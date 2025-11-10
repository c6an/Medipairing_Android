package com.example.medipairing.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;

public final class GoogleConfig {
    private GoogleConfig() {}

    public static String getDefaultWebClientId(Context ctx) {
        if (ctx == null) return null;
        int resId = ctx.getResources().getIdentifier("default_web_client_id", "string", ctx.getPackageName());
        if (resId == 0) return null;
        try {
            String id = ctx.getString(resId);
            if (id == null || id.trim().isEmpty()) return null;
            return id.trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static void logSigningInfo(Context ctx) {
        if (ctx == null) return;
        boolean isDebug;
        try {
            isDebug = (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) { isDebug = false; }
        if (!isDebug) return;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pInfo;
            if (Build.VERSION.SDK_INT >= 28) {
                pInfo = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pInfo.signingInfo;
                Signature[] sigs = si != null ? si.getApkContentsSigners() : null;
                if (sigs != null && sigs.length > 0) {
                    Log.i("GoogleConfig", "Signing SHA1=" + sha1Hex(sigs[0].toByteArray()));
                }
            } else {
                pInfo = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
                if (pInfo.signatures != null && pInfo.signatures.length > 0) {
                    Log.i("GoogleConfig", "Signing SHA1=" + sha1Hex(pInfo.signatures[0].toByteArray()));
                }
            }
            Log.i("GoogleConfig", "Package=" + ctx.getPackageName());
        } catch (Exception e) {
            Log.w("GoogleConfig", "Failed to log signing info", e);
        }
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
