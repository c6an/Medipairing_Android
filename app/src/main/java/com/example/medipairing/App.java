package com.example.medipairing;

import android.app.Application;
import com.kakao.sdk.common.KakaoSdk;
import com.kakao.sdk.common.util.Utility;
import android.util.Log;
import com.example.medipairing.util.NotificationHelper;

public class App extends Application{
    @Override public void onCreate() {
        super.onCreate();
        KakaoSdk.init(this, "07a24eede383d5a4ec11b8c447e65d2f");
        try {
            boolean isDebug = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (isDebug) {
                String keyHash = Utility.INSTANCE.getKeyHash(this);
                Log.i("KakaoKeyHash", keyHash);
            }
        } catch (Exception ignored) {}
        try { NotificationHelper.ensureChannels(this); } catch (Throwable ignored) {}
    }
}
