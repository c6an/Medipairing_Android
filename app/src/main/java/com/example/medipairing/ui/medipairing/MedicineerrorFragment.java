package com.example.medipairing.ui.medipairing;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.medipairing.R;

public class MedicineerrorFragment extends Fragment {
    public static final String ARG_NAME = "name";
    public static final String ARG_IMAGE_URL = "image_url";
    public static final String ARG_SUMMARY = "summary";
    public static final String ARG_ALT_NAME = "alt_name";
    public static final String ARG_ALT_IMAGE_URL = "alt_image_url";
    public static final String ARG_CODE = "code";

    public static final String ARG_CATEGORY = "category";
    private static final int COLOR_RED = 0xFFCB1C00;
    private static final int COLOR_ORANGE = 0xFFFF8800;
    private static final int COLOR_GRAY = 0xFF666666;
    private ImageView ivAltRef;

    public static MedicineerrorFragment newInstance(String name, String imageUrl, String category, String altName, String altImageUrl, String code) {
        MedicineerrorFragment f = new MedicineerrorFragment();
        Bundle b = new Bundle();
        b.putString(ARG_NAME, name);
        b.putString(ARG_IMAGE_URL, imageUrl);
        b.putString(ARG_CATEGORY, category);
        b.putString(ARG_ALT_NAME, altName);
        b.putString(ARG_ALT_IMAGE_URL, altImageUrl);
        b.putString(ARG_CODE, code);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_medicineerror, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView btnReturn = view.findViewById(R.id.btn_return_error);
        ImageView ivPill = view.findViewById(R.id.iv_error_pill_image);
        TextView tvName = view.findViewById(R.id.tv_error_pill_name);
        TextView tvUsage = view.findViewById(R.id.tv_error_pill_usage);
        TextView tvAlt = view.findViewById(R.id.label_substitute);
        ImageView ivAlt = view.findViewById(R.id.iv_substitute_image);
        ivAltRef = ivAlt;
        android.widget.LinearLayout conflictsContainer = view.findViewById(R.id.conflicts_container);

        Bundle args = getArguments();
        if (args != null) {
            String category = args.getString(ARG_CATEGORY, "");
            String imageUrl = args.getString(ARG_IMAGE_URL, null);
            String summary = args.getString(ARG_NAME, "");
            String altName = args.getString(ARG_ALT_NAME, null);
            String altImageUrl = args.getString(ARG_ALT_IMAGE_URL, null);
            String code = args.getString(ARG_CODE, null);
            tvName.setText(category);
            tvUsage.setText(summary == null || summary.isEmpty() ? "사용 정보 없음" : summary);
            if (imageUrl != null && !imageUrl.isEmpty()) {
                try { com.bumptech.glide.Glide.with(this).load(imageUrl).into(ivPill); } catch (Throwable ignored) {}
            }
            if (altName != null && !altName.isEmpty()) {
                tvAlt.setText("대체제: " + altName);
            }
            if (altImageUrl != null && !altImageUrl.isEmpty()) {
                try { com.bumptech.glide.Glide.with(this).load(altImageUrl).into(ivAlt); } catch (Throwable ignored) {}
            }

            // Fetch and render conflicts lists
            if (code != null && !code.trim().isEmpty()) {
                fetchAndRenderConflicts(code, conflictsContainer);
            }
        }

        btnReturn.setOnClickListener(v -> {
            if (getParentFragmentManager() != null) {
                getParentFragmentManager().popBackStack();
            }
        });
    }

    private void fetchAndRenderConflicts(String code, android.widget.LinearLayout container) {
        if (container == null || getContext() == null) return;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        final String encF = enc; // for use inside inner classes

        // 1) Product-level summary
        String urlSummary = "http://43.200.178.100:8000/interactions/for-pill?code=" + encF;
        okhttp3.Request reqSummary = new okhttp3.Request.Builder().url(urlSummary).build();
        client.newCall(reqSummary).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { /* ignore */ }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                java.util.List<ConflictRow> productRows = new java.util.ArrayList<>();
                if (response.isSuccessful() && response.body()!=null) {
                    String body = response.body().string();
                    org.json.JSONObject j = safeJson(body);
                    org.json.JSONArray arr = j != null ? j.optJSONArray("conflicts") : null;
                    if (arr != null) {
                        for (int i=0;i<arr.length();i++) {
                            org.json.JSONObject c = arr.optJSONObject(i);
                            if (c == null) continue;
                            int pid = c.optInt("pill_id", -1);
                            String pname = c.optString("pill_name", "");
                            String sev = c.optString("highest_severity", null);
                            productRows.add(new ConflictRow(pid, pname, sev, false));
                        }
                    }
                }

                // 2) User-specific conflicts (mark my_pill=true)
                String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
                if (jwt == null || jwt.isEmpty()) {
                    // No user info: render product rows only
                    if (getActivity()!=null) getActivity().runOnUiThread(() -> renderConflictRows(container, productRows));
                    return;
                }
                String urlUser = "http://43.200.178.100:8000/interactions/check-against-user?code=" + encF;
                okhttp3.Request reqUser = new okhttp3.Request.Builder().url(urlUser).header("Authorization","Bearer "+jwt).build();
                client.newCall(reqUser).enqueue(new okhttp3.Callback() {
                    @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                        // On failure to fetch user-specific conflicts, do not show full product list to avoid confusion.
                        if (getActivity()!=null) getActivity().runOnUiThread(() -> renderConflictRows(container, java.util.Collections.emptyList()));
                    }
                    @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                        java.util.HashSet<Integer> myIds = new java.util.HashSet<>();
                        java.util.List<ConflictRow> userRows = new java.util.ArrayList<>();
                        if (response.isSuccessful() && response.body()!=null) {
                            String body = response.body().string();
                            org.json.JSONObject j = safeJson(body);
                            org.json.JSONArray arr = j != null ? j.optJSONArray("conflicts") : null;
                            if (arr != null) {
                                for (int i=0;i<arr.length();i++) {
                                    org.json.JSONObject c = arr.optJSONObject(i);
                                    if (c == null) continue;
                                    int pid = c.optInt("pill_id", -1);
                                    String pname = c.optString("pill_name", "");
                                    String sev = c.optString("highest_severity", null);
                                    myIds.add(pid);
                                    userRows.add(new ConflictRow(pid, pname, sev, true));
                                }
                            }
                        }
                        // Show ONLY my-pills conflicts and set image from my-pills list
                        if (userRows.isEmpty()) {
                            if (getActivity()!=null) getActivity().runOnUiThread(() -> renderConflictRows(container, userRows));
                            return;
                        }
                        // Fetch my pills to resolve image_url for conflicting pill(s)
                        okhttp3.Request reqMine = new okhttp3.Request.Builder()
                                .url("http://43.200.178.100:8000/me/pills")
                                .header("Authorization","Bearer "+jwt)
                                .build();
                        client.newCall(reqMine).enqueue(new okhttp3.Callback() {
                            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                                if (getActivity()!=null) getActivity().runOnUiThread(() -> renderConflictRows(container, userRows));
                            }
                            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                                String imageUrl = null;
                                if (response.isSuccessful() && response.body()!=null) {
                                    String b = response.body().string();
                                    org.json.JSONArray arr;
                                    try { arr = new org.json.JSONArray(b); } catch (Exception ex) { arr = null; }
                                    if (arr != null) {
                                        // Prefer first contraindicated row's image; else first row
                                        int targetId = -1;
                                        for (ConflictRow r : userRows) { if ("contraindicated".equalsIgnoreCase(String.valueOf(r.severity))) { targetId = r.pillId; break; } }
                                        if (targetId < 0 && !userRows.isEmpty()) targetId = userRows.get(0).pillId;
                                        for (int i=0;i<arr.length();i++) {
                                            org.json.JSONObject it = arr.optJSONObject(i);
                                            if (it == null) continue;
                                            int id = it.optInt("pill_id", -1);
                                            if (id < 0) {
                                                org.json.JSONObject p = it.optJSONObject("pill");
                                                if (p != null) id = p.optInt("id", -1);
                                            }
                                            if (id == targetId) {
                                                org.json.JSONObject p = it.optJSONObject("pill");
                                                if (p != null) imageUrl = p.optString("image_url", null);
                                                break;
                                            }
                                        }
                                    }
                                }
                                final String imgF = imageUrl;
                                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                                    if (ivAltRef != null && imgF != null && !imgF.trim().isEmpty()) {
                                        try { com.bumptech.glide.Glide.with(MedicineerrorFragment.this).load(imgF).into(ivAltRef); } catch (Throwable ignore) {}
                                    }
                                    renderConflictRows(container, userRows);
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    private void renderConflictRows(android.widget.LinearLayout container, java.util.List<ConflictRow> rows) {
        if (container == null) return;
        container.removeAllViews();
        if (rows == null || rows.isEmpty()) {
            android.widget.TextView tv = new android.widget.TextView(getContext());
            tv.setText("등록된 금기/주의 정보가 없습니다");
            tv.setTextColor(COLOR_GRAY);
            container.addView(tv);
            return;
        }
        for (ConflictRow r : rows) {
            android.widget.TextView tv = new android.widget.TextView(getContext());
            String badge = r.myPill ? " [내 보관함]" : "";
            String sev = (r.severity != null) ? r.severity : "unknown";
            tv.setText(r.pillName + " — " + sev + badge);
            tv.setTextSize(14f);
            int color = "contraindicated".equalsIgnoreCase(sev) ? COLOR_RED : COLOR_ORANGE;
            tv.setTextColor(color);
            container.addView(tv);
        }
    }

    private static class ConflictRow {
        final int pillId; final String pillName; final String severity; final boolean myPill;
        ConflictRow(int id, String name, String sev, boolean my) { this.pillId=id; this.pillName=name; this.severity=sev; this.myPill=my; }
    }

    private static org.json.JSONObject safeJson(String s) {
        try { return new org.json.JSONObject(s); } catch (Exception e) { return null; }
    }
}
