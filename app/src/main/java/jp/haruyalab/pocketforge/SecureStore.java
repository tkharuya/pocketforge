package jp.haruyalab.pocketforge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Atomic encrypted records; the AES key never leaves Android Keystore. */
public final class SecureStore {
    private static final String ALIAS = "pocketforge.credentials.v1";
    private static final byte[] AAD = "PocketForge session v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences preferences;
    public SecureStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE);
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
    }
    public synchronized JSONObject read() throws Exception {
        String value = preferences.getString("vault", null);
        if (value == null) return new JSONObject();
        JSONObject envelope = new JSONObject(value);
        byte[] iv = Base64.getDecoder().decode(envelope.getString("iv"));
        if (iv.length != 12) throw new java.security.GeneralSecurityException("Invalid credential envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        return new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data"))), StandardCharsets.UTF_8));
    }
    public synchronized void write(JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject()
                .put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                .put("data", Base64.getEncoder().encodeToString(encrypted));
        if (!preferences.edit().putString("vault", envelope.toString()).commit())
            throw new java.io.IOException("認証情報を保存できませんでした。");
    }
}
