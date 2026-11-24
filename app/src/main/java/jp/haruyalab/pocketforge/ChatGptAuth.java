package jp.haruyalab.pocketforge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Public-client Sign in with ChatGPT. Never uses a client secret or an API key. */
public final class ChatGptAuth {
    public interface Callback {
        void onSuccess(JSONObject profile);
        void onError(String message);
    }
    private static final String ISSUER = "https://auth.openai.com";
    private static final String DISCOVERY = ISSUER + "/.well-known/openid-configuration";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String DYNAMIC = "dynamic_agent_client";
    private static final long LOGIN_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final Object STORE_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecureStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lifecycleLock = new Object();
    private volatile Attempt pending;
    private volatile JSONObject metadata;
    private volatile JSONArray jwks;
    private volatile long jwksFetchedAt;

    private static final class Attempt {
        final String clientId, subject, hostId, state = random(), nonce = random(), verifier = random();
        final JSONObject previous;
        final Callback callback;
        final boolean consent;
        int exchangeRetries;
        boolean completed;
        volatile boolean cancelled;
        volatile ServerSocket server;
        Attempt(String clientId, JSONObject previous, String hostId, boolean consent, Callback callback) {
            this.clientId = clientId; this.previous = previous; this.hostId = hostId;
            this.subject = previous == null ? null : previous.optString("subject", null);
            this.consent = consent; this.callback = callback;
        }
    }

    public ChatGptAuth(Context context) { store = new SecureStore(context); }

    /** Returns only display fields. Saved registrations remain after sign-out. */
    public JSONObject getProfile() {
        synchronized (STORE_LOCK) {
            try { JSONObject vault = vault(); return sanitize(selected(vault), vault.optString("selected")); }
            catch (Exception error) { return null; }
        }
    }
    public JSONArray getProfiles() {
        synchronized (STORE_LOCK) {
            JSONArray result = new JSONArray();
            try {
                JSONObject vault = vault(); JSONArray accounts = vault.getJSONArray("accounts");
                for (int i = 0; i < accounts.length(); i++)
                    result.put(sanitize(accounts.getJSONObject(i), vault.optString("selected")));
            } catch (Exception ignored) { }
            return result;
        }
    }
    public boolean isSignedIn() {
        JSONObject profile = getProfile();
        return profile != null && profile.optBoolean("signed_in");
    }
    public void selectProfile(String clientId) throws Exception {
        cancelLogin();
        synchronized (STORE_LOCK) {
            JSONObject vault = vault();
            if (find(vault, clientId) == null) throw new IOException("保存されたアカウントが見つかりません。");
            vault.put("selected", clientId); store.write(vault);
        }
    }
    public void startLogin(Activity activity, Callback callback) {
        JSONObject profile = getProfile();
        startLogin(activity, profile == null ? null : profile.optString("client_id", null), false, callback);
    }
    /** null clientId registers another account/workspace; consent is only for explicit permission requests. */
    public void startLogin(Activity activity, String clientId, boolean requestPlanConsent, Callback callback) {
        cancelLogin();
        final Attempt attempt;
        try {
            synchronized (STORE_LOCK) {
                JSONObject vault = vault(); JSONObject previous = clientId == null ? null : find(vault, clientId);
                if (clientId != null && previous == null) throw new IOException("アカウントが見つかりません。");
                attempt = new Attempt(clientId, previous == null ? null : new JSONObject(previous.toString()),
                        vault.getString("host_id"), requestPlanConsent, callback);
            }
        } catch (Exception error) { main.post(() -> callback.onError("認証情報を読み込めません。端末の安全な保存領域を確認してください。")); return; }
        synchronized (lifecycleLock) { pending = attempt; }
        Thread thread = new Thread(() -> runLogin(activity, attempt), "PocketForge-OAuth");
        thread.setDaemon(true); thread.start();
    }
    public void cancelLogin() {
        Attempt attempt;
        synchronized (lifecycleLock) {
            attempt = pending; pending = null;
            if (attempt == null || attempt.completed) return;
            attempt.cancelled = true; attempt.completed = true;
        }
        if (attempt.server != null) try { attempt.server.close(); } catch (IOException ignored) { }
        main.post(() -> attempt.callback.onError("ログインを中止しました。"));
    }

    private void runLogin(Activity activity, Attempt attempt) {
        try {
            JSONObject config = discovery();
            if (attempt.cancelled) return;
            ServerSocket server = new ServerSocket();
            attempt.server = server;
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            server.setSoTimeout(1000);
            String redirect = "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
            parameters.put("client_id", attempt.clientId == null ? DYNAMIC : attempt.clientId);
            if (attempt.clientId == null) parameters.put("agent_name_hint", "PocketForge");
            parameters.put("ext_agent_host_id", attempt.hostId);
            parameters.put("response_type", "code"); parameters.put("redirect_uri", redirect);
            parameters.put("scope", SCOPES); parameters.put("resource", RESOURCE);
            parameters.put("state", attempt.state); parameters.put("nonce", attempt.nonce);
            parameters.put("code_challenge_method", "S256"); parameters.put("code_challenge", pkce(attempt.verifier));
            if (attempt.consent) parameters.put("prompt", "consent");
            if (attempt.previous != null) {
                String hint = attempt.previous.optString("id_token");
                if (!hint.isEmpty()) parameters.put("id_token_hint", hint);
                String email = attempt.previous.optString("email");
                if (!email.isEmpty()) parameters.put("login_hint", email);
            }
            String authorization = trustedEndpoint(config.getString("authorization_endpoint")) + "?" +
                    new String(Http.form(parameters), StandardCharsets.UTF_8);
            main.post(() -> {
                if (attempt.cancelled) return;
                try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authorization)).addCategory(Intent.CATEGORY_BROWSABLE)); }
                catch (Exception error) { fail(attempt, "ブラウザを開けません。ブラウザをインストールしてください。"); }
            });
            long deadline = android.os.SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS;
            while (!attempt.cancelled && android.os.SystemClock.elapsedRealtime() < deadline) {
                Socket accepted;
                try { accepted = server.accept(); }
                catch (SocketTimeoutException timeout) { continue; }
                try (Socket socket = accepted) {
                    socket.setSoTimeout(3000);
                    Map<String, String> query;
                    try {
                        String first = readRequest(socket.getInputStream());
                        String[] pieces = first.split(" ");
                        if (pieces.length != 3 || !"GET".equals(pieces[0])) { browserReply(socket, 400, "無効なリクエストです。"); continue; }
                        URI uri = new URI(pieces[1]);
                        if (uri.isAbsolute() || !"/auth/callback".equals(uri.getPath())) { browserReply(socket, 404, "ページが見つかりません。"); continue; }
                        query = parseQuery(uri.getRawQuery());
                        if (!same(attempt.state, query.get("state"))) { browserReply(socket, 400, "認証状態が一致しません。アプリからやり直してください。"); continue; }
                    } catch (Exception malformed) { browserReply(socket, 400, "無効なコールバックです。"); continue; }
                    if (query.containsKey("error")) {
                        browserReply(socket, 200, "ログインを中止しました。PocketForge に戻ってください。");
                        throw new IOException("access_denied".equals(query.get("error")) ? "ログインまたは権限の許可がキャンセルされました。" : "ChatGPT の認証を完了できませんでした。もう一度ログインしてください。");
                    }
                    String issued = query.get("client_id");
                    if (attempt.clientId != null) {
                        if (issued != null && !attempt.clientId.equals(issued)) throw new IOException("アカウントの登録 ID が一致しません。");
                        issued = attempt.clientId;
                    }
                    if (issued == null || issued.isEmpty() || DYNAMIC.equals(issued) || issued.length() > 512)
                        throw new IOException("登録 ID が返されませんでした。再度ログインしてください。");
                    String code = query.get("code");
                    if (code == null || code.isEmpty() || code.length() > 8192) throw new IOException("認証コードがありません。");
                    // Remove the sensitive code from the visible URL before performing network work.
                    browserReply(socket, 200, "認証結果を確認しています。PocketForge に戻ってください。");
                    LinkedHashMap<String, String> grant = new LinkedHashMap<>();
                    grant.put("grant_type", "authorization_code"); grant.put("client_id", issued);
                    grant.put("code", code); grant.put("code_verifier", attempt.verifier);
                    grant.put("redirect_uri", redirect); grant.put("resource", RESOURCE);
                    JSONObject tokens;
                    try { tokens = tokenRequest(grant); }
                    catch (OAuthException rejected) {
                        // A code is single-use. Keep its newly issued registration and retry once
                        // with entirely fresh state/nonce/PKCE instead of registering another client.
                        if ("invalid_grant".equals(rejected.code) && attempt.exchangeRetries == 0 && !attempt.cancelled) {
                            Attempt retry = new Attempt(issued, attempt.previous, attempt.hostId, attempt.consent, attempt.callback);
                            retry.exchangeRetries = 1;
                            synchronized (lifecycleLock) {
                                if (attempt.cancelled || pending != attempt) return;
                                attempt.cancelled = true; attempt.completed = true; pending = retry;
                            }
                            server.close();
                            runLogin(activity, retry); return;
                        }
                        throw rejected;
                    }
                    JSONObject identity = validateIdToken(tokens.getString("id_token"), issued, attempt.nonce, null);
                    if (attempt.subject != null && !attempt.subject.equals(identity.getString("sub")))
                        throw new IOException("選択したアカウントとログインしたアカウントが一致しません。");
                    if (attempt.cancelled) return;
                    JSONObject profile;
                    synchronized (STORE_LOCK) {
                        if (attempt.cancelled) return;
                        JSONObject vault = vault(); JSONObject account = find(vault, issued);
                        if (account != null && !identity.getString("sub").equals(account.optString("subject")))
                            throw new IOException("保存済みアカウントの識別情報が一致しません。");
                        if (account == null) {
                            account = new JSONObject().put("client_id", issued)
                                    .put("label", "アカウント " + (vault.getJSONArray("accounts").length() + 1));
                            vault.getJSONArray("accounts").put(account);
                        }
                        replaceTokens(account, tokens, identity, false);
                        account.put("oidc_nonce", attempt.nonce);
                        vault.put("selected", issued); store.write(vault);
                        profile = sanitize(account, issued);
                    }
                    succeed(attempt, profile); return;
                }
            }
            if (!attempt.cancelled) throw new IOException("ログインの待機時間が終了しました。もう一度実行してください。");
        } catch (Exception error) {
            if (!attempt.cancelled) fail(attempt, safeError(error, "認証を完了できませんでした。通信環境と端末の時刻を確認してください。"));
        } finally {
            if (attempt.server != null) try { attempt.server.close(); } catch (IOException ignored) { }
            synchronized (lifecycleLock) { if (pending == attempt) pending = null; }
        }
    }

    /** Blocking; call from a worker. Refresh rotation and storage are serialized process-wide. */
    public String getAccessToken() throws Exception { return accessToken(false); }
    public void forceRefresh() throws Exception { accessToken(true); }
    private String accessToken(boolean force) throws Exception {
        synchronized (STORE_LOCK) {
            JSONObject vault = vault(); JSONObject account = selected(vault);
            if (account == null || account.optString("access_token").isEmpty()) throw new IOException("ChatGPT にログインしてください。");
            if (!hasPlan(account.optString("scope"))) throw new IOException("ChatGPT プランの使用が許可されていません。アカウント設定から使用を許可してください。");
            long now = System.currentTimeMillis() / 1000L;
            long expires = account.optLong("expires_at", 0);
            long earliest = account.optLong("earliest_refresh_at", 0);
            if ((!force && now < expires - 60) || (now < earliest && now < expires)) return account.getString("access_token");
            if (account.optString("refresh_token").isEmpty()) throw new IOException("セッションを更新できません。再度ログインしてください。");
            LinkedHashMap<String, String> grant = new LinkedHashMap<>();
            grant.put("grant_type", "refresh_token"); grant.put("client_id", account.getString("client_id"));
            grant.put("refresh_token", account.getString("refresh_token")); grant.put("resource", RESOURCE);
            JSONObject tokens;
            try { tokens = tokenRequest(grant); }
            catch (OAuthException error) {
                if (new HashSet<>(Arrays.asList("invalid_grant", "invalid_refresh_token", "token_expired",
                        "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")).contains(error.code)) {
                    clearTokens(account); store.write(vault);
                    throw new IOException("ChatGPT セッションが終了しました。保存されたアカウントで再度ログインしてください。");
                }
                throw error;
            }
            JSONObject identity = null;
            if (!tokens.optString("id_token").isEmpty())
                identity = validateIdToken(tokens.getString("id_token"), account.getString("client_id"), null, account.getString("subject"));
            if (identity != null && identity.has("nonce") && !same(account.optString("oidc_nonce", null), identity.optString("nonce", null)))
                throw new IOException("更新された認証情報の nonce が一致しません。再度ログインしてください。");
            replaceTokens(account, tokens, identity, true);
            store.write(vault);
            if (!hasPlan(account.optString("scope"))) throw new IOException("ChatGPT プランの使用権限がありません。使用を再度許可してください。");
            return account.getString("access_token");
        }
    }

    /** Stop inference before calling. Returns a warning only if remote revocation was unconfirmed. */
    public String logout() {
        cancelLogin();
        synchronized (STORE_LOCK) {
            try {
                JSONObject vault = vault(); JSONObject account = selected(vault);
                if (account == null) return null;
                String token = account.optString("refresh_token"); boolean revoked = token.isEmpty();
                for (int attempt = 0; !revoked && attempt < 3; attempt++) {
                    try {
                        String endpoint = trustedEndpoint(discovery().getString("revocation_endpoint"));
                        Map<String, String> form = new LinkedHashMap<>();
                        form.put("token", token); form.put("token_type_hint", "refresh_token");
                        form.put("client_id", account.getString("client_id"));
                        Http.Response response = Http.request("POST", endpoint,
                                Collections.singletonMap("Content-Type", "application/x-www-form-urlencoded"), Http.form(form));
                        revoked = response.status == 200;
                        if (!revoked && response.status < 500) break;
                    } catch (Exception temporary) { /* Keep the token until bounded retries finish. */ }
                    if (!revoked && attempt < 2) try { Thread.sleep(500L << attempt); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
                clearTokens(account); store.write(vault);
                return revoked ? null : "端末からログアウトしました。サーバー側の失効は確認できませんでした。ChatGPT の設定で PocketForge の接続を解除してください。";
            } catch (Exception error) { return "認証情報の削除に失敗しました。アプリを再起動して再試行してください。"; }
        }
    }

    private JSONObject vault() throws Exception {
        JSONObject vault = store.read();
        boolean changed = false;
        if (!vault.has("host_id")) { vault.put("host_id", "urn:uuid:" + UUID.randomUUID()); changed = true; }
        if (!vault.has("accounts")) { vault.put("accounts", new JSONArray()); changed = true; }
        if (changed) store.write(vault);
        return vault;
    }
    private static JSONObject find(JSONObject vault, String clientId) throws Exception {
        JSONArray accounts = vault.getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (account.optString("client_id").equals(clientId)) return account;
        }
        return null;
    }
    private static JSONObject selected(JSONObject vault) throws Exception { return find(vault, vault.optString("selected", null)); }
    private static JSONObject sanitize(JSONObject account, String active) throws Exception {
        if (account == null) return null;
        return new JSONObject().put("client_id", account.getString("client_id"))
                .put("subject", account.optString("subject")).put("email", account.optString("email"))
                .put("name", account.optString("name")).put("label", account.optString("label"))
                .put("active", account.getString("client_id").equals(active))
                .put("signed_in", !account.optString("access_token").isEmpty())
                .put("plan_enabled", hasPlan(account.optString("scope")));
    }
    private static void clearTokens(JSONObject account) {
        for (String key : Arrays.asList("access_token", "refresh_token", "id_token", "oidc_nonce", "scope", "expires_at", "earliest_refresh_at", "token_type")) account.remove(key);
    }
    private static boolean hasPlan(String scopes) {
        Set<String> scope = new HashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
        return scope.contains("chatgpt.tokens.use.direct") && scope.contains("resource.invoke");
    }
    private static void replaceTokens(JSONObject account, JSONObject tokens, JSONObject identity, boolean refresh) throws Exception {
        if (!"Bearer".equalsIgnoreCase(tokens.optString("token_type")) || tokens.optString("access_token").isEmpty())
            throw new IOException("無効なトークン応答です。");
        long expiry = tokens.optLong("expires_in", 0);
        if (expiry <= 0 || expiry > 86400) throw new IOException("トークンの有効期間が無効です。");
        if (refresh && tokens.optString("refresh_token").isEmpty()) throw new IOException("更新トークンが返されませんでした。再度ログインしてください。");
        String scope = tokens.has("scope") ? tokens.getString("scope") : refresh ? account.optString("scope") : "";
        account.put("access_token", tokens.getString("access_token"));
        account.put("refresh_token", tokens.optString("refresh_token"));
        account.put("token_type", "Bearer").put("scope", scope);
        account.put("expires_at", System.currentTimeMillis() / 1000L + expiry);
        account.put("earliest_refresh_at", epoch(tokens.opt("earliest_refresh_at")));
        if (identity != null) {
            account.put("issuer", identity.getString("iss")).put("subject", identity.getString("sub"));
            account.put("email", identity.optString("email")).put("name", identity.optString("name"));
            account.put("id_token", tokens.getString("id_token"));
        }
    }
    private static long epoch(Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        if (value instanceof Number) { long n = ((Number) value).longValue(); return n > 100000000000L ? n / 1000 : n; }
        try { long n = Long.parseLong(value.toString()); return n > 100000000000L ? n / 1000 : n; }
        catch (Exception ignored) { try { return Instant.parse(value.toString()).getEpochSecond(); } catch (Exception invalid) { return 0; } }
    }

    private synchronized JSONObject discovery() throws Exception {
        if (metadata != null) return metadata;
        Http.Response response = Http.request("GET", DISCOVERY, null, null);
        if (response.status != 200) throw new IOException("OpenAI の認証設定を取得できませんでした。");
        JSONObject result = new JSONObject(response.body);
        if (!ISSUER.equals(result.optString("issuer"))) throw new IOException("認証サーバーが一致しません。");
        for (String key : Arrays.asList("authorization_endpoint", "token_endpoint", "jwks_uri", "revocation_endpoint"))
            trustedEndpoint(result.getString(key));
        metadata = result; return result;
    }
    private static String trustedEndpoint(String value) throws Exception {
        URI uri = new URI(value);
        if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost()) ||
                uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null)
            throw new IOException("認証エンドポイントが無効です。");
        return value;
    }
    private JSONObject tokenRequest(Map<String, String> parameters) throws Exception {
        Http.Response response = Http.request("POST", trustedEndpoint(discovery().getString("token_endpoint")),
                Collections.singletonMap("Content-Type", "application/x-www-form-urlencoded"), Http.form(parameters));
        JSONObject result;
        try { result = new JSONObject(response.body); }
        catch (Exception malformed) { throw new IOException("認証サーバーの応答を読み取れませんでした (HTTP " + response.status + ")。"); }
        if (response.status != 200) {
            String code = result.optString("error");
            if (result.optJSONObject("error") != null) code = result.getJSONObject("error").optString("code");
            String message = "invalid_grant".equals(code) ? "認証コードまたはセッションの有効期限が切れました。もう一度ログインしてください。" :
                    "invalid_client".equals(code) ? "アプリの登録が無効です。別の登録を追加してください。" :
                    "認証サーバーが要求を受け付けませんでした (HTTP " + response.status + ")。";
            throw new OAuthException(code, message);
        }
        return result;
    }
    private static final class OAuthException extends IOException {
        final String code;
        OAuthException(String code, String message) { super(message); this.code = code; }
    }
    private JSONObject validateIdToken(String token, String clientId, String nonce, String subject) throws Exception {
        JSONArray keys = signingKeys(false);
        try { return verifyJwt(token, keys, clientId, nonce, subject, System.currentTimeMillis() / 1000L); }
        catch (java.security.GeneralSecurityException signature) {
            return verifyJwt(token, signingKeys(true), clientId, nonce, subject, System.currentTimeMillis() / 1000L);
        }
    }
    private synchronized JSONArray signingKeys(boolean reload) throws Exception {
        if (!reload && jwks != null && System.currentTimeMillis() - jwksFetchedAt < 60 * 60 * 1000) return jwks;
        Http.Response response = Http.request("GET", trustedEndpoint(discovery().getString("jwks_uri")), null, null);
        if (response.status != 200) throw new IOException("認証署名の検証鍵を取得できませんでした。");
        jwks = new JSONObject(response.body).getJSONArray("keys"); jwksFetchedAt = System.currentTimeMillis(); return jwks;
    }
    /** Pure helper, package-visible for rejection tests with generated RSA keys. */
    static JSONObject verifyJwt(String token, JSONArray keys, String clientId, String nonce, String subject, long now) throws Exception {
        if (token.length() > 65536) throw new java.security.GeneralSecurityException("Oversized ID token");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new java.security.GeneralSecurityException("Malformed ID token");
        JSONObject header = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg")) || header.has("crit")) throw new java.security.GeneralSecurityException("Unsupported ID token algorithm");
        String kid = header.getString("kid"); JSONObject key = null;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject candidate = keys.getJSONObject(i);
            if (kid.equals(candidate.optString("kid")) && "RSA".equals(candidate.optString("kty")) &&
                    "sig".equals(candidate.optString("use", "sig")) && "RS256".equals(candidate.optString("alg", "RS256"))) {
                if (key != null) throw new java.security.GeneralSecurityException("Ambiguous signing key");
                key = candidate;
            }
        }
        if (key == null) throw new java.security.GeneralSecurityException("Unknown signing key");
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(key.getString("n")));
        if (modulus.bitLength() < 2048) throw new java.security.GeneralSecurityException("Weak signing key");
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,
                new BigInteger(1, Base64.getUrlDecoder().decode(key.getString("e"))))));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new java.security.GeneralSecurityException("Invalid ID token signature");
        JSONObject claims = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        if (!ISSUER.equals(claims.optString("iss"))) throw new java.security.GeneralSecurityException("Invalid issuer");
        Object aud = claims.opt("aud"); boolean audience = clientId.equals(aud);
        if (aud instanceof JSONArray) {
            JSONArray values = (JSONArray) aud;
            for (int i = 0; i < values.length(); i++) audience |= clientId.equals(values.optString(i));
            if (values.length() > 1 && !clientId.equals(claims.optString("azp"))) throw new java.security.GeneralSecurityException("Invalid authorized party");
        }
        if (!audience || (claims.has("azp") && !clientId.equals(claims.optString("azp")))) throw new java.security.GeneralSecurityException("Invalid audience");
        if (!claims.has("exp") || claims.getLong("exp") <= now - 60 || claims.optLong("nbf", 0) > now + 60 ||
                claims.optLong("iat", 0) > now + 60) throw new java.security.GeneralSecurityException("Invalid token time");
        if (nonce != null && !same(nonce, claims.optString("nonce", null))) throw new java.security.GeneralSecurityException("Invalid nonce");
        if (claims.optString("sub").isEmpty() || (subject != null && !subject.equals(claims.optString("sub")))) throw new java.security.GeneralSecurityException("Invalid subject");
        return claims;
    }
    static String pkce(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }
    static Map<String, String> parseQuery(String query) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.length() > 16384) throw new IOException("Invalid query");
        for (String part : query.split("&")) {
            String[] fields = part.split("=", 2);
            String key = URLDecoder.decode(fields[0], "UTF-8");
            String value = fields.length == 2 ? URLDecoder.decode(fields[1], "UTF-8") : "";
            if (result.put(key, value) != null) throw new IOException("Duplicate OAuth parameter");
        }
        return result;
    }
    private static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    private static String readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value;
        while ((value = input.read()) != -1) {
            if (bytes.size() >= 20000) throw new IOException("Request too large");
            bytes.write(value);
            if (value == '\n') return bytes.toString("US-ASCII").trim();
        }
        throw new IOException("Incomplete request");
    }
    private static void browserReply(Socket socket, int status, String message) throws IOException {
        // All message strings are app constants; OAuth parameters are never reflected.
        byte[] body = ("<!doctype html><html lang='ja'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<meta name='referrer' content='no-referrer'><title>PocketForge</title><style>body{font:18px system-ui;background:#101c21;color:#f3f8f7;padding:40px;line-height:1.7}h1{color:#9de4bc}</style>" +
                "<h1>PocketForge</h1><p>" + message + "</p><script>history.replaceState(null,'','/auth/callback')</script></html>").getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }
    private void succeed(Attempt attempt, JSONObject profile) {
        synchronized (lifecycleLock) {
            if (attempt.completed || attempt.cancelled) return;
            attempt.completed = true;
            if (pending == attempt) pending = null;
        }
        main.post(() -> attempt.callback.onSuccess(profile));
    }
    private void fail(Attempt attempt, String message) {
        synchronized (lifecycleLock) {
            if (attempt.completed || attempt.cancelled) return;
            attempt.completed = true; attempt.cancelled = true;
            if (pending == attempt) pending = null;
        }
        if (attempt.server != null) try { attempt.server.close(); } catch (IOException ignored) { }
        main.post(() -> attempt.callback.onError(message));
    }
    private static String safeError(Exception error, String fallback) {
        // Only our deliberately written Japanese errors are suitable for the UI. Never show server bodies/URLs.
        String value = error.getMessage();
        return value != null && value.matches("(?s).*[\\u3040-\\u30ff\\u3400-\\u9fff].*") ? value : fallback;
    }
}
