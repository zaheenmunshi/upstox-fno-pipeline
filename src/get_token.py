"""Exchange an Upstox authorization code for an access token (non-interactive).

Usage:
    python src/get_token.py "<code-or-full-redirect-url>"

Reads credentials from .env and saves the token to .access_token.
This is the script for the "paste the redirect URL" workflow (auth.py is the
interactive equivalent you run in your own terminal).
"""

import os
import sys
import urllib.parse

from dotenv import load_dotenv
import upstox_client
from upstox_client.rest import ApiException

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
load_dotenv(os.path.join(PROJECT_ROOT, ".env"))
KEY = os.getenv("UPSTOX_API_KEY")
SECRET = os.getenv("UPSTOX_API_SECRET")
REDIRECT = os.getenv("UPSTOX_REDIRECT_URI")
API_VERSION = "2.0"
TOKEN_FILE = os.path.join(PROJECT_ROOT, ".access_token")


def extract_code(s):
    s = s.strip().strip('"').strip("'")
    if s.startswith("http"):
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(s).query)
        return (qs.get("code") or [None])[0]
    return s


def main():
    if len(sys.argv) < 2:
        print('Usage: python src/get_token.py "<code-or-redirect-url>"')
        sys.exit(1)

    code = extract_code(sys.argv[1])
    if not code:
        print("ERROR: could not extract a 'code' from the input.")
        sys.exit(1)

    try:
        resp = upstox_client.LoginApi().token(
            API_VERSION,
            code=code,
            client_id=KEY,
            client_secret=SECRET,
            redirect_uri=REDIRECT,
            grant_type="authorization_code",
        )
    except ApiException as e:
        print("TOKEN EXCHANGE FAILED")
        print(f"  Status: {e.status}  Reason: {e.reason}")
        print(f"  Body:   {e.body}")
        print("\n  UDAPI100058 => account segments inactive; reactivate in the Upstox app.")
        print("  'invalid'/credentials => check API secret in .env.")
        print("  code used/expired => log in again for a fresh code.")
        sys.exit(1)

    with open(TOKEN_FILE, "w", encoding="utf-8") as f:
        f.write(resp.access_token)

    print(f"SUCCESS: access token saved to {TOKEN_FILE}")
    print("Next: python src/market_snapshot.py   (then ask the fno-strategist agent for a trade)")


if __name__ == "__main__":
    main()
