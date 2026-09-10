package com.avatar.nputest;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the user-supplied DashScope key encrypted by AndroidKeyStore. */
public final class CredentialStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "avatar_dashscope_api_key_v1";
    private static final String PREFS = "cloud_credentials";
    private static final String VALUE = "dashscope_api_key";
    private static final int FORMAT_VERSION = 1;

    private final SharedPreferences preferences;

    public CredentialStore(Context context) {
        Context app = Objects.requireNonNull(context, "context").getApplicationContext();
        if (app == null) {
            app = context;
        }
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String get() {
        String encoded = preferences.getString(VALUE, "");
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            byte[] stored = Base64.decode(encoded, Base64.NO_WRAP);
            if (stored.length < 3 || (stored[0] & 0xff) != FORMAT_VERSION) {
                return "";
            }
            int ivLength = stored[1] & 0xff;
            if (ivLength == 0 || stored.length <= 2 + ivLength) {
                return "";
            }
            byte[] iv = Arrays.copyOfRange(stored, 2, 2 + ivLength);
            byte[] ciphertext = Arrays.copyOfRange(stored, 2 + ivLength, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) {
            return "";
        }
    }

    public void set(String key) {
        String value = Objects.requireNonNull(key, "key").trim();
        if (value.isEmpty()) {
            commitEncoded(preferences, "");
            return;
        }
        if (containsWhitespace(value)) {
            throw new IllegalArgumentException("API key contains whitespace");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            if (iv.length > 255) {
                throw new GeneralSecurityException("invalid IV length");
            }
            byte[] stored = new byte[2 + iv.length + ciphertext.length];
            stored[0] = FORMAT_VERSION;
            stored[1] = (byte) iv.length;
            System.arraycopy(iv, 0, stored, 2, iv.length);
            System.arraycopy(ciphertext, 0, stored, 2 + iv.length, ciphertext.length);
            String encoded = Base64.encodeToString(stored, Base64.NO_WRAP);
            commitEncoded(preferences, encoded);
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Could not protect API key", exception);
        }
    }

    // SharedPreferences changes its in-memory value even when its disk commit fails.
    static void commitEncoded(SharedPreferences preferences, String encoded) {
        String previous = preferences.getString(VALUE, null);
        SharedPreferences.Editor update = preferences.edit();
        if (encoded.isEmpty()) update.remove(VALUE); else update.putString(VALUE, encoded);
        if (!update.commit()) {
            SharedPreferences.Editor rollback = preferences.edit();
            if (previous == null) rollback.remove(VALUE); else rollback.putString(VALUE, previous);
            rollback.commit(); // Restores the running value even if storage is still unavailable.
            throw new IllegalStateException("Could not commit credential setting");
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
