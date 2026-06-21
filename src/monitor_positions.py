"""monitor_positions.py - watch OPEN trades against stop / target / time-stop using live LTP.

This is a MONITOR/ALERT tool only - it never places or modifies orders. You still
execute manually in your broker. It polls live LTP and prints an ALERT when a position
hits its stop, target, or time-stop.

Setup:
  1. Copy positions.example.json -> positions.json and fill in your open trades.
     (instrument_key must be the tradable contract key, e.g. an NSE_FO option key.)
  2. Ensure a valid .access_token exists.
  3. Run during market hours:
        python src/monitor_positions.py
     Ctrl+C to stop.
"""

import os
import sys
import json
import time
import datetime

import upstox_client
from upstox_client.rest import ApiException

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = os.path.join(PROJECT_ROOT, ".access_token")
POSITIONS_FILE = os.path.join(PROJECT_ROOT, "config", "positions.json")
POLL_SECONDS = 10
API_VERSION = "2.0"
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))


def load_token():
    if not os.path.exists(TOKEN_FILE):
        print(f"ERROR: {TOKEN_FILE} not found. Authenticate first.")
        sys.exit(1)
    with open(TOKEN_FILE, "r", encoding="utf-8") as f:
        t = f.read().strip()
    if not t:
        print(f"ERROR: {TOKEN_FILE} is empty.")
        sys.exit(1)
    return t


def load_positions():
    if not os.path.exists(POSITIONS_FILE):
        print(f"ERROR: {POSITIONS_FILE} not found.")
        print("Copy config/positions.example.json -> config/positions.json and fill in your trades.")
        sys.exit(1)
    with open(POSITIONS_FILE, "r", encoding="utf-8") as f:
        positions = json.load(f)
    if not isinstance(positions, list) or not positions:
        print(f"ERROR: {POSITIONS_FILE} must be a non-empty JSON list.")
        sys.exit(1)
    return positions


def get_ltps(client, keys):
    """Return {instrument_key: last_price}. Best-effort across SDK response shapes."""
    api = upstox_client.MarketQuoteV3Api(client)
    resp = api.get_ltp(",".join(keys), API_VERSION)
    out = {}
    data = getattr(resp, "data", {}) or {}
    # data is keyed by an exchange symbol; match back by instrument_token when present.
    for _, v in (data.items() if hasattr(data, "items") else []):
        price = getattr(v, "last_price", None)
        token = getattr(v, "instrument_token", None)
        if token is not None and price is not None:
            out[token] = price
    return out, data


def check(position, ltp):
    """Return an alert string if stop/target hit, else None."""
    side = position.get("side", "LONG").upper()
    stop = position.get("stop")
    target = position.get("target")
    if side == "LONG":
        if stop is not None and ltp <= stop:
            return f"STOP hit (LTP {ltp} <= stop {stop})"
        if target is not None and ltp >= target:
            return f"TARGET hit (LTP {ltp} >= target {target})"
    else:  # SHORT
        if stop is not None and ltp >= stop:
            return f"STOP hit (LTP {ltp} >= stop {stop})"
        if target is not None and ltp <= target:
            return f"TARGET hit (LTP {ltp} <= target {target})"
    return None


def time_stop_passed(position, now_ist):
    ts = position.get("time_stop")
    if not ts:
        return False
    try:
        hh, mm = [int(x) for x in ts.split(":")]
        return (now_ist.hour, now_ist.minute) >= (hh, mm)
    except ValueError:
        return False


def main():
    client = upstox_client.ApiClient(_config(load_token()))
    positions = load_positions()
    keys = [p["instrument_key"] for p in positions if p.get("instrument_key")]
    done = set()

    print(f"Monitoring {len(positions)} position(s), polling every {POLL_SECONDS}s. Ctrl+C to stop.\n")
    try:
        while len(done) < len(positions):
            now = datetime.datetime.now(IST)
            try:
                ltps, raw = get_ltps(client, keys)
            except ApiException as e:
                print(f"[{now:%H:%M:%S}] LTP fetch error HTTP {e.status}; retrying...")
                time.sleep(POLL_SECONDS)
                continue

            for idx, p in enumerate(positions):
                if idx in done:
                    continue
                label = p.get("label", p.get("instrument_key", f"pos{idx}"))
                # match LTP: try by instrument_token map, else skip if unavailable
                ltp = None
                tok = p.get("instrument_key")
                # ltps is keyed by instrument_token; many setups key raw by symbol -
                # fall back to scanning raw values for this contract is unreliable, so
                # we report if we cannot resolve.
                for v in (raw.values() if hasattr(raw, "values") else []):
                    if getattr(v, "instrument_token", None) == tok:
                        ltp = getattr(v, "last_price", None)
                        break
                if ltp is None and tok in ltps:
                    ltp = ltps[tok]

                if ltp is None:
                    print(f"[{now:%H:%M:%S}] {label}: LTP unavailable (check instrument_key).")
                    continue

                if time_stop_passed(p, now):
                    print(f"[{now:%H:%M:%S}] ** TIME-STOP ** {label}: exit now (LTP {ltp}).")
                    done.add(idx)
                    continue

                alert = check(p, ltp)
                if alert:
                    print(f"[{now:%H:%M:%S}] ** ALERT ** {label}: {alert} -> ACT NOW.")
                    done.add(idx)
                else:
                    print(f"[{now:%H:%M:%S}] {label}: LTP {ltp} (stop {p.get('stop')}, target {p.get('target')})")

            if len(done) < len(positions):
                time.sleep(POLL_SECONDS)
        print("\nAll positions hit an exit condition. Monitor done.")
    except KeyboardInterrupt:
        print("\nStopped by user.")


def _config(token):
    cfg = upstox_client.Configuration()
    cfg.access_token = token
    return cfg


if __name__ == "__main__":
    main()
