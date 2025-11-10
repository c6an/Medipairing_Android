package com.example.medipairing.ui.medipairing;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.medipairing.R;
import com.example.medipairing.ui.mypage.MyPageFragment;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MedicineAlarmFragment extends Fragment {

    private TextView tvStartDate, tvEndDate;
    private TextView btnGeneral, btnPrescription;
    private LinearLayout timeSlotsContainer;
    private List<View> dayOfWeekButtons = new ArrayList<>();
    // Pill info
    private String alias;
    private String name;
    private String code;
    private String imageUrl;
    private int userItemId = -1;
    private int pillId = -1;

    public static MedicineAlarmFragment newInstance(String alias, String name, String code, String imageUrl, int userItemId, int pillId) {
        MedicineAlarmFragment f = new MedicineAlarmFragment();
        Bundle b = new Bundle();
        b.putString("alias", alias);
        b.putString("name", name);
        b.putString("code", code);
        b.putString("image_url", imageUrl);
        b.putInt("user_item_id", userItemId);
        b.putInt("pill_id", pillId);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_medicine_alarm, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize Views
        tvStartDate = view.findViewById(R.id.tv_start_date);
        tvEndDate = view.findViewById(R.id.tv_end_date);
        btnGeneral = view.findViewById(R.id.btn_type_general);
        btnPrescription = view.findViewById(R.id.btn_type_prescription);
        timeSlotsContainer = view.findViewById(R.id.time_slots_container);

        // Read args and bind header card
        Bundle args = getArguments();
        if (args != null) {
            alias = args.getString("alias", "");
            name = args.getString("name", "");
            code = args.getString("code", "");
            imageUrl = args.getString("image_url", null);
            userItemId = args.getInt("user_item_id", -1);
            pillId = args.getInt("pill_id", -1);
        }
        TextView tvAlias = view.findViewById(R.id.tv_alias);
        TextView tvNameCode = view.findViewById(R.id.tv_name_code);
        ImageView ivPill = view.findViewById(R.id.iv_pill_image);
        if (tvAlias != null) tvAlias.setText(alias != null && !alias.isEmpty() ? alias : (name != null ? name : "사용자 지정 이름"));
        if (tvNameCode != null) {
            String sub = (name != null ? name : "") + (code != null && !code.isEmpty() ? (" / " + code) : "");
            tvNameCode.setText(sub);
        }
        if (ivPill != null && imageUrl != null && !imageUrl.isEmpty()) {
            try { com.bumptech.glide.Glide.with(this).load(imageUrl).into(ivPill); } catch (Throwable ignored) {}
        }

        // Setup Click Listeners
        view.findViewById(R.id.btn_return_alarm).setOnClickListener(v -> getParentFragmentManager().popBackStack());
        view.findViewById(R.id.btn_save_alarm).setOnClickListener(v -> onSaveAlarm());

        // Date Pickers
        tvStartDate.setOnClickListener(v -> showDatePickerDialog(tvStartDate));
        tvEndDate.setOnClickListener(v -> showDatePickerDialog(tvEndDate));

        // Initial visual state: before input → blue text
        try {
            int blue = requireContext().getColor(R.color.login_bg);
            tvStartDate.setText("입력"); tvStartDate.setTextColor(blue);
            tvEndDate.setText("입력"); tvEndDate.setTextColor(blue);
        } catch (Throwable ignored) {}

        // Medicine Type Buttons
        btnGeneral.setOnClickListener(v -> selectMedicineType(btnGeneral));
        btnPrescription.setOnClickListener(v -> selectMedicineType(btnPrescription));
        btnPrescription.setSelected(true); // Default selection

        // Days of Week Buttons
        LinearLayout daysContainer = view.findViewById(R.id.days_of_week_container);
        String[] days = {"월", "화", "수", "목", "금", "토", "일"};
        for (String day : days) {
            TextView dayButton = createDayButton(day);
            daysContainer.addView(dayButton);
            dayOfWeekButtons.add(dayButton);
        }

        // Load previously saved config; if none, add one empty slot
        if (!loadAlarmConfig()) {
            addTimeSlot();
            ensureDatePlaceholderBlue();
        } else {
            ensureDatePlaceholderBlue();
        }
    }

    private void onSaveAlarm() {
        // Android 13+ notifications permission
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1202);
                Toast.makeText(getContext(), "알림 권한을 허용해 주세요", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        // Android 12+ exact alarm permission
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (!com.example.medipairing.util.AlarmScheduler.canScheduleExact(requireContext())) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    intent.setData(android.net.Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(intent);
                    Toast.makeText(getContext(), "정확한 알람 권한을 허용해 주세요", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
                // We will still schedule inexact alarms as fallback below
            }
        }
        // Collect selected days (Mon..Sun)
        boolean[] days = new boolean[7];
        int selCount = 0;
        for (int i = 0; i < dayOfWeekButtons.size() && i < 7; i++) {
            days[i] = dayOfWeekButtons.get(i).isSelected();
            if (days[i]) selCount++;
        }
        // 요일을 하나도 선택하지 않았다면: 모든 요일로 간주(기본값)
        if (selCount == 0) {
            for (int i = 0; i < 7; i++) days[i] = true;
        }

        // Parse + validate date range
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault());
        java.util.Calendar todayCal = java.util.Calendar.getInstance();
        todayCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        todayCal.set(java.util.Calendar.MINUTE, 0);
        todayCal.set(java.util.Calendar.SECOND, 0);
        todayCal.set(java.util.Calendar.MILLISECOND, 0);
        java.util.Date start = null, end = null;
        try { String s = String.valueOf(tvStartDate.getText()); if (!"입력".equals(s)) start = fmt.parse(s); } catch (Exception ignored) {}
        try { String e = String.valueOf(tvEndDate.getText()); if (!"입력".equals(e)) end = fmt.parse(e); } catch (Exception ignored) {}
        if (start != null && end != null && start.after(end)) {
            Toast.makeText(getContext(), "시작일이 종료일보다 늦습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        int baseId = getBaseId();
        int scheduled = 0;
        int totalSlots = timeSlotsContainer.getChildCount();
        int enabledSlots = 0;
        for (int i = 0; i < timeSlotsContainer.getChildCount(); i++) {
            View child = timeSlotsContainer.getChildAt(i);
            TextView tv = child.findViewById(R.id.tv_time);
            SwitchCompat sw = child.findViewById(R.id.toggle_alarm);
            if (tv == null || sw == null) continue;
            if (!sw.isChecked()) continue;
            enabledSlots++;
            int[] hm = parseHourMinute(String.valueOf(tv.getText()));
            if (hm == null) continue;
            for (int d = 0; d < 7; d++) {
                if (!days[d]) continue;
                long at = computeNextTriggerWithinRange(d, hm[0], hm[1], start, end);
                if (at <= 0) continue;
                // Build a robust unique identity per user + (userItemId|pillId) + code + day + slot
                String userNs = getUserNamespace();
                int pk = pillKeyId();
                String uniq = userNs + ":k" + pk + ":d" + d + ":s" + i;
                int reqId = (uniq.hashCode() & 0x7fffffff);
                String title = (alias != null && !alias.isEmpty()) ? alias : (name != null ? name : "복용 알림");
                String text = name != null ? (name + " 복용 시간입니다") : "약을 복용할 시간입니다";
                com.example.medipairing.util.AlarmScheduler.scheduleAt(requireContext(), reqId, at, title, text, uniq);
                scheduled++;
            }
        }
        // Persist selections regardless of scheduled count (allow OFF slots to be saved)
        persistAlarmConfig(days, start, end);
        if (scheduled > 0) {
            Toast.makeText(getContext(), "알람이 저장되었습니다.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "설정이 저장되었습니다(예약 없음)", Toast.LENGTH_SHORT).show();
        }
        getParentFragmentManager().popBackStack();
    }

    private static int[] parseHourMinute(String text) {
        if (text == null) return null;
        text = text.trim();
        try {
            if (text.startsWith("오전") || text.startsWith("오후")) {
                boolean pm = text.startsWith("오후");
                String[] parts = text.substring(2).trim().split(":");
                int h = Integer.parseInt(parts[0].trim());
                int m = Integer.parseInt(parts[1].trim());
                if (pm && h < 12) h += 12; if (!pm && h == 12) h = 0;
                return new int[]{h, m};
            } else if (text.contains(":")) {
                String[] parts = text.split(":");
                return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static long computeNextTriggerWithinRange(int dowMon0, int hour, int minute, java.util.Date startDate, java.util.Date endDate) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        if (startDate != null) c.setTime(startDate);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);

        int targetDow = dowMon0 + 2; // Mon=2 .. Sun=1
        if (targetDow > java.util.Calendar.SATURDAY) targetDow = java.util.Calendar.SUNDAY;

        // Advance to target DOW
        while (c.get(java.util.Calendar.DAY_OF_WEEK) != targetDow) c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        // If equal or before now (e.g., same minute), nudge to the immediate future
        if (!c.after(now)) {
            c.add(java.util.Calendar.MINUTE, 1);
        }

        // Allow scheduling even if the configured end date is in the past.
        // We do not block alarms by endDate; we only ensure the next future occurrence.
        return c.getTimeInMillis();
    }

    private TextView createDayButton(String day) {
        TextView button = new TextView(getContext());
        button.setText(day);
        button.setBackgroundResource(R.drawable.bg_day_of_week_selector);
        try {
            button.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.day_of_week_text_color_selector));
        } catch (Throwable t) {
            // Fallback
            button.setTextColor(0xFF1778F2);
        }
        button.setGravity(Gravity.CENTER);

        int size = (int) (40 * getResources().getDisplayMetrics().density);
        int margin = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);
        button.setLayoutParams(params);

        button.setOnClickListener(v -> v.setSelected(!v.isSelected()));
        return button;
    }

    private void showDatePickerDialog(TextView dateView) {
        Calendar c = Calendar.getInstance();
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                getContext(),
                (view, year, month, dayOfMonth) -> {
                    String selectedDate = String.format(Locale.getDefault(), "%d.%02d.%02d", year, month + 1, dayOfMonth);
                    dateView.setText(selectedDate);
                    // After input → black text
                    dateView.setTextColor(Color.BLACK);
                },
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    private void selectMedicineType(TextView selectedButton) {
        if (selectedButton == btnGeneral) {
            btnGeneral.setSelected(true);
            btnPrescription.setSelected(false);
        } else {
            btnGeneral.setSelected(false);
            btnPrescription.setSelected(true);
        }
    }

    private void addTimeSlot() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View timeSlotView = inflater.inflate(R.layout.item_time_slot, timeSlotsContainer, false);

        TextView tvTime = timeSlotView.findViewById(R.id.tv_time);
        SwitchCompat toggleAlarm = timeSlotView.findViewById(R.id.toggle_alarm);
        ImageView btnAddTime = timeSlotView.findViewById(R.id.btn_add_time);

        tvTime.setOnClickListener(v -> showTimePickerDialog(tvTime));
        btnAddTime.setOnClickListener(v -> addTimeSlot());

        // 기본값: 새 슬롯은 ON 상태로 두어 바로 저장 가능하도록
        if (toggleAlarm != null) toggleAlarm.setChecked(true);

        timeSlotsContainer.addView(timeSlotView);
    }

    private void addTimeSlot(int hour24, int minute, boolean enabled) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View timeSlotView = inflater.inflate(R.layout.item_time_slot, timeSlotsContainer, false);
        TextView tvTime = timeSlotView.findViewById(R.id.tv_time);
        SwitchCompat toggleAlarm = timeSlotView.findViewById(R.id.toggle_alarm);
        ImageView btnAddTime = timeSlotView.findViewById(R.id.btn_add_time);
        btnAddTime.setOnClickListener(v -> addTimeSlot());
        if (hour24 >= 0 && minute >= 0) {
            boolean pm = hour24 >= 12;
            int h12 = hour24 % 12; if (h12 == 0) h12 = 12;
            String ampm = pm ? "오후" : "오전";
            tvTime.setText(String.format(java.util.Locale.getDefault(), "%s %d:%02d", ampm, h12, minute));
            tvTime.setTextColor(Color.BLACK);
        }
        tvTime.setOnClickListener(v -> showTimePickerDialog(tvTime));
        if (toggleAlarm != null) toggleAlarm.setChecked(enabled);
        timeSlotsContainer.addView(timeSlotView);
    }

    private int getBaseId() {
        return (userItemId >= 0 ? userItemId : (pillId >= 0 ? pillId : (int)(System.currentTimeMillis() & 0xFFFF)));
    }

    private int pillKeyId() {
        if (userItemId >= 0) return userItemId;
        if (pillId >= 0) return 100000 + pillId; // disambiguate
        String k = (name != null ? name : "") + "|" + (code != null ? code : "") + "|" + (imageUrl != null ? imageUrl : "");
        return 200000 + Math.abs(k.hashCode());
    }

    private String prefsFileName() {
        // Separate file per (user, pill)
        String userNs = getUserNamespace().replace(":", "_");
        int pk = pillKeyId();
        return "alarm_prefs_" + userNs + "_k" + pk;
    }

    private String prefsKey() {
        // Single key within file (extendable)
        return "config";
    }

    private String getUserNamespace() {
        try {
            String uid = com.example.medipairing.util.SessionManager.getOrCreateUserId(requireContext());
            if (uid != null && !uid.isEmpty()) return "u:" + uid;
        } catch (Throwable ignored) {}
        return "u:anon";
    }

    private void persistAlarmConfig(boolean[] days, java.util.Date start, java.util.Date end) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("alias", alias);
            root.put("name", name);
            root.put("code", code);
            root.put("image_url", imageUrl);
            org.json.JSONArray jd = new org.json.JSONArray();
            for (int i = 0; i < 7; i++) jd.put(days != null && i < days.length && days[i]);
            root.put("days", jd);
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault());
            String st = String.valueOf(tvStartDate.getText());
            String et = String.valueOf(tvEndDate.getText());
            root.put("start", st);
            root.put("end", et);
            org.json.JSONArray slots = new org.json.JSONArray();
            for (int i = 0; i < timeSlotsContainer.getChildCount(); i++) {
                View child = timeSlotsContainer.getChildAt(i);
                TextView tv = child.findViewById(R.id.tv_time);
                SwitchCompat sw = child.findViewById(R.id.toggle_alarm);
                if (tv == null || sw == null) continue;
                int[] hm = parseHourMinute(String.valueOf(tv.getText()));
                if (hm == null) continue;
                org.json.JSONObject s = new org.json.JSONObject();
                s.put("h", hm[0]); s.put("m", hm[1]); s.put("on", sw.isChecked());
                slots.put(s);
            }
            root.put("slots", slots);
            SharedPreferences sp = requireContext().getSharedPreferences(prefsFileName(), Context.MODE_PRIVATE);
            sp.edit().putString(prefsKey(), root.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private boolean loadAlarmConfig() {
        try {
            SharedPreferences sp = requireContext().getSharedPreferences(prefsFileName(), Context.MODE_PRIVATE);
            String s = sp.getString(prefsKey(), null);
            if (s == null || s.isEmpty()) return false;
            org.json.JSONObject root = new org.json.JSONObject(s);
            // Days
            org.json.JSONArray jd = root.optJSONArray("days");
            if (jd != null) {
                for (int i = 0; i < jd.length() && i < dayOfWeekButtons.size(); i++) {
                    boolean sel = jd.optBoolean(i, false);
                    dayOfWeekButtons.get(i).setSelected(sel);
                }
            }
            // Dates
            String st = root.optString("start", null);
            String et = root.optString("end", null);
            if (st != null && !st.isEmpty()) { tvStartDate.setText(st); tvStartDate.setTextColor(Color.BLACK); }
            if (et != null && !et.isEmpty()) { tvEndDate.setText(et); tvEndDate.setTextColor(Color.BLACK); }
            // Slots
            timeSlotsContainer.removeAllViews();
            org.json.JSONArray slots = root.optJSONArray("slots");
            if (slots != null && slots.length() > 0) {
                for (int i = 0; i < slots.length(); i++) {
                    org.json.JSONObject o = slots.optJSONObject(i);
                    if (o == null) continue;
                    int h = o.optInt("h", -1);
                    int m = o.optInt("m", -1);
                    boolean on = o.optBoolean("on", true);
                    addTimeSlot(h, m, on);
                }
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void showTimePickerDialog(TextView timeView) {
        Calendar c = Calendar.getInstance();
        TimePickerDialog timePickerDialog = new TimePickerDialog(
                getContext(),
                (view, hourOfDay, minute) -> {
                    String amPm = hourOfDay < 12 ? "오전" : "오후";
                    int hour = hourOfDay % 12;
                    if (hour == 0) hour = 12; // Adjust for 12 AM/PM
                    String selectedTime = String.format(Locale.getDefault(), "%s %d:%02d", amPm, hour, minute);
                    timeView.setText(selectedTime);
                    // 시간을 선택하면 해당 슬롯 스위치를 자동으로 ON
                    View parent = (View) timeView.getParent();
                    if (parent != null) {
                        SwitchCompat sw = parent.findViewById(R.id.toggle_alarm);
                        if (sw != null) sw.setChecked(true);
                    }
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                false // Use 12-hour format with AM/PM
        );
        timePickerDialog.show();
    }

    private void ensureDatePlaceholderBlue() {
        try {
            int blue = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.login_bg);
            if (tvStartDate != null) {
                CharSequence s = tvStartDate.getText();
                if (s == null || s.length() == 0 || "입력".contentEquals(s)) {
                    tvStartDate.setText("입력");
                    tvStartDate.setTextColor(blue);
                }
            }
            if (tvEndDate != null) {
                CharSequence e = tvEndDate.getText();
                if (e == null || e.length() == 0 || "입력".contentEquals(e)) {
                    tvEndDate.setText("입력");
                    tvEndDate.setTextColor(blue);
                }
            }
        } catch (Throwable ignored) {}
    }
}
