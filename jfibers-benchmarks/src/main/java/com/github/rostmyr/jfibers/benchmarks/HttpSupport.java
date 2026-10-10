package com.github.rostmyr.jfibers.benchmarks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Shared wire protocol and validation for all three HTTP clients. */
final class HttpSupport {
    static final byte[] PAYLOAD = new byte[1024];
    static final HttpResponse.BodyHandler<byte[]> BODY = HttpResponse.BodyHandlers.ofByteArray();

    static {
        for (int i = 0; i < PAYLOAD.length; i++) {
            PAYLOAD[i] = (byte) (i * 31 + 7);
        }
    }

    private HttpSupport() {
    }

    static int validate(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() != 200 || !Arrays.equals(PAYLOAD, response.body())) {
            throw new IOException("Invalid HTTP response: status=" + response.statusCode()
                + ", bytes=" + response.body().length);
        }
        return Arrays.hashCode(response.body());
    }

    static final class Api {
        final HttpClient client;
        final HttpRequest first;
        private final URI base;

        Api(HttpClient client, URI base) {
            this.client = client;
            this.base = base;
            first = request(base.resolve("/first"));
        }

        HttpRequest second(int token) {
            return request(base.resolve("/second?token=" + token));
        }

        private static HttpRequest request(URI uri) {
            return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        }

        CompletableFuture<HttpResponse<byte[]>> firstAsync() {
            return client.sendAsync(first, BODY);
        }

        CompletableFuture<HttpResponse<byte[]>> secondAsync(int token) {
            return client.sendAsync(second(token), BODY);
        }
    }

    // The harness has no JSON dependency. Only standard JSON value types are accepted.
    static String json(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (result.length() > 1) {
                    result.append(',');
                }
                result.append(json(entry.getKey().toString())).append(':').append(json(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable<?> items) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : items) {
                if (result.length() > 1) {
                    result.append(',');
                }
                result.append(json(item));
            }
            return result.append(']').toString();
        }
        if (value instanceof String string) {
            StringBuilder result = new StringBuilder("\"");
            for (char character : string.toCharArray()) {
                switch (character) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> {
                        if (character < 32) {
                            result.append(String.format("\\u%04x", (int) character));
                        } else {
                            result.append(character);
                        }
                    }
                }
            }
            return result.append('"').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }
}
