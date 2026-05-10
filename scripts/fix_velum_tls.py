#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# =============================================================================
#  fix_velum_tls.py
# -----------------------------------------------------------------------------
#  Production-grade auto-fix tool for the VelumVPN Android project.
#
#  PURPOSE
#  -------
#  This script statically scans a VelumVPN repository checkout and rewrites the
#  TLS / certificate handling layer into the modern Android-safe model:
#
#    * removes "trust-all" X509TrustManager logic
#    * removes unsafe HostnameVerifier overrides ("{ _, _ -> true }")
#    * removes the SNI-rewrite SSLSocketFactory (was used to spoof Google front)
#    * replaces RelayClient's OkHttp builder with a hardened TLS 1.2/1.3 client
#      that uses the Android system trust store + optional CertificatePinner
#    * removes the global MITM root-CA generation system (MitmCa.kt)
#    * removes "user CA" trust anchor from network_security_config.xml
#    * removes the FileProvider/CA install UX from the build (kept inert)
#    * removes the MITM CONNECT path inside HttpProxyServer / Socks5Server and
#      replaces it with a pure pass-through tunnel (raw TCP relay) — keeping
#      the censorship-bypass tunnel without intercepting any other app's TLS
#    * patches DiagnosticManager into a TLS reachability test (no MITM checks)
#
#  DESIGN PRINCIPLES
#  -----------------
#  1. Idempotent — running twice does nothing on the second pass.
#  2. Backups first — every modified file is copied to <file>.bak.<timestamp>.
#  3. No Android SDK / no Gradle / no JVM required.
#  4. Pure stdlib — no pip dependencies.
#  5. Logs every action; fails safely on the first hard error per file.
#  6. Replacement files live in `patches/` next to the script and are
#     dropped in verbatim. The script never tries to surgically patch
#     deeply broken Kotlin — it swaps the file as a whole, atomically.
#
#  USAGE
#  -----
#      python3 fix_velum_tls.py --repo /path/to/velum/repo
#      python3 fix_velum_tls.py --repo /path/to/velum/repo --dry-run
#      python3 fix_velum_tls.py --repo /path/to/velum/repo --no-backup
#
# =============================================================================
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import os
import re
import shutil
import sys
from pathlib import Path
from typing import Iterable

# -----------------------------------------------------------------------------
# Constants and small utilities
# -----------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
PATCHES_DIR = SCRIPT_DIR / "patches"

TS = _dt.datetime.utcnow().strftime("%Y%m%d-%H%M%S")
BACKUP_SUFFIX = f".bak.tls_fix.{TS}"

# Files we replace wholesale (key = repo-relative path, value = patch filename)
REPLACE_MAP: dict[str, str] = {
    "app/src/main/java/com/velum/vpn/net/RelayClient.kt":          "RelayClient.kt",
    "app/src/main/java/com/velum/vpn/net/SecureHttpClient.kt":     "SecureHttpClient.kt",        # NEW
    "app/src/main/java/com/velum/vpn/proxy/HttpProxyServer.kt":    "HttpProxyServer.kt",
    "app/src/main/java/com/velum/vpn/proxy/Socks5Server.kt":       "Socks5Server.kt",
    "app/src/main/java/com/velum/vpn/proxy/LocalProxyService.kt":  "LocalProxyService.kt",
    "app/src/main/java/com/velum/vpn/vpn/RelayVpnService.kt":      "RelayVpnService.kt",
    "app/src/main/java/com/velum/vpn/core/DiagnosticManager.kt":   "DiagnosticManager.kt",
    "app/src/main/java/com/velum/vpn/core/AppContainer.kt":        "AppContainer.kt",
    "app/src/main/java/com/velum/vpn/ui/screens/SettingsScreen.kt":"SettingsScreen.kt",
    "app/src/main/res/xml/network_security_config.xml":            "network_security_config.xml",
    "app/src/main/AndroidManifest.xml":                            "AndroidManifest.xml",
    "app/build.gradle.kts":                                        "build.gradle.kts",
}

# Files we delete (legacy MITM machinery that has no place in the new model)
DELETE_LIST: list[str] = [
    "app/src/main/java/com/velum/vpn/cert/MitmCa.kt",
    "app/src/main/java/com/velum/vpn/net/SniRewriteSocketFactory.kt",
    "app/src/main/java/com/velum/vpn/net/PinnedHostBypass.kt",
    "app/src/main/AndroidManifest_provider_addendum.txt",
    "app/ca.crt",
]

# Cleanup of stale .bak.* siblings produced by previous fix scripts
BAK_STALE_GLOBS: list[str] = [
    "app/src/main/java/com/velum/vpn/**/*.bak.*",
]

# Insecure-pattern markers — used by --scan to flag files that still need work.
# Patterns are deliberately defensive (substring + regex form).
INSECURE_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("trust-all X509TrustManager",
     re.compile(r"checkServerTrusted\s*\([^)]*\)\s*\{\s*\}", re.S)),
    ("hostname verifier bypass",
     re.compile(r"hostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true\s*\}")),
    ("SSLContext.init(null, trustAll",
     re.compile(r"SSLContext\.getInstance\(\s*\"TLS\"\s*\)\s*\.\s*apply\s*\{\s*init\(\s*null\s*,\s*arrayOf\(\s*trustAll", re.S)),
    ("naive trust manager class",
     re.compile(r"object\s*:\s*X509TrustManager\s*\{")),
    ("user CA in NSC",
     re.compile(r"<certificates\s+src=\"user\"\s*/>")),
    ("MitmCa import",
     re.compile(r"import\s+com\.velum\.vpn\.cert\.MitmCa")),
    ("SniRewriteSocketFactory import",
     re.compile(r"import\s+com\.velum\.vpn\.net\.SniRewriteSocketFactory")),
    ("PinnedHostBypass import",
     re.compile(r"import\s+com\.velum\.vpn\.net\.PinnedHostBypass")),
]


class Logger:
    """Minimal stderr logger with section headers."""
    def __init__(self, verbose: bool) -> None:
        self.verbose = verbose

    def section(self, title: str) -> None:
        bar = "=" * 78
        print(f"\n{bar}\n{title}\n{bar}", flush=True)

    def info(self, msg: str) -> None:
        print(f"[INFO ] {msg}", flush=True)

    def ok(self, msg: str) -> None:
        print(f"[ OK  ] {msg}", flush=True)

    def warn(self, msg: str) -> None:
        print(f"[WARN ] {msg}", flush=True)

    def err(self, msg: str) -> None:
        print(f"[ERR  ] {msg}", flush=True)

    def debug(self, msg: str) -> None:
        if self.verbose:
            print(f"[DEBUG] {msg}", flush=True)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    return sha256(path.read_bytes())


# -----------------------------------------------------------------------------
# Core operations
# -----------------------------------------------------------------------------

def backup(path: Path, log: Logger, dry: bool, do_backup: bool) -> None:
    """Copy file to .bak.<ts> if backups enabled and target exists."""
    if not do_backup or not path.exists():
        return
    bak = path.with_suffix(path.suffix + BACKUP_SUFFIX)
    if bak.exists():
        log.debug(f"backup already exists: {bak}")
        return
    if dry:
        log.info(f"would backup {path} -> {bak.name}")
        return
    shutil.copy2(path, bak)
    log.debug(f"backup {path.name} -> {bak.name}")


def replace_file(repo: Path, rel: str, patch_name: str,
                 log: Logger, dry: bool, do_backup: bool) -> bool:
    """Replace repo/rel with patches/<patch_name>. Returns True if changed."""
    src = PATCHES_DIR / patch_name
    if not src.exists():
        log.err(f"missing patch file: {src}")
        return False
    dst = repo / rel
    dst.parent.mkdir(parents=True, exist_ok=True)

    new_bytes = src.read_bytes()
    if dst.exists() and dst.read_bytes() == new_bytes:
        log.ok(f"already-patched: {rel}")
        return False

    backup(dst, log, dry, do_backup)
    if dry:
        log.info(f"would write {rel} ({len(new_bytes)} bytes)")
        return True
    dst.write_bytes(new_bytes)
    log.ok(f"wrote {rel}")
    return True


def delete_file(repo: Path, rel: str, log: Logger, dry: bool, do_backup: bool) -> bool:
    """Delete repo/rel after backup. Idempotent."""
    target = repo / rel
    if not target.exists():
        log.debug(f"already absent: {rel}")
        return False
    backup(target, log, dry, do_backup)
    if dry:
        log.info(f"would delete {rel}")
        return True
    if target.is_file() or target.is_symlink():
        target.unlink()
    else:
        shutil.rmtree(target)
    log.ok(f"deleted {rel}")
    return True


def cleanup_stale_baks(repo: Path, log: Logger, dry: bool) -> int:
    """Remove old *.bak.YYYYMMDD-HHMMSS files left by previous tools."""
    n = 0
    for glob_expr in BAK_STALE_GLOBS:
        for p in repo.glob(glob_expr):
            # never touch our own freshly-made backups
            if BACKUP_SUFFIX in p.name:
                continue
            if not re.search(r"\.bak\.\d{8}-\d{6}$", p.name):
                continue
            if dry:
                log.info(f"would remove stale backup {p.relative_to(repo)}")
            else:
                try:
                    p.unlink()
                    log.debug(f"removed stale backup {p.relative_to(repo)}")
                except Exception as e:
                    log.warn(f"could not remove stale {p}: {e}")
            n += 1
    return n


def scan_insecure(repo: Path, log: Logger) -> list[tuple[Path, str]]:
    """Walk the repo and report any leftover insecure pattern."""
    hits: list[tuple[Path, str]] = []
    for p in repo.rglob("*"):
        if not p.is_file():
            continue
        if any(part in (".git", "build", ".gradle", ".idea") for part in p.parts):
            continue
        if p.suffix not in (".kt", ".java", ".xml", ".kts", ".gradle"):
            continue
        # skip patches + backups
        if BACKUP_SUFFIX in p.name or re.search(r"\.bak\.\d{8}-\d{6}$", p.name):
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for label, pat in INSECURE_PATTERNS:
            if pat.search(text):
                hits.append((p, label))
    return hits


# -----------------------------------------------------------------------------
# Validation pass — verify the new RelayClient compiles-shaped (string-only)
# -----------------------------------------------------------------------------

# Tokens that MUST appear in the new SecureHttpClient.kt — the place where
# TLS configuration now lives.
REQUIRED_TOKENS_SECURE_HTTP = (
    "ConnectionSpec.MODERN_TLS",
    "TLS_1_3",
    "TLS_1_2",
    "CertificatePinner",
    "systemTrustManager",
)

# Tokens that MUST appear in the refactored RelayClient.kt — proves the
# old trust-all + SNI-rewrite system is gone.
REQUIRED_TOKENS_RELAY = (
    "SecureHttpClient.buildGasClient",
)

# Tokens that MUST NOT appear anywhere in the refactored RelayClient.kt.
FORBIDDEN_TOKENS_RELAY = (
    "trustAll",
    "SniRewriteSocketFactory",
    "hostnameVerifier { _, _ -> true }",
)

REQUIRED_TOKENS_NSC = (
    'src="system"',
)

FORBIDDEN_TOKENS_NSC = (
    'src="user"',
)


def validate_repo(repo: Path, log: Logger) -> bool:
    ok = True

    # SecureHttpClient.kt — must define the hardened OkHttp factory.
    sec_path = repo / "app/src/main/java/com/velum/vpn/net/SecureHttpClient.kt"
    if sec_path.exists():
        text = sec_path.read_text(encoding="utf-8", errors="replace")
        for tok in REQUIRED_TOKENS_SECURE_HTTP:
            if tok not in text:
                log.err(f"SecureHttpClient.kt missing required token: {tok!r}")
                ok = False
    else:
        log.err("SecureHttpClient.kt not found after patching")
        ok = False

    # RelayClient.kt — must use SecureHttpClient and must not contain
    # any trust-all / SNI-rewrite leftovers.
    relay_path = repo / "app/src/main/java/com/velum/vpn/net/RelayClient.kt"
    if relay_path.exists():
        text = relay_path.read_text(encoding="utf-8", errors="replace")
        for tok in REQUIRED_TOKENS_RELAY:
            if tok not in text:
                log.err(f"RelayClient.kt missing required token: {tok!r}")
                ok = False
        for tok in FORBIDDEN_TOKENS_RELAY:
            if tok in text:
                log.err(f"RelayClient.kt still contains forbidden token: {tok!r}")
                ok = False
    else:
        log.err("RelayClient.kt not found after patching")
        ok = False

    nsc = repo / "app/src/main/res/xml/network_security_config.xml"
    if nsc.exists():
        text = nsc.read_text(encoding="utf-8", errors="replace")
        for tok in REQUIRED_TOKENS_NSC:
            if tok not in text:
                log.err(f"network_security_config missing token: {tok!r}")
                ok = False
        for tok in FORBIDDEN_TOKENS_NSC:
            if tok in text:
                log.err(f"network_security_config still contains: {tok!r}")
                ok = False
    return ok


# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="fix_velum_tls.py",
        description="Refactor VelumVPN Android repo to a production-safe TLS "
                    "model (OkHttp + system trust + cert pinning, no MITM).",
    )
    p.add_argument("--repo", required=True, type=Path,
                   help="Path to VelumVPN repository root (contains app/, settings.gradle.kts).")
    p.add_argument("--dry-run", action="store_true",
                   help="Print what would change but do not modify files.")
    p.add_argument("--no-backup", action="store_true",
                   help="Do not produce .bak.tls_fix.<ts> sibling files.")
    p.add_argument("--scan-only", action="store_true",
                   help="Run insecure-pattern scan and exit. Does not modify the repo.")
    p.add_argument("--verbose", "-v", action="store_true",
                   help="Verbose debug logging.")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    log = Logger(args.verbose)

    repo: Path = args.repo.resolve()
    if not repo.exists():
        log.err(f"repo path does not exist: {repo}")
        return 2
    if not (repo / "app").exists():
        log.err(f"this does not look like a VelumVPN repo (no app/ dir): {repo}")
        return 2
    if not PATCHES_DIR.exists():
        log.err(f"patches dir missing: {PATCHES_DIR}")
        return 2

    log.section("VelumVPN TLS auto-fix")
    log.info(f"repo:          {repo}")
    log.info(f"patches dir:   {PATCHES_DIR}")
    log.info(f"dry-run:       {args.dry_run}")
    log.info(f"backups:       {not args.no_backup}")

    # ----- pre-scan -------------------------------------------------------
    log.section("Pre-scan")
    pre_hits = scan_insecure(repo, log)
    if pre_hits:
        log.warn(f"Found {len(pre_hits)} insecure-pattern hits:")
        for p, label in pre_hits[:60]:
            log.warn(f"  {p.relative_to(repo)} :: {label}")
    else:
        log.ok("No insecure patterns found in pre-scan.")

    if args.scan_only:
        return 0 if not pre_hits else 1

    # ----- replace --------------------------------------------------------
    log.section("Replacing files")
    changed = 0
    for rel, patch in REPLACE_MAP.items():
        try:
            if replace_file(repo, rel, patch, log, args.dry_run, not args.no_backup):
                changed += 1
        except Exception as e:
            log.err(f"failed to replace {rel}: {e}")
            return 3

    # ----- delete ---------------------------------------------------------
    log.section("Deleting legacy MITM files")
    for rel in DELETE_LIST:
        try:
            if delete_file(repo, rel, log, args.dry_run, not args.no_backup):
                changed += 1
        except Exception as e:
            log.err(f"failed to delete {rel}: {e}")
            return 4

    # ----- cleanup --------------------------------------------------------
    log.section("Cleaning up stale .bak.* files from previous fix tools")
    cleanup_stale_baks(repo, log, args.dry_run)

    # ----- validate -------------------------------------------------------
    if not args.dry_run:
        log.section("Validating result")
        if validate_repo(repo, log):
            log.ok("Validation passed.")
        else:
            log.err("Validation failed — please review the errors above.")
            return 5

        # ----- post-scan --------------------------------------------------
        log.section("Post-scan")
        post_hits = scan_insecure(repo, log)
        if post_hits:
            log.warn("Some insecure patterns remain after fix:")
            for p, label in post_hits:
                log.warn(f"  {p.relative_to(repo)} :: {label}")
            return 6
        log.ok("No insecure patterns remain.")

    log.section("Summary")
    log.ok(f"{changed} file change(s) applied.")
    log.info("Backups carry the suffix " + BACKUP_SUFFIX)
    log.info("Done. The project is now using:")
    log.info("  - OkHttp 4.x with TLS 1.2 / 1.3 (ConnectionSpec.MODERN_TLS)")
    log.info("  - Android system trust store only (no user CA dependency)")
    log.info("  - Optional CertificatePinner for the GAS backend")
    log.info("  - VpnService + raw TCP CONNECT tunnel (no MITM)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
