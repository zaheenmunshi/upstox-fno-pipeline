"""cleanup_data.py - safely remove generated market-data files so storage doesn't bloat.

This ONLY ever touches the project's data/ output (and optionally __pycache__).
It NEVER deletes source code, .env, .access_token, agents, the venv, or memory.

Examples:
    python src/cleanup_data.py                  # delete ALL generated files in data/
    python src/cleanup_data.py --keep-latest 5  # keep the 5 newest, delete the rest
    python src/cleanup_data.py --older-than 3   # delete files older than 3 days
    python src/cleanup_data.py --pycache        # also clear __pycache__ folders
    python src/cleanup_data.py --dry-run        # preview only - delete nothing
"""

import os
import glob
import time
import shutil
import argparse

# Project root is the parent of this script's folder (src/).
PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(PROJECT_DIR, "data")

# Only files matching these (inside data/) are ever eligible for deletion.
PATTERNS = ["market_data_*.json", "snapshot_*.json", "*.json"]


def human(n):
    n = float(n)
    for unit in ["B", "KB", "MB", "GB"]:
        if n < 1024:
            return f"{n:.1f}{unit}"
        n /= 1024
    return f"{n:.1f}TB"


def within(path, base):
    """True only if `path` resolves to somewhere inside `base` (safety guard)."""
    try:
        return os.path.commonpath([os.path.abspath(path), base]) == base
    except ValueError:
        return False


def collect_data_files():
    files = set()
    for pat in PATTERNS:
        files.update(glob.glob(os.path.join(DATA_DIR, pat)))
    return [f for f in files if os.path.isfile(f) and within(f, DATA_DIR)]


def main():
    parser = argparse.ArgumentParser(description="Safely clean generated market data.")
    parser.add_argument("--keep-latest", type=int, default=None,
                        help="keep the N most recent files, delete the rest")
    parser.add_argument("--older-than", type=int, default=None,
                        help="delete files older than this many days")
    parser.add_argument("--pycache", action="store_true",
                        help="also remove __pycache__ folders (excludes .venv)")
    parser.add_argument("--dry-run", action="store_true",
                        help="show what would be deleted, delete nothing")
    args = parser.parse_args()

    if not os.path.isdir(DATA_DIR):
        print(f"No data/ directory at {DATA_DIR}; nothing to clean.")
        return

    files = collect_data_files()
    files.sort(key=os.path.getmtime, reverse=True)  # newest first

    to_delete = list(files)
    if args.keep_latest is not None:
        to_delete = files[args.keep_latest:]
    if args.older_than is not None:
        cutoff = time.time() - args.older_than * 86400
        to_delete = [f for f in to_delete if os.path.getmtime(f) < cutoff]

    pycache_dirs = []
    if args.pycache:
        for root, dirs, _ in os.walk(PROJECT_DIR):
            if ".venv" in root.split(os.sep):
                continue
            for d in dirs:
                if d == "__pycache__":
                    pycache_dirs.append(os.path.join(root, d))

    total_bytes = sum(os.path.getsize(f) for f in to_delete)
    print(f"data/ : {len(files)} file(s) found, {len(to_delete)} to delete ({human(total_bytes)}).")
    if pycache_dirs:
        print(f"__pycache__: {len(pycache_dirs)} folder(s) to delete.")

    if args.dry_run:
        for f in to_delete:
            print("  [dry-run] would delete", os.path.relpath(f, PROJECT_DIR))
        for d in pycache_dirs:
            print("  [dry-run] would delete", os.path.relpath(d, PROJECT_DIR))
        print("Dry run - nothing was deleted.")
        return

    deleted = 0
    for f in to_delete:
        if within(f, DATA_DIR):  # final guard before each delete
            try:
                os.remove(f)
                deleted += 1
            except OSError as e:
                print("  could not delete", f, e)

    for d in pycache_dirs:
        if within(d, PROJECT_DIR):
            try:
                shutil.rmtree(d)
            except OSError as e:
                print("  could not delete", d, e)

    print(f"Done. Deleted {deleted} data file(s), freed ~{human(total_bytes)}.")
    if pycache_dirs:
        print(f"Removed {len(pycache_dirs)} __pycache__ folder(s).")


if __name__ == "__main__":
    main()
