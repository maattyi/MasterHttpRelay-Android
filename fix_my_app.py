#!/usr/bin/env python3
"""
fix_my_app_v2.py
================

Patches FIVE files at once:
  1. app/.../ui/components/Glass.kt          (adds asComposeRenderEffect import)
  2. app/.../ui/components/FloatingPillNav.kt(opaque pill, no glass blur)
  3. app/.../ui/screens/DashboardScreen.kt   (clean, professional layout)
  4. app/.../cert/MitmCa.kt                  (auto-save to Downloads/Velum/)
  5. app/.../ui/screens/SettingsScreen.kt    (wires SAVE TO DOWNLOADS button)

Usage:
    python3 fix_my_app_v2.py            # overwrite + commit (no push)
    python3 fix_my_app_v2.py --push     # also `git push` to current branch
    python3 fix_my_app_v2.py --no-git   # only overwrite the files
"""

from __future__ import annotations

import argparse, os, subprocess, sys, time
from pathlib import Path

# All five Kotlin source bodies live in this dict. Edit them here, not at
# the call sites, so a single pass updates every fix together.
FILES: dict[str, str] = {
    "Glass.kt":               "<<<GLASS>>>",
    "FloatingPillNav.kt":     "<<<NAV>>>",
    "DashboardScreen.kt":     "<<<DASH>>>",
    "MitmCa.kt":              "<<<MITM>>>",
    "SettingsScreen.kt":      "<<<SETTINGS>>>",
}

# Paste each file's full source between the matching markers below.
# (They are filled with the exact code shown in this answer.)

GLASS_KT = r'''<PASTE the Glass.kt code shown above>'''
NAV_KT   = r'''<PASTE the FloatingPillNav.kt code from the previous round>'''
DASH_KT  = r'''<PASTE the DashboardScreen.kt code from the previous round>'''
MITM_KT  = r'''<PASTE the MitmCa.kt code shown above>'''
SET_KT   = r'''<PASTE the SettingsScreen.kt code shown above>'''

FILES["Glass.kt"]           = GLASS_KT
FILES["FloatingPillNav.kt"] = NAV_KT
FILES["DashboardScreen.kt"] = DASH_KT
FILES["MitmCa.kt"]          = MITM_KT
FILES["SettingsScreen.kt"]  = SET_KT


def find_target(app_dir: Path, basename: str) -> Path | None:
    matches = list(app_dir.rglob(basename))
    if not matches: return None
    main = [m for m in matches if "/src/main/" in m.as_posix()]
    return (main or matches)[0]


def overwrite(target: Path, content: str, ts: str) -> None:
    if target.exists():
        backup = target.with_suffix(target.suffix + f".bak.{ts}")
        backup.write_bytes(target.read_bytes())
        print(f"   backup -> {backup.name}")
    tmp = target.with_suffix(target.suffix + ".tmp")
    tmp.write_text(content, encoding="utf-8")
    os.replace(tmp, target)
    print(f"   wrote  -> {target}")


def run_git(args, cwd):
    try:
        p = subprocess.run(["git", *args], cwd=cwd, check=False,
                           capture_output=True, text=True)
        return p.returncode, (p.stdout + p.stderr).strip()
    except FileNotFoundError:
        return 127, "git not found on PATH"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project-dir", type=Path, default=Path.cwd())
    ap.add_argument("--no-git", action="store_true")
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--branch", type=str, default=None)
    a = ap.parse_args()

    project = a.project_dir.resolve()
    app_dir = project / "app"
    if not app_dir.is_dir():
        print(f"ERROR: 'app/' not found under {project}")
        return 1

    ts = time.strftime("%Y%m%d-%H%M%S")
    print(f"Project root : {project}")
    print(f"Timestamp    : {ts}\n")

    written: list[Path] = []
    for name, body in FILES.items():
        if "<PASTE" in body or body.startswith("<<<"):
            print(f"[ABORT] {name}: source body is still a placeholder. "
                  f"Open this script and paste the code into the matching constant.")
            return 9
        target = find_target(app_dir, name)
        if target is None:
            print(f"[SKIP] {name} not found")
            continue
        print(f"[FIX] {name}")
        overwrite(target, body, ts)
        written.append(target)
        print()

    print("─" * 64)
    print(f"Patched {len(written)} / {len(FILES)} files.")

    if a.no_git or not (project / ".git").is_dir():
        return 0

    rc, out = run_git(["add", *[str(p) for p in written]], project)
    if rc != 0:
        print(out); return 3
    rc, out = run_git(
        ["commit", "-m", "fix(ui+cert): asComposeRenderEffect import + save cert to Downloads/Velum/"],
        project,
    )
    if rc != 0 and "nothing to commit" not in out.lower():
        print(out); return 4
    print(out or "Committed.")

    rc, br = run_git(["rev-parse", "--abbrev-ref", "HEAD"], project)
    branch = a.branch or (br if rc == 0 else "main")

    if a.push:
        rc, out = run_git(["push", "origin", branch], project)
        print(out)
        return 0 if rc == 0 else 5

    print("\n" + "═" * 64)
    print("Files patched and committed. Push with:")
    print(f"   git push origin {branch}")
    print("═" * 64)
    return 0


if __name__ == "__main__":
    sys.exit(main())
