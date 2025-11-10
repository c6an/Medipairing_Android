package com.example.medipairing.ui.medipairing;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Button;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.Context;

import androidx.annotation.Nullable;

import com.example.medipairing.R;
import com.example.medipairing.util.ReminderReceiver;
import com.example.medipairing.util.AlarmScheduler;

public class AlarmRingActivity extends Activity {
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Show on lock and turn screen on (for modern APIs prefer setTurnScreenOn/setShowWhenLocked)
        try { getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignore) {}
        setContentView(R.layout.activity_alarm_ring);

        String title = getIntent().getStringExtra(ReminderReceiver.EXTRA_TITLE);
        String text = getIntent().getStringExtra(ReminderReceiver.EXTRA_TEXT);
        TextView tvTitle = findViewById(R.id.tv_alarm_title);
        TextView tvText = findViewById(R.id.tv_alarm_text);
        if (tvTitle != null) tvTitle.setText(title != null ? title : "복용 알림");
        if (tvText != null) tvText.setText(text != null ? text : "약을 복용할 시간입니다");

        // Vibrate pattern like a messenger alert
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                long[] pattern = new long[]{0, 800, 300, 800};
                if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                else v.vibrate(pattern, -1);
            }
        } catch (Throwable ignore) {}

        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSnooze = findViewById(R.id.btn_snooze);
        if (btnDismiss != null) btnDismiss.setOnClickListener(v -> finish());
        if (btnSnooze != null) btnSnooze.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            // Snooze 5 minutes (inexact if exact not allowed)
            AlarmScheduler.scheduleAt(this, (int)(now & 0x7FFFFFFF), now + 5 * 60 * 1000L, title, text);
            finish();
        });
    }
}

