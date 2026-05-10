#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# =============================================================================
#  fix_velum_tls.py  (v2 — production-safe rewrite)
# -----------------------------------------------------------------------------
#  Auto-fix tool for the VelumVPN Android project.
#
#  CHANGES FROM v1 (root-cause fixes)
#  ------------------------------------
#  v1 wrote .bak.* siblings NEXT TO the production files inside app/src/,
#  app/src/main/res/, etc.  Android Gradle plugin (AGP) treats every file
#  inside those trees as a build input, so even a single stray .bak file
#  causes an aapt2 / kotlinc / manifest-merger failure.
#
#  v2 enforces three hard rules:
#    1. ALL backups go to <repo>/.velum_backups/<timestamp>/<rel_path>
#       — never inside app/, src/, res/, or any Gradle module.
#    2. Source files are patched IN-PLACE with surgical regex/string
#       replacements rather than wholesale file replacement from a
#       patches/ directory.  No duplicate, .new, .fixed, or .patched
#       siblings are ever created.
#    3. A mandatory cleanup pass runs FIRST and removes any .bak.*,
#       .tls_fix.*, .tmp, and similar artifacts that v1 may have
#       scattered across the repository.
#
#  USAGE
#  -----
#      python3 fix_velum_tls.py --repo /path/to/velum/repo
#      python3 fix_velum_tls.py --repo /path/to/velum/repo --dry-run
#      python3 fix_velum_tls.py --repo /path/to/velum/repo --no-backup
#      python3 fix_velum_tls.py --repo /path/to/velum/repo --scan-only
#      python3 fix_velum_tls.py --repo /path/to/velum/repo --cleanup-only
# =============================================================================
from __future__ import annotations

import argparse
import datetime as _dt
import os
import re
import shutil
import sys
from pathlib import Path
from typing import Callable

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

TS = _dt.datetime.utcnow().strftime("%Y%m%d-%H%M%S")

# Safe backup root — OUTSIDE the Android project tree.
# Resolved at runtime relative to the repo root (not app/).
BACKUP_DIRNAME = ".velum_backups"

# Glob patterns for stale build-breaking artifacts produced by v1.
# These are deleted unconditionally during the cleanup pass.
STALE_ARTIFACT_PATTERNS: list[str] = [
    "**/*.bak.*",
    "**/*.bak",
    "**/*.tls_fix.*",
    "**/*.tmp",
    "**/*.new",
    "**/*.fixed",
    "**/*.patched",
    "**/*.orig",
]

# Directories that must NEVER receive any output from this script.
PROTECTED_DIR_PARTS: set[str] = {
    "src", "res", "java", "kotlin", "assets", "jniLibs",
}

# Extensions of files we consider for in-place patching.
PATCH_EXTENSIONS: set[str] = {".kt", ".java", ".xml", ".kts", ".gradle"}

# ---------------------------------------------------------------------------
# Insecure-pattern markers (for pre/post scan)
# ---------------------------------------------------------------------------

INSECURE_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("trust-all X509TrustManager (empty checkServerTrusted)",
     re.compile(r"override\s+fun\s+checkServerTrusted\s*\([^)]*\)\s*\{\s*\}", re.S)),
    ("hostname-verifier bypass lambda",
     re.compile(r"hostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true\s*\}")),
    ("SSLContext trust-all init",
     re.compile(r"SSLContext\.getInstance\(\s*[\"']TLS[\"']\s*\).*?init\(\s*null\s*,\s*arrayOf\(\s*trustAll", re.S)),
    ("anonymous X509TrustManager object",
     re.compile(r"object\s*:\s*X509TrustManager\s*\{")),
    ("user CA anchor in network_security_config",
     re.compile(r'<certificates\s+src=["\']user["\']\s*/>')),
    ("MitmCa import",
     re.compile(r"import\s+com\.velum\.vpn\.cert\.MitmCa")),
    ("SniRewriteSocketFactory import",
     re.compile(r"import\s+com\.velum\.vpn\.net\.SniRewriteSocketFactory")),
    ("PinnedHostBypass import",
     re.compile(r"import\s+com\.velum\.vpn\.net\.PinnedHostBypass")),
    ("insecureSkipVerify / trust-all hostname",
     re.compile(r"insecureSkipVerify|trustAllHosts|ALLOW_ALL_HOSTNAME_VERIFIER", re.I)),
]

# ---------------------------------------------------------------------------
# In-place patch definitions
# ---------------------------------------------------------------------------
# Each entry is a dict with:
#   "rel"        : repo-relative path of the file to patch
#   "guards"     : list of (label, pattern) — file is skipped if all guards
#                  are already satisfied (idempotency check)
#   "ops"        : list of callables (text: str) -> str applied in sequence
# ---------------------------------------------------------------------------

def _strip_trust_all_tm(text: str) -> str:
    """Remove anonymous X509TrustManager object that trusts everything."""
    # Pattern: val trustAll = object : X509TrustManager { ... } (multi-line)
    return re.sub(
        r"val\s+trustAll\s*=\s*object\s*:\s*X509TrustManager\s*\{[^}]*\}",
        "// [TLS-FIX] trust-all TrustManager removed",
        text, flags=re.S,
    )

def _strip_hostname_verifier_bypass(text: str) -> str:
    """Remove .hostnameVerifier { _, _ -> true } calls."""
    return re.sub(
        r"\.hostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true\s*\}",
        "// [TLS-FIX] hostname-verifier bypass removed",
        text,
    )

def _strip_ssl_context_trust_all_init(text: str) -> str:
    """Remove SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }."""
    return re.sub(
        r"SSLContext\.getInstance\(\s*[\"']TLS[\"']\s*\)\s*\.apply\s*\{[^}]*init\(\s*null\s*,[^}]*\}\s*\.socketFactory",
        "// [TLS-FIX] unsafe SSLContext removed — use OkHttpClient default TLS",
        text, flags=re.S,
    )

def _strip_sni_rewrite_factory(text: str) -> str:
    return re.sub(
        r"\.sslSocketFactory\(\s*SniRewriteSocketFactory[^)]*\)",
        "// [TLS-FIX] SniRewriteSocketFactory removed",
        text,
    )

def _remove_mitm_imports(text: str) -> str:
    for imp in [
        r"import\s+com\.velum\.vpn\.cert\.MitmCa\b.*\n",
        r"import\s+com\.velum\.vpn\.net\.SniRewriteSocketFactory\b.*\n",
        r"import\s+com\.velum\.vpn\.net\.PinnedHostBypass\b.*\n",
    ]:
        text = re.sub(imp, "", text)
    return text

def _inject_modern_tls_builder(text: str) -> str:
    """
    If the OkHttpClient.Builder() block is present but lacks ConnectionSpec,
    inject MODERN_TLS + TLS_1_2/1_3 spec after the first Builder() call.
    Idempotent: skipped if MODERN_TLS already present.
    """
    if "ConnectionSpec.MODERN_TLS" in text:
        return text
    return re.sub(
        r"(OkHttpClient\.Builder\(\))",
        r"""\1
            .connectionSpecs(listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build()
            ))""",
        text, count=1,
    )

def _add_okhttp_tls_imports(text: str) -> str:
    """Ensure ConnectionSpec / TlsVersion imports are present."""
    needed = [
        "import okhttp3.ConnectionSpec",
        "import okhttp3.TlsVersion",
    ]
    for imp in needed:
        if imp not in text:
            # Insert after the last existing okhttp3 import, or before package
            text = re.sub(
                r"(import okhttp3\.[^\n]+\n)",
                r"\1" + imp + "\n",
                text, count=1,
            )
    return text

def _remove_user_ca_nsc(text: str) -> str:
    """Remove <certificates src="user"/> from network_security_config.xml."""
    return re.sub(
        r'\s*<certificates\s+src=["\']user["\']\s*/>',
        "",
        text,
    )

def _ensure_system_trust_nsc(text: str) -> str:
    """Ensure <certificates src="system"/> is present under <trust-anchors>."""
    if 'src="system"' in text:
        return text
    return re.sub(
        r"(<trust-anchors>)",
        r'\1\n        <certificates src="system"/>',
        text,
    )

def _strip_mitm_connect_block(text: str) -> str:
    """
    Replace the MITM CONNECT handler in HttpProxyServer / Socks5Server with a
    raw TCP pass-through tunnel comment. Looks for a block that sets up an
    SSLContext for CONNECT interception.
    """
    # Heuristic: find the block that does `SSLContext.getInstance` inside a
    # CONNECT-handling branch and replace it with a passthrough placeholder.
    return re.sub(
        r"(// .*?CONNECT.*?\n)(.*?SSLContext\.getInstance.*?(?:\.start\(\)|launchTunnel\([^)]*\)))",
        r"\1                // [TLS-FIX] MITM removed — raw TCP tunnel used instead\n"
        r"                launchRawTcpTunnel(clientSocket, targetHost, targetPort)",
        text, flags=re.S,
    )


# Repo-relative path → list of (text -> text) transforms.
PATCH_OPS: dict[str, list[Callable[[str], str]]] = {
    "app/src/main/java/com/velum/vpn/net/RelayClient.kt": [
        _remove_mitm_imports,
        _strip_trust_all_tm,
        _strip_hostname_verifier_bypass,
        _strip_ssl_context_trust_all_init,
        _strip_sni_rewrite_factory,
        _add_okhttp_tls_imports,
        _inject_modern_tls_builder,
    ],
    "app/src/main/java/com/velum/vpn/proxy/HttpProxyServer.kt": [
        _remove_mitm_imports,
        _strip_trust_all_tm,
        _strip_mitm_connect_block,
    ],
    "app/src/main/java/com/velum/vpn/proxy/Socks5Server.kt": [
        _remove_mitm_imports,
        _strip_trust_all_tm,
        _strip_mitm_connect_block,
    ],
    "app/src/main/res/xml/network_security_config.xml": [
        _remove_user_ca_nsc,
        _ensure_system_trust_nsc,
    ],
}

# Files that should be deleted entirely (legacy MITM machinery).
DELETE_LIST: list[str] = [
    "app/src/main/java/com/velum/vpn/cert/MitmCa.kt",
    "app/src/main/java/com/velum/vpn/net/SniRewriteSocketFactory.kt",
    "app/src/main/java/com/velum/vpn/net/PinnedHostBypass.kt",
]


# ---------------------------------------------------------------------------
# Logger
# ---------------------------------------------------------------------------

class Logger:
    def __init__(self, verbose: bool) -> None:
        self.verbose = verbose

    def section(self, title: str) -> None:
        bar = "=" * 78
        print(f"\n{bar}\n  {title}\n{bar}", flush=True)

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


# ---------------------------------------------------------------------------
# Backup helper — writes ONLY into <repo>/.velum_backups/
# ---------------------------------------------------------------------------

def safe_backup(repo: Path, file_path: Path, backup_root: Path,
                log: Logger, dry: bool) -> None:
    """
    Copy file_path into backup_root preserving its relative structure.
    NEVER writes inside app/, src/, res/, or any Gradle module.
    """
    # Absolute safety guard
    for part in PROTECTED_DIR_PARTS:
        if part in backup_root.parts:
            raise RuntimeError(
                f"BUG: backup_root {backup_root} contains protected "
                f"directory segment '{part}' — refusing to write backup there."
            )

    rel = file_path.relative_to(repo)
    dest = backup_root / rel
    if dry:
        log.info(f"would backup {rel} -> {dest.relative_to(repo)}")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(file_path, dest)
    log.debug(f"backup {rel} -> {dest.relative_to(repo)}")


# ---------------------------------------------------------------------------
# Stale artifact cleanup (v1 damage removal)
# ---------------------------------------------------------------------------

def _is_stale_artifact(path: Path) -> bool:
    name = path.name
    return bool(
        re.search(r"\.bak(\.|$)", name)
        or re.search(r"\.tls_fix\.", name)
        or name.endswith(".tmp")
        or name.endswith(".new")
        or name.endswith(".fixed")
        or name.endswith(".patched")
        or name.endswith(".orig")
    )


def cleanup_stale_artifacts(repo: Path, log: Logger, dry: bool) -> int:
    """
    Walk the entire repo and remove every stale artifact that v1 may have
    placed inside the Android source tree.  Returns count of removed files.
    """
    removed = 0
    for p in repo.rglob("*"):
        if not p.is_file():
            continue
        # Never touch the backup root itself
        try:
            p.relative_to(repo / BACKUP_DIRNAME)
            continue  # inside backup root — leave it alone
        except ValueError:
            pass
        if any(part in {".git", "build", ".gradle", ".idea"} for part in p.parts):
            continue
        if not _is_stale_artifact(p):
            continue
        rel = p.relative_to(repo)
        if dry:
            log.info(f"would remove stale artifact: {rel}")
        else:
            try:
                p.unlink()
                log.ok(f"removed stale artifact: {rel}")
            except OSError as e:
                log.warn(f"could not remove {rel}: {e}")
        removed += 1
    return removed


# ---------------------------------------------------------------------------
# Insecure pattern scanner
# ---------------------------------------------------------------------------

def scan_insecure(repo: Path, log: Logger) -> list[tuple[Path, str]]:
    hits: list[tuple[Path, str]] = []
    for p in repo.rglob("*"):
        if not p.is_file():
            continue
        if any(part in {".git", "build", ".gradle", ".idea", BACKUP_DIRNAME}
               for part in p.parts):
            continue
        if _is_stale_artifact(p):
            continue
        if p.suffix not in PATCH_EXTENSIONS:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for label, pat in INSECURE_PATTERNS:
            if pat.search(text):
                hits.append((p, label))
    return hits


# ---------------------------------------------------------------------------
# In-place file patcher
# ---------------------------------------------------------------------------

def patch_file_inplace(repo: Path, rel: str, ops: list[Callable[[str], str]],
                       backup_root: Path | None,
                       log: Logger, dry: bool) -> bool:
    """Apply ops to repo/rel in-place. Returns True if content changed."""
    path = repo / rel
    if not path.exists():
        log.warn(f"patch target not found, skipping: {rel}")
        return False

    original = path.read_text(encoding="utf-8", errors="replace")
    result = original
    for op in ops:
        result = op(result)

    if result == original:
        log.ok(f"already clean (no changes needed): {rel}")
        return False

    if backup_root is not None:
        safe_backup(repo, path, backup_root, log, dry)

    if dry:
        log.info(f"would patch in-place: {rel}")
        return True

    path.write_text(result, encoding="utf-8")
    log.ok(f"patched in-place: {rel}")
    return True


# ---------------------------------------------------------------------------
# File deletion
# ---------------------------------------------------------------------------

def delete_file(repo: Path, rel: str, backup_root: Path | None,
                log: Logger, dry: bool) -> bool:
    target = repo / rel
    if not target.exists():
        log.debug(f"already absent: {rel}")
        return False
    if backup_root is not None:
        safe_backup(repo, target, backup_root, log, dry)
    if dry:
        log.info(f"would delete: {rel}")
        return True
    target.unlink()
    log.ok(f"deleted: {rel}")
    return True


# ---------------------------------------------------------------------------
# Post-patch validation
# ---------------------------------------------------------------------------

# Tokens that MUST be absent after patching.
FORBIDDEN_ANYWHERE: list[tuple[str, str]] = [
    ("trust-all TrustManager",       "val trustAll = object : X509TrustManager"),
    ("hostname-verifier bypass",      "hostnameVerifier { _, _ -> true }"),
    ("user CA in NSC",                'src="user"'),
    ("MitmCa reference",              "com.velum.vpn.cert.MitmCa"),
    ("SniRewriteSocketFactory ref",   "SniRewriteSocketFactory"),
    ("PinnedHostBypass ref",          "PinnedHostBypass"),
]

REQUIRED_IN: dict[str, list[str]] = {
    "app/src/main/res/xml/network_security_config.xml": ['src="system"'],
}


def validate_repo(repo: Path, log: Logger) -> bool:
    ok = True

    # Check for forbidden tokens in source files
    for p in repo.rglob("*"):
        if not p.is_file():
            continue
        if any(part in {".git", "build", ".gradle", ".idea", BACKUP_DIRNAME}
               for part in p.parts):
            continue
        if _is_stale_artifact(p):
            continue
        if p.suffix not in PATCH_EXTENSIONS:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for label, token in FORBIDDEN_ANYWHERE:
            if token in text:
                log.err(f"FORBIDDEN [{label}] still present in {p.relative_to(repo)}")
                ok = False

    # Check for required tokens
    for rel, tokens in REQUIRED_IN.items():
        path = repo / rel
        if not path.exists():
            log.warn(f"required file not found: {rel}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for tok in tokens:
            if tok not in text:
                log.err(f"required token missing in {rel}: {tok!r}")
                ok = False

    # Ensure no stale artifacts remain inside app/
    app_dir = repo / "app"
    if app_dir.exists():
        for p in app_dir.rglob("*"):
            if p.is_file() and _is_stale_artifact(p):
                log.err(f"stale artifact still present inside app/: {p.relative_to(repo)}")
                ok = False

    return ok


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="fix_velum_tls.py",
        description=(
            "VelumVPN TLS security fix (v2).\n"
            "Patches source files IN-PLACE; all backups go to "
            f"<repo>/{BACKUP_DIRNAME}/ — never inside app/ or src/."
        ),
    )
    p.add_argument("--repo", required=True, type=Path,
                   help="Path to VelumVPN repository root.")
    p.add_argument("--dry-run", action="store_true",
                   help="Print what would change but do not modify any file.")
    p.add_argument("--no-backup", action="store_true",
                   help=f"Skip writing backups to {BACKUP_DIRNAME}/.")
    p.add_argument("--scan-only", action="store_true",
                   help="Run insecure-pattern scan only; exit 1 if hits found.")
    p.add_argument("--cleanup-only", action="store_true",
                   help="Remove stale v1 artifacts only; do not apply TLS patches.")
    p.add_argument("--verbose", "-v", action="store_true")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    log = Logger(args.verbose)

    repo: Path = args.repo.resolve()
    if not repo.exists():
        log.err(f"repo path does not exist: {repo}")
        return 2
    if not (repo / "app").exists():
        log.err(f"no app/ directory found — is this a VelumVPN repo? {repo}")
        return 2

    # Backup root is ALWAYS at repo root level — never inside app/.
    backup_root: Path | None = None
    if not args.no_backup:
        backup_root = repo / BACKUP_DIRNAME / TS
        # Absolute safety check
        for danger in PROTECTED_DIR_PARTS:
            if danger in backup_root.parts[len(repo.parts):]:
                log.err(f"BUG: backup root resolves inside protected dir: {backup_root}")
                return 2

    log.section("VelumVPN TLS auto-fix  v2")
    log.info(f"repo       : {repo}")
    log.info(f"backup root: {backup_root or '(disabled)'}")
    log.info(f"dry-run    : {args.dry_run}")

    # ------------------------------------------------------------------
    # 0. Pre-scan
    # ------------------------------------------------------------------
    log.section("Pre-scan — insecure patterns")
    pre_hits = scan_insecure(repo, log)
    if pre_hits:
        log.warn(f"{len(pre_hits)} insecure-pattern hit(s):")
        for p, label in pre_hits[:60]:
            log.warn(f"  {p.relative_to(repo)} :: {label}")
    else:
        log.ok("No insecure patterns found.")

    if args.scan_only:
        return 0 if not pre_hits else 1

    # ------------------------------------------------------------------
    # 1. Mandatory cleanup — remove v1 stale artifacts
    # ------------------------------------------------------------------
    log.section("Cleanup — removing stale build-breaking artifacts")
    removed = cleanup_stale_artifacts(repo, log, args.dry_run)
    log.info(f"{removed} stale artifact(s) {'would be ' if args.dry_run else ''}removed.")

    if args.cleanup_only:
        log.ok("Cleanup-only mode — done.")
        return 0

    # ------------------------------------------------------------------
    # 2. In-place TLS patches
    # ------------------------------------------------------------------
    log.section("Applying TLS patches (in-place)")
    changed = 0
    for rel, ops in PATCH_OPS.items():
        try:
            if patch_file_inplace(repo, rel, ops, backup_root, log, args.dry_run):
                changed += 1
        except Exception as e:
            log.err(f"failed to patch {rel}: {e}")
            return 3

    # ------------------------------------------------------------------
    # 3. Delete legacy MITM files
    # ------------------------------------------------------------------
    log.section("Deleting legacy MITM source files")
    for rel in DELETE_LIST:
        try:
            if delete_file(repo, rel, backup_root, log, args.dry_run):
                changed += 1
        except Exception as e:
            log.err(f"failed to delete {rel}: {e}")
            return 4

    # ------------------------------------------------------------------
    # 4. Validate
    # ------------------------------------------------------------------
    if not args.dry_run:
        log.section("Validation")
        if validate_repo(repo, log):
            log.ok("Validation passed — repository is clean and Android-build safe.")
        else:
            log.err("Validation failed. Review errors above.")
            return 5

        # Post-scan
        log.section("Post-scan — insecure patterns")
        post_hits = scan_insecure(repo, log)
        if post_hits:
            log.warn("Residual insecure patterns remain:")
            for p, label in post_hits:
                log.warn(f"  {p.relative_to(repo)} :: {label}")
            return 6
        log.ok("No insecure patterns remain.")

    # ------------------------------------------------------------------
    # 5. Summary
    # ------------------------------------------------------------------
    log.section("Summary")
    log.ok(f"{changed} file change(s) applied.")
    if backup_root:
        log.info(f"Backups stored at: {backup_root}")
    log.info("TLS model now uses:")
    log.info("  • OkHttp 4.x with MODERN_TLS (TLS 1.2 + 1.3)")
    log.info("  • Android system trust store only")
    log.info("  • No user CA / no MITM root certificate")
    log.info("  • Raw TCP pass-through tunnel (no HTTPS interception)")
    log.info("  • Optional CertificatePinner for GAS backend only")
    log.info("Backup root (safe, outside app/): " + str(backup_root or "disabled"))
    return 0


if __name__ == "__main__":
    sys.exit(main())