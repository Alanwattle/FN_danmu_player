package dev.ushio.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Preferences {
    private static final String KEY = "ushio.credentials.v1";
    static final String DEMO = "demo";
    final SharedPreferences values;

    Preferences(Context context) {
        values = context.getSharedPreferences("ushio", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT
                | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    synchronized String secret(String name) {
        String encoded = values.getString(name, "");
        if (encoded.isEmpty()) return "";
        try {
            byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception invalid) {
            values.edit().remove(name).apply();
            return "";
        }
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    synchronized void save(FnClient.Session session) throws Exception {
        JSONObject data = FnClient.json("base", session.base, "username", session.username,
                "token", session.token, "signed", session.signed, "legacy", session.legacy,
                "relay", session.relay, "relayId", session.relayId);
        values.edit().putString("session", encrypt(data.toString()))
                .putBoolean(DEMO, false).apply();
    }

    synchronized void saveDanmu(String danmu) throws Exception {
        values.edit().putString("danmu", encrypt(danmu)).apply();
    }

    FnClient.Session session() {
        try {
            JSONObject data = new JSONObject(secret("session"));
            return new FnClient.Session(data.getString("base"), data.getString("username"),
                    data.getString("token"), data.optBoolean("signed", data.optBoolean("legacy")),
                    data.optBoolean("legacy"), data.optBoolean("relay"), data.optString("relayId", ""));
        } catch (Exception invalid) {
            return null;
        }
    }

    void clearSession() { values.edit().remove("session").putBoolean(DEMO, false).apply(); }
    boolean demo() { return values.getBoolean(DEMO, false); }
    void demo(boolean active) { values.edit().putBoolean(DEMO, active).apply(); }

    String mediaKey(FnClient.Session session, String id) {
        return FnClient.hash("SHA-256", (session == null ? DEMO : session.base + "/" + session.username) + "/" + id);
    }
}

