"""
auth.py - Upstox OAuth2 authentication.

Run this once per trading day to obtain a fresh access token:

    python src/auth.py

What it does:
  1. Prints (and tries to open) the Upstox authorization URL.
  2. Waits for you to paste the authorization code from the redirect URL.
  3. Exchanges the code for an access token via the Upstox SDK.
  4. Saves the token to .access_token in the project root.

Note: Upstox access tokens expire every day (around 3:30 AM IST), so this
must be re-run each trading day before streaming.
"""

import os
import sys
import webbrowser
from urllib.parse import urlparse, parse_qs

from dotenv import load_dotenv
import upstox_client
from upstox_client.rest import ApiException

# --- Configuration ---
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = os.path.join(PROJECT_ROOT, ".access_token")
API_VERSION = "2.0"


def load_credentials():
    """Load and validate Upstox credentials from the .env file."""
    load_dotenv(os.path.join(PROJECT_ROOT, ".env"))
    api_key = os.getenv("UPSTOX_API_KEY")
    api_secret = os.getenv("UPSTOX_API_SECRET")
    redirect_uri = os.getenv("UPSTOX_REDIRECT_URI")

    # Flag anything missing or left as the original placeholder text.
    missing = [
        name
        for name, val in [
            ("UPSTOX_API_KEY", api_key),
            ("UPSTOX_API_SECRET", api_secret),
            ("UPSTOX_REDIRECT_URI", redirect_uri),
        ]
        if not val or val.startswith("paste_")
    ]

    if missing:
        print("ERROR: The following .env values are missing or still placeholders:")
        for m in missing:
            print(f"   - {m}")
        print("\nPlease edit the .env file and add your real Upstox credentials.")
        sys.exit(1)

    return api_key, api_secret, redirect_uri


def build_auth_url(api_key, redirect_uri):
    """Build the Upstox authorization dialog URL."""
    return (
        "https://api.upstox.com/v2/login/authorization/dialog"
        f"?response_type=code&client_id={api_key}&redirect_uri={redirect_uri}"
    )


def extract_code(user_input):
    """Accept either a raw code or the full redirect URL and extract the code."""
    user_input = user_input.strip()
    if user_input.startswith("http"):
        qs = parse_qs(urlparse(user_input).query)
        codes = qs.get("code")
        if codes:
            return codes[0]
        print("ERROR: Could not find a 'code' parameter in that URL.")
        return None
    return user_input


def exchange_code_for_token(code, api_key, api_secret, redirect_uri):
    """Exchange the authorization code for an access token."""
    api = upstox_client.LoginApi()
    try:
        response = api.token(
            API_VERSION,
            code=code,
            client_id=api_key,
            client_secret=api_secret,
            redirect_uri=redirect_uri,
            grant_type="authorization_code",
        )
        return response.access_token
    except ApiException as e:
        print("\nERROR: Upstox rejected the token request.")
        print(f"   Status: {e.status}")
        print(f"   Reason: {e.reason}")
        print(f"   Body:   {e.body}")
        print("\nCommon causes:")
        print("   - The authorization code was already used (codes are single-use).")
        print("   - The redirect_uri does not exactly match your Upstox app settings.")
        print("   - API key/secret are incorrect.")
        return None
    except Exception as e:  # noqa: BLE001 - surface anything unexpected to the user
        print(f"\nERROR: Unexpected problem during token exchange: {e}")
        return None


def main():
    print("=" * 60)
    print(" Upstox OAuth2 Authentication")
    print("=" * 60)

    api_key, api_secret, redirect_uri = load_credentials()

    # Step 1: authorization URL
    auth_url = build_auth_url(api_key, redirect_uri)
    print("\nStep 1: Open this URL in your browser and log in to Upstox:\n")
    print(auth_url)
    print()
    try:
        if webbrowser.open(auth_url):
            print("(Attempted to open it in your default browser automatically.)")
        else:
            print("(Could not open the browser automatically - copy the URL above.)")
    except Exception:
        print("(Could not open the browser automatically - copy the URL above.)")

    # Step 2 & 3: collect the code
    print("\nStep 2: After logging in you'll be redirected to a URL like:")
    print(f"   {redirect_uri}?code=SOME_LONG_CODE&...")
    print("\nStep 3: Copy the 'code' value (you can also paste the whole redirect URL).")

    user_input = input(
        "\nPaste the authorization code (or full redirect URL) here: "
    ).strip()
    if not user_input:
        print("ERROR: No input provided. Exiting.")
        sys.exit(1)

    code = extract_code(user_input)
    if not code:
        sys.exit(1)

    # Step 4: exchange and save
    print("\nStep 4: Exchanging the code for an access token...")
    access_token = exchange_code_for_token(code, api_key, api_secret, redirect_uri)
    if not access_token:
        sys.exit(1)

    with open(TOKEN_FILE, "w", encoding="utf-8") as f:
        f.write(access_token)

    print("\n" + "=" * 60)
    print(" SUCCESS")
    print("=" * 60)
    print(f"Access token saved to: {TOKEN_FILE}")
    print("\nYou can now start streaming:  python src/streamer.py")
    print(
        "\nReminder: Upstox tokens expire daily (~3:30 AM IST), so re-run\n"
        "auth.py each trading day before streaming."
    )


if __name__ == "__main__":
    main()
