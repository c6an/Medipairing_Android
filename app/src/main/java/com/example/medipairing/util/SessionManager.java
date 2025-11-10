package com.example.medipairing.util;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {
    private static final String PREFS = "medi_prefs";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_EMAIL = "user_email";
    private static final String KEY_PROVIDER = "auth_provider";
    private static final String KEY_MY_PILLS = "my_pill_codes_csv";
    private static final String KEY_JWT = "auth_jwt";
    private static final String KEY_USER_ID = "user_id";

    private static android.content.SharedPreferences getPrefs(Context context) {
        try {
            androidx.security.crypto.MasterKey masterKey = new androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return androidx.security.crypto.EncryptedSharedPreferences.create(
                    context,
                    PREFS,
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Throwable t) {
            // Fallback to unencrypted prefs if Security-crypto is unavailable
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    public static boolean isLoggedIn(Context context) {
        if (context == null) return false;
        SharedPreferences sp = getPrefs(context);
        return sp.getBoolean(KEY_LOGGED_IN, false);
    }

    public static void setLoggedIn(Context context, boolean value) {
        if (context == null) return;
        getPrefs(context)
                .edit()
                .putBoolean(KEY_LOGGED_IN, value)
                .apply();
    }

    public static void setEmail(Context context, String email) {
        if (context == null) return;
        getPrefs(context)
                .edit()
                .putString(KEY_EMAIL, email)
                .apply();
    }

    public static String getEmail(Context context) {
        if (context == null) return null;
        return getPrefs(context)
                .getString(KEY_EMAIL, null);
    }

    public static void setProvider(Context context, String provider) {
        if (context == null) return;
        getPrefs(context)
                .edit()
                .putString(KEY_PROVIDER, provider)
                .apply();
    }

    public static String getProvider(Context context) {
        if (context == null) return null;
        return getPrefs(context)
                .getString(KEY_PROVIDER, null);
    }

    public static void clear(Context context) {
        if (context == null) return;
        getPrefs(context)
                .edit()
                .clear()
                .apply();
    }

    public static void setMyPillCodes(Context context, java.util.List<String> codes) {
        if (context == null) return;
        String csv = codes == null ? "" : android.text.TextUtils.join(",", codes);
        getPrefs(context)
                .edit().putString(KEY_MY_PILLS, csv).apply();
    }

    public static java.util.List<String> getMyPillCodes(Context context) {
        if (context == null) return java.util.Collections.emptyList();
        String csv = getPrefs(context).getString(KEY_MY_PILLS, "");
        if (csv == null || csv.trim().isEmpty()) return java.util.Collections.emptyList();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String s : csv.split(",")) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public static void setJwt(Context context, String jwt) {
        if (context == null) return;
        getPrefs(context)
                .edit().putString(KEY_JWT, jwt).apply();
    }

    public static String getJwt(Context context) {
        if (context == null) return null;
        return getPrefs(context)
                .getString(KEY_JWT, null);
    }

    public static String getOrCreateUserId(Context context) {
        if (context == null) return null;
        // Only provide dev fallback ID when logged in; otherwise return null to avoid stale access
        if (!isLoggedIn(context)) return null;
        SharedPreferences sp = getPrefs(context);
        String id = sp.getString(KEY_USER_ID, null);
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            sp.edit().putString(KEY_USER_ID, id).apply();
        }
        return id;
    }

    public static void clearUser(Context context) {
        if (context == null) return;
        getPrefs(context)
                .edit()
                .remove(KEY_JWT)
                .remove(KEY_USER_ID)
                .putBoolean(KEY_LOGGED_IN, false)
                .apply();
    }
}
