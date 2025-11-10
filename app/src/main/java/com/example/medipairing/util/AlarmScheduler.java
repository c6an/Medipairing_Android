package com.example.medipairing.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

public final class AlarmScheduler {
    public static boolean canScheduleExact(Context context) {
        if (context == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        }
        return true;
    }
    public static void scheduleDaily(Context context, int requestId, int hour, int minute, String title, String text) {
        if (context == null) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long triggerAt = nextTriggerMillis(hour, minute);

        Intent i = new Intent(context, ReminderReceiver.class);
        i.putExtra(ReminderReceiver.EXTRA_TITLE, title);
        i.putExtra(ReminderReceiver.EXTRA_TEXT, text);
        i.putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, requestId);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestId, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Use AlarmClockInfo for user-visible exact alarms without special permission
        Intent show = new Intent(context, com.example.medipairing.MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(context, requestId + 100000, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(triggerAt, showPi);
        am.setAlarmClock(info, pi);
    }

    public static void cancel(Context context, int requestId) {
        if (context == null) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestId, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    public static void scheduleAt(Context context, int requestId, long triggerAtMillis, String title, String text) {
        scheduleAt(context, requestId, triggerAtMillis, title, text, null);
    }

    public static void scheduleAt(Context context, int requestId, long triggerAtMillis, String title, String text, String identity) {
        if (context == null) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(context, ReminderReceiver.class);
        i.setAction("com.example.medipairing.ALARM");
        if (identity != null) {
            try { i.setData(android.net.Uri.parse("medipairing://alarm/" + identity)); } catch (Throwable ignored) {}
        }
        i.putExtra(ReminderReceiver.EXTRA_TITLE, title);
        i.putExtra(ReminderReceiver.EXTRA_TEXT, text);
        i.putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, requestId);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestId, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (android.os.Build.VERSION.SDK_INT >= 31 && !canScheduleExact(context)) {
            // Fallback to inexact to avoid SecurityException
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else {
            Intent show = new Intent(context, com.example.medipairing.MainActivity.class);
            if (identity != null) try { show.setData(android.net.Uri.parse("medipairing://alarm/" + identity)); } catch (Throwable ignored) {}
            PendingIntent showPi = PendingIntent.getActivity(context, requestId + 200000, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(triggerAtMillis, showPi);
            am.setAlarmClock(info, pi);
        }
    }

    private static long nextTriggerMillis(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        long t = c.getTimeInMillis();
        if (t <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
            t = c.getTimeInMillis();
        }
        return t;
    }
}
