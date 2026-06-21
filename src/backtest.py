"""backtest.py - validate a directional setup on historical Upstox candles.

HONEST SCOPE: this backtests the UNDERLYING directional signal (the basis for an F&O
directional view), NOT option P&L. Real option P&L depends on strike, IV, theta and
path, so treat these results as evidence the *signal* has (or lacks) an edge - then
apply option structure + costs separately. PAST PERFORMANCE != FUTURE RESULTS.

Needs a valid .access_token (auth.py / get_token.py).

Examples:
    python src/backtest.py --instrument "NSE_INDEX|Nifty 50" --unit minutes --interval 5 \
        --strategy ema_crossover --fast 9 --slow 20 --stop-atr 1.5 --target-atr 3 \
        --lookback-days 30 --cost-pct 0.05

    python src/backtest.py --instrument "NSE_INDEX|Nifty Bank" --unit days --interval 1 \
        --strategy ema_crossover --lookback-days 365
"""

import os
import sys
import argparse
import datetime

import upstox_client
from upstox_client.rest import ApiException

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = os.path.join(PROJECT_ROOT, ".access_token")


def load_token():
    if not os.path.exists(TOKEN_FILE):
        print(f"ERROR: {TOKEN_FILE} not found. Run auth.py or get_token.py first.")
        sys.exit(1)
    with open(TOKEN_FILE, "r", encoding="utf-8") as f:
        t = f.read().strip()
    if not t:
        print(f"ERROR: {TOKEN_FILE} is empty.")
        sys.exit(1)
    return t


def fetch_candles(client, instrument, unit, interval, lookback_days):
    """Return candles oldest-first as list of dicts: ts,o,h,l,c,v."""
    to_date = datetime.date.today().isoformat()
    from_date = (datetime.date.today() - datetime.timedelta(days=lookback_days)).isoformat()
    api = upstox_client.HistoryV3Api(client)
    resp = api.get_historical_candle_data1(instrument, unit, interval, to_date, from_date=from_date)
    raw = []
    data = getattr(resp, "data", None)
    candles = getattr(data, "candles", None) if data is not None else None
    if not candles:
        return []
    for c in candles:
        # Upstox candle: [timestamp, open, high, low, close, volume, oi]
        try:
            raw.append({
                "ts": c[0],
                "o": float(c[1]), "h": float(c[2]), "l": float(c[3]),
                "c": float(c[4]), "v": float(c[5]) if len(c) > 5 else 0.0,
            })
        except (IndexError, TypeError, ValueError):
            continue
    raw.sort(key=lambda x: x["ts"])  # oldest first
    return raw


def ema_series(values, period):
    if not values:
        return []
    k = 2.0 / (period + 1)
    out = [values[0]]
    for v in values[1:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


def atr_series(candles, period=14):
    """Wilder-ish ATR; returns a list aligned to candles (first value at index 0)."""
    trs = []
    prev_close = candles[0]["c"]
    for c in candles:
        tr = max(c["h"] - c["l"], abs(c["h"] - prev_close), abs(c["l"] - prev_close))
        trs.append(tr)
        prev_close = c["c"]
    # simple rolling mean of TR as ATR proxy
    atr = []
    for i in range(len(trs)):
        window = trs[max(0, i - period + 1): i + 1]
        atr.append(sum(window) / len(window))
    return atr


def backtest_ema_crossover(candles, fast, slow, stop_atr, target_atr, cost_pct):
    closes = [c["c"] for c in candles]
    ef = ema_series(closes, fast)
    es = ema_series(closes, slow)
    atr = atr_series(candles)

    trades = []
    pos = None  # dict: side, entry, stop, target, entry_i
    for i in range(1, len(candles)):
        c = candles[i]
        crossed_up = ef[i] > es[i] and ef[i - 1] <= es[i - 1]
        crossed_dn = ef[i] < es[i] and ef[i - 1] >= es[i - 1]

        if pos is None:
            if crossed_up or crossed_dn:
                side = "LONG" if crossed_up else "SHORT"
                entry = c["c"]
                a = atr[i] or (entry * 0.001)
                if side == "LONG":
                    stop, target = entry - stop_atr * a, entry + target_atr * a
                else:
                    stop, target = entry + stop_atr * a, entry - target_atr * a
                pos = {"side": side, "entry": entry, "stop": stop, "target": target, "i": i}
            continue

        # in a position: check exits on this candle (stop before target = conservative)
        exit_price = None
        if pos["side"] == "LONG":
            if c["l"] <= pos["stop"]:
                exit_price = pos["stop"]
            elif c["h"] >= pos["target"]:
                exit_price = pos["target"]
            elif crossed_dn:
                exit_price = c["c"]
        else:
            if c["h"] >= pos["stop"]:
                exit_price = pos["stop"]
            elif c["l"] <= pos["target"]:
                exit_price = pos["target"]
            elif crossed_up:
                exit_price = c["c"]

        if exit_price is not None:
            gross = (exit_price - pos["entry"]) if pos["side"] == "LONG" else (pos["entry"] - exit_price)
            cost = (pos["entry"] + exit_price) * (cost_pct / 100.0)
            trades.append({"side": pos["side"], "entry": pos["entry"],
                           "exit": exit_price, "pnl": gross - cost})
            pos = None
    return trades


def report(trades, label):
    print("\n" + "=" * 56)
    print(f" BACKTEST RESULT - {label}")
    print("=" * 56)
    n = len(trades)
    if n == 0:
        print(" No trades generated. Try a longer lookback or different params.")
        return
    wins = [t for t in trades if t["pnl"] > 0]
    losses = [t for t in trades if t["pnl"] <= 0]
    total = sum(t["pnl"] for t in trades)
    gross_win = sum(t["pnl"] for t in wins)
    gross_loss = -sum(t["pnl"] for t in losses)
    win_rate = 100.0 * len(wins) / n
    avg_win = (gross_win / len(wins)) if wins else 0.0
    avg_loss = (gross_loss / len(losses)) if losses else 0.0
    expectancy = total / n
    profit_factor = (gross_win / gross_loss) if gross_loss > 0 else float("inf")

    # max drawdown on the equity curve (in points)
    equity, peak, max_dd = 0.0, 0.0, 0.0
    for t in trades:
        equity += t["pnl"]
        peak = max(peak, equity)
        max_dd = max(max_dd, peak - equity)

    print(f" Trades:        {n}   (wins {len(wins)}, losses {len(losses)})")
    print(f" Win rate:      {win_rate:.1f}%")
    print(f" Avg win:       {avg_win:.2f} pts   Avg loss: {avg_loss:.2f} pts")
    print(f" Expectancy:    {expectancy:.2f} pts/trade   (>0 = positive edge, net of costs)")
    print(f" Profit factor: {profit_factor:.2f}   (>1 = profitable)")
    print(f" Total:         {total:.2f} pts   Max drawdown: {max_dd:.2f} pts")
    print("=" * 56)
    print(" NOTE: underlying-signal backtest only - not option P&L. Past != future.")


def main():
    p = argparse.ArgumentParser(description="Backtest a directional setup on Upstox candles.")
    p.add_argument("--instrument", required=True, help='e.g. "NSE_INDEX|Nifty 50"')
    p.add_argument("--unit", default="minutes", help="minutes/hours/days/weeks/months")
    p.add_argument("--interval", default="5")
    p.add_argument("--strategy", default="ema_crossover", choices=["ema_crossover"])
    p.add_argument("--fast", type=int, default=9)
    p.add_argument("--slow", type=int, default=20)
    p.add_argument("--stop-atr", type=float, default=1.5)
    p.add_argument("--target-atr", type=float, default=3.0)
    p.add_argument("--lookback-days", type=int, default=30)
    p.add_argument("--cost-pct", type=float, default=0.05, help="round-trip cost as %% of price")
    args = p.parse_args()

    client = upstox_client.ApiClient(_config(load_token()))
    try:
        candles = fetch_candles(client, args.instrument, args.unit, args.interval, args.lookback_days)
    except ApiException as e:
        print(f"ERROR fetching candles: HTTP {e.status}: {e.body}")
        sys.exit(1)

    if len(candles) < max(args.slow + 5, 20):
        print(f"Only {len(candles)} candles fetched - not enough to backtest. Increase --lookback-days.")
        sys.exit(1)

    trades = backtest_ema_crossover(candles, args.fast, args.slow, args.stop_atr,
                                    args.target_atr, args.cost_pct)
    label = f"{args.instrument} {args.interval}{args.unit} EMA{args.fast}/{args.slow} " \
            f"SL{args.stop_atr}xATR TP{args.target_atr}xATR ({len(candles)} candles)"
    report(trades, label)


def _config(token):
    cfg = upstox_client.Configuration()
    cfg.access_token = token
    return cfg


if __name__ == "__main__":
    main()
