"""run_pipeline.py - one-command DATA stage of the daily trade pipeline.

Automates the deterministic part of the pipeline:
    token (optional) -> fresh market snapshot -> readiness report.

The AI stages (news-scanner, backtester, fno-strategist, risk-manager) are then
orchestrated by Claude at the top level - see docs/PIPELINE.md for the stage graph.

Usage:
    python src/run_pipeline.py                                   # use existing .access_token
    python src/run_pipeline.py "https://127.0.0.1/?code=XXXX"    # exchange code first, then refresh
"""

import os
import sys
import glob
import json
import time
import subprocess

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(PROJECT_ROOT, "src")
DATA_DIR = os.path.join(PROJECT_ROOT, "data")
TOKEN_FILE = os.path.join(PROJECT_ROOT, ".access_token")
PY = sys.executable


def run(script, *args):
    """Run a sibling script with the same Python; return its exit code."""
    return subprocess.run([PY, os.path.join(SRC, script), *args]).returncode


def main():
    print("=" * 60)
    print(" DAILY TRADE PIPELINE - DATA STAGE")
    print("=" * 60)

    url = sys.argv[1] if len(sys.argv) > 1 else None

    # Stage 0: token
    if url:
        print("\n[1/3] Exchanging login code for an access token...")
        if run("get_token.py", url) != 0:
            print("\n>> Token exchange failed (see error above). Pipeline stopped.")
            sys.exit(1)
    else:
        has_token = os.path.exists(TOKEN_FILE) and open(TOKEN_FILE, encoding="utf-8").read().strip()
        if not has_token:
            print("\nNo .access_token found. Pass your login redirect URL:")
            print('   python src/run_pipeline.py "https://127.0.0.1/?code=XXXX"')
            sys.exit(1)
        print("\n[1/3] Using existing .access_token.")

    # Stage 1: fresh snapshot
    print("\n[2/3] Fetching fresh market snapshot (status / candles / option chain / news)...")
    if run("market_snapshot.py") != 0:
        print("\n>> Snapshot failed (see error above). Pipeline stopped.")
        sys.exit(1)

    # Stage 2: readiness report
    print("\n[3/3] Readiness check...")
    snaps = sorted(glob.glob(os.path.join(DATA_DIR, "snapshot_*.json")), key=os.path.getmtime)
    if not snaps:
        print(">> No snapshot file was produced. Pipeline stopped.")
        sys.exit(1)

    latest = snaps[-1]
    age_min = (time.time() - os.path.getmtime(latest)) / 60.0
    with open(latest, encoding="utf-8") as f:
        snap = json.load(f)
    ok = list(snap.get("sections", {}).keys())
    errs = list(snap.get("errors", {}).keys())

    # Highlight the sections the F&O stage depends on.
    needed = ["intraday_NIFTY", "historical_NIFTY", "option_chain_NIFTY"]
    have_fno = [s for s in needed if s in ok]
    missing_fno = [s for s in needed if s not in ok]

    print(f"   Snapshot:        {os.path.relpath(latest, PROJECT_ROOT)}  (age {age_min:.1f} min)")
    print(f"   Sections OK:     {ok}")
    if errs:
        print(f"   Sections w/err:  {errs}")
    print(f"   F&O inputs ready: {have_fno or 'NONE'}")
    if missing_fno:
        print(f"   F&O inputs MISSING: {missing_fno}  (analysis confidence will be lower)")

    print("\n" + "=" * 60)
    print(" DATA READY -> hand off to Claude for the AI stages:")
    print("   Stage A (parallel): news-scanner  +  backtester")
    print("   Stage B: fno-strategist  ->  Stage C: risk-manager (APPROVE/VETO)")
    print("   After entry: position-monitor + trade-journal")
    print("=" * 60)
    print('In chat just say: "run the pipeline" or "give me today\'s NIFTY trade".')


if __name__ == "__main__":
    main()