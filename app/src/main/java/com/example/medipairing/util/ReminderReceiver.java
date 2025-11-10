package com.example.medipairing.util;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.medipairing.MainActivity;
import com.example.medipairing.R;

public class ReminderReceiver extends BroadcastReceiver {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_NOTIFICATION_ID = "nid";

    @Override public void onReceive(Context context, Intent intent) {
        NotificationHelper.ensureChannels(context);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String text = intent.getStringExtra(EXTRA_TEXT);
        int nid = intent.getIntExtra(EXTRA_NOTIFICATION_ID, (int)System.currentTimeMillis());

        // Full-screen alarm activity
        Intent fs = new Intent(context, com.example.medipairing.ui.medipairing.AlarmRingActivity.class);
        fs.putExtra(EXTRA_TITLE, title);
        fs.putExtra(EXTRA_TEXT, text);
        fs.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fsPi = PendingIntent.getActivity(context, nid + 1, fs, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // High-priority, alarm-category notification with vibration and heads-up
        long[] vibrate = new long[]{0, 800, 300, 800};
        NotificationCompat.Builder nb = new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_ALARMS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title != null ? title : "복용 알림")
                .setContentText(text != null ? text : "약을 복용할 시간입니다")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(vibrate)
                .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(fsPi, true);

        NotificationManagerCompat.from(context).notify(nid, nb.build());
    }
}
