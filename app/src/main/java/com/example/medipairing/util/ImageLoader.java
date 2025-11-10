package com.example.medipairing.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public final class ImageLoader {
    private static final OkHttpClient http = new OkHttpClient();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private ImageLoader() {}

    public static void loadInto(String url, ImageView target) {
        if (url == null || url.isEmpty() || target == null) return;
        Request req = new Request.Builder().url(url).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { /* ignore */ }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) return;
                byte[] bytes = response.body().bytes();
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    main.post(() -> target.setImageBitmap(bmp));
                }
            }
        });
    }
}

