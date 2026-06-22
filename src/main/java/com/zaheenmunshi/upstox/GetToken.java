package com.zaheenmunshi.upstox;

import com.upstox.ApiException;
import com.upstox.api.TokenResponse;
import io.swagger.client.api.LoginApi;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Port of {@code get_token.py}: exchange an Upstox authorization code for an
 * access token (non-interactive). Reads credentials from {@code .env} and saves
 * the token to {@code .access_token}.
 *
 * <pre>java -jar upstox-fno-pipeline.jar get-token "&lt;code-or-full-redirect-url&gt;"</pre>
 */
public final class GetToken {

    private GetToken() {}

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: get-token \"<code-or-redirect-url>\"");
            System.exit(1);
        }

        Config cfg = Config.load();
        String code = extractCode(args[0]);
        if (code == null || code.isBlank()) {
            System.out.println("ERROR: could not extract a 'code' from the input.");
            System.exit(1);
        }

        TokenResponse resp;
        try {
            resp = new LoginApi().token(
                    Config.API_VERSION,
                    code,
                    cfg.apiKey(),
                    cfg.apiSecret(),
                    cfg.redirectUri(),
                    "authorization_code");
        } catch (ApiException e) {
            System.out.println("TOKEN EXCHANGE FAILED");
            System.out.println("  Status: " + e.getCode());
            System.out.println("  Body:   " + e.getResponseBody());
            System.out.println("\n  UDAPI100058 => account segments inactive; reactivate in the Upstox app.");
            System.out.println("  'invalid'/credentials => check API secret in .env.");
            System.out.println("  code used/expired => log in again for a fresh code.");
            System.exit(1);
            return;
        }

        cfg.saveToken(resp.getAccessToken());
        System.out.println("SUCCESS: access token saved to " + cfg.tokenFile());
        System.out.println("Next: snapshot   (then ask the fno-strategist agent for a trade)");
    }

    /** Accept either a raw code or the full redirect URL and extract the code. */
    static String extractCode(String s) {
        s = s.strip();
        if (s.startsWith("\"") || s.startsWith("'")) s = s.replaceAll("^[\"']|[\"']$", "");
        if (s.startsWith("http")) {
            String query = URI.create(s).getQuery();
            if (query == null) return null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("code")) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
        return s;
    }
}
