package com.zaheenmunshi.upstox;

import com.upstox.ApiException;
import com.upstox.api.TokenResponse;
import io.swagger.client.api.LoginApi;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Port of {@code auth.py}: interactive Upstox OAuth2 login. Run once per trading
 * day to obtain a fresh access token (Upstox tokens expire ~3:30 AM IST).
 *
 *   1. Prints (and tries to open) the Upstox authorization URL.
 *   2. Waits for you to paste the authorization code / redirect URL.
 *   3. Exchanges it for an access token and saves it to {@code .access_token}.
 */
public final class Auth {

    private Auth() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println(" Upstox OAuth2 Authentication");
        System.out.println("=".repeat(60));

        Config cfg = Config.load();
        List<String> missing = cfg.missingCredentials();
        if (!missing.isEmpty()) {
            System.out.println("ERROR: The following .env values are missing or still placeholders:");
            for (String m : missing) System.out.println("   - " + m);
            System.out.println("\nPlease edit the .env file and add your real Upstox credentials.");
            System.exit(1);
        }

        String authUrl = "https://api.upstox.com/v2/login/authorization/dialog"
                + "?response_type=code&client_id=" + cfg.apiKey()
                + "&redirect_uri=" + cfg.redirectUri();

        System.out.println("\nStep 1: Open this URL in your browser and log in to Upstox:\n");
        System.out.println(authUrl + "\n");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(authUrl));
                System.out.println("(Attempted to open it in your default browser automatically.)");
            } else {
                System.out.println("(Could not open the browser automatically - copy the URL above.)");
            }
        } catch (Exception e) {
            System.out.println("(Could not open the browser automatically - copy the URL above.)");
        }

        System.out.println("\nStep 2: After logging in you'll be redirected to a URL like:");
        System.out.println("   " + cfg.redirectUri() + "?code=SOME_LONG_CODE&...");
        System.out.println("\nStep 3: Copy the 'code' value (you can also paste the whole redirect URL).");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.print("\nPaste the authorization code (or full redirect URL) here: ");
        String userInput = in.readLine();
        if (userInput == null || userInput.strip().isEmpty()) {
            System.out.println("ERROR: No input provided. Exiting.");
            System.exit(1);
        }

        String code = GetToken.extractCode(userInput);
        if (code == null || code.isBlank()) {
            System.out.println("ERROR: Could not find a 'code' parameter in that input.");
            System.exit(1);
        }

        System.out.println("\nStep 4: Exchanging the code for an access token...");
        String accessToken = null;
        try {
            TokenResponse resp = new LoginApi().token(
                    Config.API_VERSION, code, cfg.apiKey(), cfg.apiSecret(),
                    cfg.redirectUri(), "authorization_code");
            accessToken = resp.getAccessToken();
        } catch (ApiException e) {
            System.out.println("\nERROR: Upstox rejected the token request.");
            System.out.println("   Status: " + e.getCode());
            System.out.println("   Body:   " + e.getResponseBody());
            System.out.println("\nCommon causes:");
            System.out.println("   - The authorization code was already used (codes are single-use).");
            System.out.println("   - The redirect_uri does not exactly match your Upstox app settings.");
            System.out.println("   - API key/secret are incorrect.");
            System.exit(1);
        }

        cfg.saveToken(accessToken);
        System.out.println("\n" + "=".repeat(60));
        System.out.println(" SUCCESS");
        System.out.println("=".repeat(60));
        System.out.println("Access token saved to: " + cfg.tokenFile());
        System.out.println("\nYou can now start streaming:  stream");
        System.out.println("\nReminder: Upstox tokens expire daily (~3:30 AM IST), so re-run");
        System.out.println("auth each trading day before streaming.");
    }
}
