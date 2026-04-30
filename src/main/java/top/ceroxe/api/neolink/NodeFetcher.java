package top.ceroxe.api.neolink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fetches public NeoLink node definitions from NKM.
 *
 * <p>The returned {@link NeoNode} values preserve NKM display metadata such as
 * {@code name}, stable {@code realId}, optional SVG icon and connection ports.
 * Call {@link NeoNode#toCfg(String, int)} when a selected public node needs to become a
 * {@link NeoLinkCfg} tunnel configuration.</p>
 */
public final class NodeFetcher {
    public static final int DEFAULT_TIMEOUT_MILLIS = 1000;
    public static final int DEFAULT_HOST_HOOK_PORT = 44801;
    public static final int DEFAULT_HOST_CONNECT_PORT = 44802;

    private NodeFetcher() {
    }

    /**
     * Fetches NKM nodes with the same one-second timeout used by the desktop client.
     *
     * @param url NKM node-list endpoint
     * @return insertion-ordered map keyed by NKM {@code realId}
     * @throws IOException when the endpoint, HTTP status or JSON payload is invalid
     */
    public static Map<String, NeoNode> getFromNKM(String url) throws IOException {
        return getFromNKM(url, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Fetches NKM nodes keyed by stable {@code realId}.
     *
     * @param url NKM node-list endpoint
     * @param timeoutMillis connect and read timeout in milliseconds
     * @return insertion-ordered map keyed by NKM {@code realId}
     * @throws IllegalArgumentException when {@code url} is malformed or timeout is not positive
     * @throws IOException when the HTTP status or JSON payload is invalid
     */
    public static Map<String, NeoNode> getFromNKM(String url, int timeoutMillis) throws IOException {
        URI endpoint = parseEndpoint(url);
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be greater than 0.");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching NKM node list.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid NKM node-list URL: " + url, e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("NKM node-list request failed with HTTP status " + response.statusCode() + ".");
        }
        return parseNodeMap(response.body());
    }

    static Map<String, NeoNode> parseNodeMap(String json) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(Objects.requireNonNull(json, "json"));
        } catch (JsonParseException e) {
            throw new IOException("NKM node-list JSON is invalid.", e);
        }
        if (!root.isJsonArray()) {
            throw new IOException("NKM node-list root must be a JSON array.");
        }

        Map<String, NeoNode> result = new LinkedHashMap<>();
        for (JsonElement node : root.getAsJsonArray()) {
            if (!node.isJsonObject()) {
                throw new IOException("NKM node-list contains a non-object entry.");
            }

            JsonObject item = node.getAsJsonObject();
            String realId = readText(item, "realId", "realid");
            String name = readText(item, "name");
            String address = readText(item, "address");
            if (isBlank(realId) || isBlank(name) || isBlank(address)) {
                throw new IOException("NKM node-list entry must contain non-blank realId, name and address.");
            }

            String iconSvg = readText(item, "icon", "iconSvg");
            int hookPort = readPort(item, DEFAULT_HOST_HOOK_PORT, "HOST_HOOK_PORT", "hookPort");
            int connectPort = readPort(item, DEFAULT_HOST_CONNECT_PORT, "HOST_CONNECT_PORT", "connectPort");
            NeoNode parsedNode = new NeoNode(name, realId, address, iconSvg, hookPort, connectPort);
            if (result.put(realId, parsedNode) != null) {
                throw new IOException("NKM node-list contains duplicate realId: " + realId);
            }
        }
        return result;
    }

    private static URI parseEndpoint(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank.");
        }
        URI endpoint = URI.create(url.trim());
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("url must use http or https.");
        }
        return endpoint;
    }

    private static String readText(JsonObject item, String... aliases) {
        for (String alias : aliases) {
            JsonElement value = item.get(alias);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString().trim();
            }
        }
        return null;
    }

    private static int readPort(JsonObject item, int defaultValue, String... aliases) throws IOException {
        for (String alias : aliases) {
            JsonElement value = item.get(alias);
            if (value == null || value.isJsonNull()) {
                continue;
            }

            long port;
            if (!value.isJsonPrimitive()) {
                throw new IOException("Invalid port value type for " + alias + ".");
            }

            if (value.getAsJsonPrimitive().isNumber()) {
                port = parseIntegralPort(value.getAsString(), alias);
            } else if (value.getAsJsonPrimitive().isString()) {
                port = parseIntegralPort(value.getAsString(), alias);
            } else {
                throw new IOException("Invalid port value type for " + alias + ".");
            }

            if (port < 1 || port > 65535) {
                throw new IOException("Port out of range for " + alias + ": " + port);
            }
            return (int) port;
        }
        return defaultValue;
    }

    private static long parseIntegralPort(String rawValue, String fieldName) throws IOException {
        String normalized = rawValue.trim();
        if (!normalized.matches("[+-]?\\d+")) {
            throw new IOException("Invalid port value for " + fieldName + ": " + rawValue);
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid port value for " + fieldName + ": " + rawValue, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
