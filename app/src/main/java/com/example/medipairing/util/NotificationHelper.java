package com.example.medipairing.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public final class NotificationHelper {
    public static final String CHANNEL_ID_REMINDERS = "pill_reminders";
    public static final String CHANNEL_ID_ALARMS = "pill_alarms";
    public static void ensureChannels(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID_REMINDERS);
            if (ch == null) {
                ch = new NotificationChannel(CHANNEL_ID_REMINDERS, "복용 알림", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("알약 복용 시간 알림");
                nm.createNotificationChannel(ch);
            }
            NotificationChannel alarmCh = nm.getNotificationChannel(CHANNEL_ID_ALARMS);
            if (alarmCh == null) {
                alarmCh = new NotificationChannel(CHANNEL_ID_ALARMS, "긴급 알람", NotificationManager.IMPORTANCE_HIGH);
                alarmCh.enableVibration(true);
                alarmCh.setDescription("정해진 시간에 상단 배너로 울림");
                nm.createNotificationChannel(alarmCh);
            }
        }
    }
}
