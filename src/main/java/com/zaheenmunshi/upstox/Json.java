package com.zaheenmunshi.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared Jackson mapper for reading/writing the project's JSON files
 * ({@code data/snapshot_*.json}, {@code data/market_data_*.json}, positions).
 * Configured to tolerate the Upstox SDK's generated beans (some expose no
 * conventional properties) instead of aborting a whole write.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {}
}
