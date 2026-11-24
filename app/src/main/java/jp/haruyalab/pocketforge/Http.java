package jp.haruyalab.pocketforge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Small HTTPS-only helper. Redirects are deliberately never followed with credentials. */
public final class Http {
    private Http() {}
    public static final class Response {
        public final int status;
        public final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
    public static Response request(String method, String url, Map<String, String> headers,
                                   byte[] body) throws IOException {
        URL endpoint = new URL(url);
        if (!"https".equals(endpoint.getProtocol()) || endpoint.getUserInfo() != null)
            throw new IOException("HTTPS 接続が必要です。");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PocketForge/1.0 Android");
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet())
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (raw == null) return new Response(status, "");
            try (InputStream input = raw; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > 4 * 1024 * 1024) throw new IOException("応答サイズが上限を超えました。");
                    output.write(buffer, 0, n);
                }
                return new Response(status, output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    public static byte[] form(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
