package com.zaheenmunshi.upstox;

import com.upstox.ApiClient;

/**
 * Builds an authenticated Upstox {@link ApiClient} from the daily access token.
 * Mirrors the Python {@code _config(token)} / {@code Configuration(); cfg.access_token = ...}
 * helper repeated across the scripts.
 */
public final class UpstoxClients {

    private UpstoxClients() {}

    /** A client carrying the OAuth2 access token, ready to pass to any *Api class. */
    public static ApiClient authenticated(String accessToken) {
        ApiClient client = new ApiClient();
        client.setAccessToken(accessToken);
        return client;
    }
}
