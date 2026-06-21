"""
streamer.py - Upstox WebSocket V3 live market data streamer.

Connects to the Upstox V3 market-data feed, subscribes to the configured
instruments, and saves incoming messages to timestamped JSON files in data/.

Usage:
    python src/streamer.py

Requires a valid .access_token (produced by auth.py). Only returns data during
Indian market hours (9:00 AM - 3:30 PM IST, Mon-Fri). Press Ctrl+C to stop.
"""

import os
import sys
import json
import threading
from datetime import datetime, timezone

import upstox_client

# ============================================================
# CONFIGURATION - edit these to change what you stream
# ============================================================

# Instruments to subscribe to. Add more instrument keys to this list.
# Format examples:
#   "NSE_INDEX|Nifty 50"      (index)
#   "NSE_INDEX|Nifty Bank"    (index)
#   "NSE_EQ|INE002A01018"     (equity - Reliance Industries)
#   "NSE_EQ|INE467B01029"     (equity - TCS)
INSTRUMENT_KEYS = [
    "NSE_INDEX|Nifty 50",
    "NSE_INDEX|Nifty Bank",
]

# Subscription mode: "ltpc", "option_greeks", "full", or "full_d30"
MODE = "full"

# How often (seconds) to flush accumulated messages to disk.
SAVE_INTERVAL_SECONDS = 5

# Output directory and token file.
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(PROJECT_ROOT, "data")
TOKEN_FILE = os.path.join(PROJECT_ROOT, ".access_token")

# ============================================================
# Shared session state
# ============================================================
_buffer = []                      # messages received but not yet written
_buffer_lock = threading.Lock()
_total_messages = 0               # total messages received this session
_files_saved = 0                  # number of JSON files written
_last_saved_file = None           # path of most recently written file
_stop_event = threading.Event()   # set on shutdown to stop the saver thread


def load_access_token():
    """Read the access token saved by auth.py, exiting with guidance if absent."""
    if not os.path.exists(TOKEN_FILE):
        print(f"ERROR: '{TOKEN_FILE}' not found.")
        print("Please authenticate first by running:")
        print("    python src/auth.py")
        sys.exit(1)

    with open(TOKEN_FILE, "r", encoding="utf-8") as f:
        token = f.read().strip()

    if not token:
        print(f"ERROR: '{TOKEN_FILE}' is empty. Please run auth.py again:")
        print("    python src/auth.py")
        sys.exit(1)

    return token


def save_buffer():
    """Write any accumulated messages to a new timestamped JSON file.

    Each file contains a list of message records; an empty buffer is skipped
    so we never create empty files.
    """
    global _files_saved, _last_saved_file

    with _buffer_lock:
        if not _buffer:
            return
        messages = _buffer[:]
        _buffer.clear()

    os.makedirs(DATA_DIR, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = os.path.join(DATA_DIR, f"market_data_{timestamp}.json")

    # If several saves land in the same second, avoid overwriting.
    counter = 1
    while os.path.exists(filename):
        filename = os.path.join(DATA_DIR, f"market_data_{timestamp}_{counter}.json")
        counter += 1

    with open(filename, "w", encoding="utf-8") as f:
        json.dump(messages, f, indent=2, default=str)

    _files_saved += 1
    _last_saved_file = filename
    print(f"[SAVE] Wrote {len(messages)} message(s) -> {filename}")


def periodic_saver():
    """Background thread: flush the buffer every SAVE_INTERVAL_SECONDS."""
    while not _stop_event.is_set():
        # Wait the interval, but wake immediately if asked to stop.
        if _stop_event.wait(SAVE_INTERVAL_SECONDS):
            break
        save_buffer()
        print(
            f"[STATUS] Total messages: {_total_messages} | "
            f"Files saved: {_files_saved} | "
            f"Last file: {_last_saved_file or '(none yet)'}"
        )


# ============================================================
# WebSocket event handlers
# ============================================================

def on_open():
    print("[OPEN] Connected to Upstox WebSocket V3.")
    print(f"[OPEN] Subscribed to {len(INSTRUMENT_KEYS)} instrument(s) in '{MODE}' mode:")
    for key in INSTRUMENT_KEYS:
        print(f"        - {key}")
    print("\nReceiving live data... (press Ctrl+C to stop)\n")


def on_message(message):
    """Store an incoming message with an ISO-8601 receive timestamp."""
    global _total_messages
    record = {
        "_received_at": datetime.now(timezone.utc).isoformat(),
        "data": message,
    }
    with _buffer_lock:
        _buffer.append(record)
        _total_messages += 1


def on_error(error):
    print(f"[ERROR] {error}")


def on_close(close_status_code=None, close_msg=None):
    print(f"[CLOSE] WebSocket closed (code={close_status_code}, msg={close_msg}).")
    # Persist anything still buffered when the connection drops.
    save_buffer()


def print_summary():
    print("\n" + "=" * 60)
    print(" SESSION SUMMARY")
    print("=" * 60)
    print(f" Total messages received: {_total_messages}")
    print(f" Files saved:             {_files_saved}")
    print(f" Last file:               {_last_saved_file or '(none)'}")
    print(f" Data folder:             {os.path.abspath(DATA_DIR)}")
    print("=" * 60)


def main():
    print("=" * 60)
    print(" Upstox WebSocket V3 - Live Market Data Streamer")
    print("=" * 60)

    token = load_access_token()

    configuration = upstox_client.Configuration()
    configuration.access_token = token

    streamer = upstox_client.MarketDataStreamerV3(
        upstox_client.ApiClient(configuration),
        INSTRUMENT_KEYS,
        MODE,
    )

    streamer.on("open", on_open)
    streamer.on("message", on_message)
    streamer.on("error", on_error)
    streamer.on("close", on_close)

    # Start the background saver before connecting.
    saver_thread = threading.Thread(target=periodic_saver, daemon=True)
    saver_thread.start()

    print("Connecting...\n")
    try:
        # connect() runs the websocket loop. Depending on SDK version it may
        # block here, or return and run in the background - handle both.
        streamer.connect()
        while not _stop_event.is_set():
            _stop_event.wait(1)
    except KeyboardInterrupt:
        print("\n[CTRL+C] Stopping, saving any unsaved data...")
    finally:
        _stop_event.set()
        save_buffer()          # final flush of anything left in the buffer
        try:
            streamer.disconnect()
        except Exception:
            pass
        print_summary()


if __name__ == "__main__":
    main()
