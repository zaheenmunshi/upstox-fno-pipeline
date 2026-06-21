"""Fetch a comprehensive market snapshot from Upstox REST APIs into data/.

Run AFTER a valid .access_token exists (auth.py or get_token.py):

    python src/market_snapshot.py

Pulls (best-effort - each section is independent, errors are captured not fatal):
  - Market status & exchange timings
  - Intraday (5m) + recent daily candles for the configured underlyings
  - Nearest-expiry option chain (OI / IV / greeks) for F&O underlyings
  - News headlines

Saves everything to data/snapshot_YYYYMMDD_HHMMSS.json for the analysis agents
(news-scanner, fno-strategist, market-analyst) to read.

NOTE: Historical candles work any time; option chain / live quotes are most
useful during or just after market hours.
"""

import os
import sys
import json
import datetime

import upstox_client
from upstox_client.rest import ApiException

# ============================================================
# CONFIG - edit underlyings / intervals here
# ============================================================
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = os.path.join(PROJECT_ROOT, ".access_token")
DATA_DIR = os.path.join(PROJECT_ROOT, "data")

# Underlyings to analyse (index F&O by default; add equities as needed).
UNDERLYINGS = {
    "NIFTY": "NSE_INDEX|Nifty 50",
    "BANKNIFTY": "NSE_INDEX|Nifty Bank",
    "SENSEX": "BSE_INDEX|SENSEX",
}

INTRADAY_UNIT = "minutes"
INTRADAY_INTERVAL = "5"
HIST_UNIT = "days"
HIST_INTERVAL = "1"
HIST_LOOKBACK_DAYS = 40
# ============================================================


def to_serializable(obj):
    if obj is None:
        return None
    if hasattr(obj, "to_dict"):
        try:
            return obj.to_dict()
        except Exception:
            pass
    return str(obj)


def nearest_expiry(expiries, today_str):
    """Nearest tradeable expiry as bare YYYY-MM-DD (API wants date only).

    Prefers the nearest expiry strictly AFTER today (the next live weekly/monthly);
    falls back to today's, then to the earliest available.
    """
    dates = sorted({str(e).split(" ")[0] for e in expiries if e})
    future = [d for d in dates if d > today_str]
    if future:
        return future[0]
    same_or_future = [d for d in dates if d >= today_str]
    return same_or_future[0] if same_or_future else (dates[0] if dates else None)


def load_token():
    if not os.path.exists(TOKEN_FILE):
        print(f"ERROR: {TOKEN_FILE} not found. Run auth.py or get_token.py first.")
        sys.exit(1)
    with open(TOKEN_FILE, "r", encoding="utf-8") as f:
        token = f.read().strip()
    if not token:
        print(f"ERROR: {TOKEN_FILE} is empty. Re-authenticate.")
        sys.exit(1)
    return token


def main():
    token = load_token()
    cfg = upstox_client.Configuration()
    cfg.access_token = token
    client = upstox_client.ApiClient(cfg)

    snap = {
        "_generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "underlyings": UNDERLYINGS,
        "sections": {},
        "errors": {},
    }

    def grab(name, fn):
        """Run a fetch, store result or error - never abort the whole snapshot."""
        try:
            snap["sections"][name] = to_serializable(fn())
            print(f"  [ok]  {name}")
        except ApiException as e:
            snap["errors"][name] = f"ApiException {e.status}: {e.body}"
            print(f"  [ERR] {name}: HTTP {e.status}")
        except Exception as e:  # noqa: BLE001
            snap["errors"][name] = f"{type(e).__name__}: {e}"
            print(f"  [ERR] {name}: {e}")

    print("== Market status ==")
    grab(
        "market_status_NSE",
        lambda: upstox_client.MarketHolidaysAndTimingsApi(client).get_market_status("NSE"),
    )

    today = datetime.date.today().isoformat()
    from_date = (datetime.date.today() - datetime.timedelta(days=HIST_LOOKBACK_DAYS)).isoformat()
    hist = upstox_client.HistoryV3Api(client)

    print("== Candles ==")
    for name, key in UNDERLYINGS.items():
        grab(
            f"intraday_{name}",
            lambda key=key: hist.get_intra_day_candle_data(key, INTRADAY_UNIT, INTRADAY_INTERVAL),
        )
        grab(
            f"historical_{name}",
            lambda key=key: hist.get_historical_candle_data1(
                key, HIST_UNIT, HIST_INTERVAL, today, from_date=from_date
            ),
        )

    print("== Option chains ==")
    opt = upstox_client.OptionsApi(client)
    for name, key in UNDERLYINGS.items():
        try:
            contracts = to_serializable(opt.get_option_contracts(key))
            snap["sections"][f"option_contracts_{name}"] = contracts
            # Best-effort: find the nearest expiry from the contracts payload.
            expiries = []
            data = contracts.get("data") if isinstance(contracts, dict) else None
            if isinstance(data, list):
                expiries = sorted(
                    {c.get("expiry") for c in data if isinstance(c, dict) and c.get("expiry")}
                )
            snap["sections"][f"expiries_{name}"] = expiries
            exp = nearest_expiry(expiries, today)
            snap["sections"][f"nearest_expiry_{name}"] = exp
            if exp:
                grab(
                    f"option_chain_{name}",
                    lambda key=key, exp=exp: opt.get_put_call_option_chain(key, exp),
                )
            else:
                snap["errors"][f"option_chain_{name}"] = "no expiry found in contracts"
            print(f"  [ok]  option_contracts_{name}")
        except Exception as e:  # noqa: BLE001
            snap["errors"][f"option_contracts_{name}"] = f"{type(e).__name__}: {e}"
            print(f"  [ERR] option_contracts_{name}: {e}")

    # News is sourced by the news-scanner agent (web search). The Upstox news API is
    # instrument-scoped (not a market-wide "category"), so it's intentionally omitted here.

    os.makedirs(DATA_DIR, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    path = os.path.join(DATA_DIR, f"snapshot_{ts}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(snap, f, indent=2, default=str)

    print(f"\nSaved snapshot -> {path}")
    print(f"Sections OK:    {list(snap['sections'].keys())}")
    if snap["errors"]:
        print(f"Sections w/err: {list(snap['errors'].keys())}")


if __name__ == "__main__":
    main()
