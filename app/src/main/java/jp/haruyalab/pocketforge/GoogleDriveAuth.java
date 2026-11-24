package jp.haruyalab.pocketforge;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Google Identity authorization; no client secret, password, or pasted token. */
public final class GoogleDriveAuth implements DriveSync.TokenProvider {
    public static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    public static final int REQUEST_AUTHORIZE = 7492;
    private static final List<Scope> SCOPES = Collections.singletonList(new Scope(DRIVE_SCOPE));
    private final AuthorizationClient client;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String accessToken;
    private volatile String lastError = "";
    private volatile boolean reconnectNeeded;
    private Callback pending;
    private int generation;
    private int activeRequestCode = REQUEST_AUTHORIZE;

    public interface Callback {
        void onSuccess(JSONObject status);
        void onError(String message);
    }

    public GoogleDriveAuth(Context context) {
        Context app = context.getApplicationContext();
        client = Identity.getAuthorizationClient(app);
        preferences = app.getSharedPreferences("google_drive_authorization", Context.MODE_PRIVATE);
    }

    private AuthorizationRequest request() {
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder().setRequestedScopes(SCOPES);
        String email = preferences.getString("email", "");
        if (!email.isEmpty()) builder.setAccount(new Account(email, "com.google"));
        return builder.build();
    }

    /** Call from the UI thread in response to the user choosing to connect Drive. */
    public void connect(Activity activity, Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> connect(activity, callback));
            return;
        }
        if (pending != null) {
            callback.onError("Google Drive の接続処理が進行中です。");
            return;
        }
        pending = callback;
        final int thisGeneration = ++generation;
        activeRequestCode = REQUEST_AUTHORIZE + (thisGeneration % 512);
        lastError = "";
        try {
            Identity.getAuthorizationClient(activity).authorize(request())
                .addOnSuccessListener(result -> {
                    if (generation != thisGeneration || pending == null) return;
                    if (result.hasResolution()) {
                        try {
                            if (result.getPendingIntent() == null) throw new Exception("承認画面を取得できませんでした。");
                            activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                activeRequestCode, null, 0, 0, 0);
                        } catch (Exception e) { fail(e); }
                    } else { verifyAndAccept(result, thisGeneration); }
                })
                .addOnFailureListener(e -> { if (generation == thisGeneration) fail(e); });
        } catch (Exception e) { fail(e); }
    }

    /** Forward Activity.onActivityResult here. Returns whether this result was consumed. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode < REQUEST_AUTHORIZE || requestCode >= REQUEST_AUTHORIZE + 512) return false;
        if (requestCode != activeRequestCode) return true;
        if (pending == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail(new Exception("Google Drive の接続はキャンセルされました。"));
            return true;
        }
        try { verifyAndAccept(client.getAuthorizationResultFromIntent(data), generation); }
        catch (Exception e) { fail(e); }
        return true;
    }

    private static String checkedToken(AuthorizationResult result) throws Exception {
        if (result.hasResolution()) throw new Exception("Google Drive の再承認が必要です。設定の「Drive に接続」を押してください。");
        if (!result.getGrantedScopes().contains(DRIVE_SCOPE)) {
            throw new Exception("Google Drive の同期権限が許可されていません。PC で作成したファイルも読むため Drive 全体の権限が必要です。");
        }
        String token = result.getAccessToken();
        if (token == null || token.isEmpty()) throw new Exception("Google のアクセストークンを取得できませんでした。");
        return token;
    }

    private void verifyAndAccept(AuthorizationResult result, int thisGeneration) {
        worker.execute(() -> {
            try {
                String token = checkedToken(result);
                JSONObject user = readDriveUser(token);
                main.post(() -> {
                    if (pending == null || generation != thisGeneration) return;
                    accessToken = token;
                    reconnectNeeded = false;
                    lastError = "";
                    preferences.edit()
                        .putBoolean("authorized", true)
                        .putString("email", user.optString("emailAddress"))
                        .putString("name", user.optString("displayName"))
                        .putString("permissionId", user.optString("permissionId"))
                        .putLong("verifiedAt", System.currentTimeMillis()).apply();
                    Callback done = pending;
                    pending = null;
                    done.onSuccess(status());
                });
            } catch (Exception e) { main.post(() -> { if (generation == thisGeneration) fail(e); }); }
        });
    }

    /**
     * Use only on a background thread. Google Play services refreshes its own
     * token cache. This method never launches consent UI from a sync worker.
     */
    public String getAccessToken() throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Drive token retrieval must run on a background thread");
        }
        if (!preferences.getBoolean("authorized", false)) {
            throw new Exception("Google Drive は未接続です。設定の「Drive に接続」から承認してください。");
        }
        try {
            AuthorizationResult result = Tasks.await(client.authorize(request()), 45, TimeUnit.SECONDS);
            String token = checkedToken(result);
            accessToken = token;
            reconnectNeeded = false;
            lastError = "";
            return token;
        } catch (Exception e) {
            accessToken = null;
            reconnectNeeded = true;
            lastError = explain(e);
            throw new Exception(lastError, e);
        }
    }

    /** Call after a Drive HTTP 401 before a later, explicit retry. */
    public void invalidateToken(String rejectedToken) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Token invalidation must run on a background thread");
        }
        accessToken = null;
        if (rejectedToken != null && !rejectedToken.isEmpty()) {
            Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(rejectedToken).build()),
                30, TimeUnit.SECONDS);
        }
    }

    /** Revoke Google's grant, then clear local connection state. Never deletes source projects. */
    public void disconnect(Activity activity, Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> disconnect(activity, callback));
            return;
        }
        if (pending != null) {
            callback.onError("接続処理の終了後に解除してください。");
            return;
        }
        String email = preferences.getString("email", "");
        if (email.isEmpty()) {
            clearLocal();
            callback.onSuccess(status());
            return;
        }
        RevokeAccessRequest revoke = RevokeAccessRequest.builder()
            .setAccount(new Account(email, "com.google"))
            .setScopes(SCOPES).build();
        Identity.getAuthorizationClient(activity).revokeAccess(revoke)
            .addOnSuccessListener(unused -> {
                clearLocal();
                callback.onSuccess(status());
            })
            .addOnFailureListener(e -> callback.onError(explain(e)
                + " Google の権限は解除できていません。接続を確認して再試行してください。"));
    }

    private void clearLocal() {
        accessToken = null;
        reconnectNeeded = false;
        lastError = "";
        preferences.edit().clear().apply();
    }

    /** Cancels this Activity's callback only; does not revoke a granted account. */
    public void cancelConnect() {
        main.post(() -> {
            Callback cancelled = pending;
            pending = null;
            generation++;
            if (cancelled != null) cancelled.onError("接続処理を終了しました。");
        });
    }

    public JSONObject status() {
        JSONObject out = new JSONObject();
        boolean granted = preferences.getBoolean("authorized", false);
        try {
            out.put("connected", granted && !reconnectNeeded);
            out.put("authorized", granted);
            out.put("verifiedThisSession", accessToken != null);
            out.put("needsAuthorization", !granted || reconnectNeeded);
            out.put("state", reconnectNeeded ? "reauthorize" : granted ? "connected" : "not_connected");
            out.put("email", preferences.getString("email", ""));
            out.put("name", preferences.getString("name", ""));
            out.put("permissionId", preferences.getString("permissionId", ""));
            out.put("verifiedAt", preferences.getLong("verifiedAt", 0));
            out.put("scope", DRIVE_SCOPE);
            out.put("lastError", lastError);
            out.put("setupRequired", !granted);
            out.put("setupMessage", "初回は Google Cloud で Drive API を有効化し、この APK のパッケージ名と署名 SHA-1 を Android OAuth クライアントに登録してください。");
            out.put("permissionExplanation", "PC で新しく作成したファイルを含めて同期するため、Drive 全体への権限を要求します。アプリの同期対象は PocketForge 内の選択したプロジェクトです。");
        } catch (Exception ignored) { }
        return out;
    }

    private void fail(Exception error) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> fail(error)); return; }
        accessToken = null;
        reconnectNeeded = true;
        lastError = explain(error);
        Callback callback = pending;
        pending = null;
        if (callback != null) callback.onError(lastError);
    }

    private static String explain(Throwable error) {
        Throwable t = error;
        for (int i = 0; i < 5 && t.getCause() != null; i++) t = t.getCause();
        if (t instanceof ApiException) {
            int code = ((ApiException) t).getStatusCode();
            if (code == 10 || code == 12500) return "Google OAuth の構成を確認してください。Google Cloud で Drive API、テストユーザー、パッケージ名 jp.haruyalab.pocketforge とこの APK の署名 SHA-1 の登録が必要です。（Google code " + code + "）";
            if (code == 7) return "Google に接続できません。ネットワークを確認してください。（Google code 7）";
            if (code == 16 || code == 12501) return "Google Drive の接続はキャンセルされました。";
            return "Google Drive の承認に失敗しました。（Google code " + code + "）Google Play 開発者サービスと OAuth の設定を確認してください。";
        }
        if (t instanceof java.util.concurrent.TimeoutException) return "Google の応答がタイムアウトしました。接続を確認して再試行してください。";
        String message = t.getMessage();
        return message == null || message.isEmpty() ? "Google Drive の接続に失敗しました。" : message;
    }

    private static JSONObject readDriveUser(String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,permissionId,displayName)").openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                // Never reflect provider responses: they can contain credentials or private data.
                if (code == 403) throw new Exception("Drive API が利用できません。Google Cloud の Drive API 有効化と Drive 権限の承認を確認してください。（HTTP 403）");
                if (code == 401) throw new Exception("Google の認証が失効しています。Drive に再接続してください。（HTTP 401）");
                throw new Exception("Google Drive の接続確認に失敗しました。（HTTP " + code + "）");
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 65536) throw new Exception("Google Drive の応答サイズが上限を超えました。");
                    out.write(buffer, 0, n);
                }
                JSONObject user = new JSONObject(out.toString("UTF-8")).getJSONObject("user");
                if (user.optString("permissionId").isEmpty() || user.optString("emailAddress").isEmpty()) {
                    throw new Exception("Google Drive のアカウントを確認できませんでした。");
                }
                return user;
            }
        } finally { connection.disconnect(); }
    }
}
