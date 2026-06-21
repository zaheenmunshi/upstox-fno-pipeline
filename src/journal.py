"""journal.py - record trades and compute real win-rate / expectancy / cost drag.

This is how you find out whether the system ACTUALLY works. Log every trade (paper or
real) and review the stats over 30+ trades before trusting the edge.

Usage:
  # add a closed trade
  python src/journal.py add --label "NIFTY 24500CE" --side LONG --entry 120 --exit 150 \
      --qty 75 --costs 60 --setup ema_crossover --notes "trend day"

  # show performance stats
  python src/journal.py stats

Data is stored in trade_journal.csv (gitignored).
"""

import os
import sys
import csv
import argparse

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JOURNAL_FILE = os.path.join(PROJECT_ROOT, "trade_journal.csv")
FIELDS = ["timestamp", "label", "side", "entry", "exit", "qty", "costs",
          "pnl", "setup", "notes"]


def add(args):
    import datetime
    entry, exit_, qty = float(args.entry), float(args.exit), float(args.qty)
    costs = float(args.costs)
    gross = (exit_ - entry) * qty if args.side.upper() == "LONG" else (entry - exit_) * qty
    pnl = gross - costs
    row = {
        "timestamp": datetime.datetime.now().isoformat(timespec="seconds"),
        "label": args.label, "side": args.side.upper(),
        "entry": entry, "exit": exit_, "qty": qty, "costs": costs,
        "pnl": round(pnl, 2), "setup": args.setup or "", "notes": args.notes or "",
    }
    exists = os.path.exists(JOURNAL_FILE)
    with open(JOURNAL_FILE, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        if not exists:
            w.writeheader()
        w.writerow(row)
    print(f"Logged: {row['label']} {row['side']} pnl={row['pnl']} (after costs {costs}).")


def stats(_args):
    if not os.path.exists(JOURNAL_FILE):
        print(f"No {JOURNAL_FILE} yet. Add trades first with: python src/journal.py add ...")
        return
    with open(JOURNAL_FILE, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        print("Journal is empty.")
        return

    pnls = [float(r["pnl"]) for r in rows]
    costs = sum(float(r["costs"]) for r in rows)
    n = len(pnls)
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p <= 0]
    total = sum(pnls)
    gross_win = sum(wins)
    gross_loss = -sum(losses)
    win_rate = 100.0 * len(wins) / n
    avg_win = gross_win / len(wins) if wins else 0.0
    avg_loss = gross_loss / len(losses) if losses else 0.0
    expectancy = total / n
    profit_factor = (gross_win / gross_loss) if gross_loss > 0 else float("inf")

    equity, peak, max_dd = 0.0, 0.0, 0.0
    for p in pnls:
        equity += p
        peak = max(peak, equity)
        max_dd = max(max_dd, peak - equity)

    print("=" * 52)
    print(" TRADE JOURNAL STATS")
    print("=" * 52)
    print(f" Trades:        {n}  (wins {len(wins)}, losses {len(losses)})")
    print(f" Win rate:      {win_rate:.1f}%")
    print(f" Avg win:       Rs {avg_win:.0f}    Avg loss: Rs {avg_loss:.0f}")
    print(f" Expectancy:    Rs {expectancy:.0f} per trade  (>0 = edge after costs)")
    print(f" Profit factor: {profit_factor:.2f}  (>1 = profitable)")
    print(f" Net P&L:       Rs {total:.0f}    Total costs paid: Rs {costs:.0f}")
    print(f" Max drawdown:  Rs {max_dd:.0f}")
    print("=" * 52)
    if n < 30:
        print(" NOTE: <30 trades - low confidence. Keep logging before trusting this.")
    if expectancy <= 0:
        print(" WARNING: expectancy <= 0 - this approach is NOT making money after costs.")


def main():
    p = argparse.ArgumentParser(description="Trade journal + performance stats.")
    sub = p.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("add", help="log a closed trade")
    a.add_argument("--label", required=True)
    a.add_argument("--side", required=True, choices=["LONG", "SHORT", "long", "short"])
    a.add_argument("--entry", required=True)
    a.add_argument("--exit", required=True)
    a.add_argument("--qty", required=True)
    a.add_argument("--costs", default="0")
    a.add_argument("--setup", default="")
    a.add_argument("--notes", default="")
    a.set_defaults(func=add)

    s = sub.add_parser("stats", help="show performance stats")
    s.set_defaults(func=stats)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
