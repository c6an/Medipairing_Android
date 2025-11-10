package com.example.medipairing.ui.medipairing;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.medipairing.R;

public class MedicinedeeperFragment extends Fragment {
    public static final String ARG_PILL_ID = "pill_id";
    public static final String ARG_USER_ITEM_ID = "user_item_id";
    public static final String ARG_ALIAS = "alias";
    public static final String ARG_NAME = "name";
    public static final String ARG_IMAGE_URL = "image_url";
    public static final String ARG_NOTE = "note";
    public static final String ARG_CODE = "code";
    public static final String ARG_CATEGORY = "category";
    public static final String ARG_EFFICACY = "efficacy";
    public static final String ARG_PRECAUTIONS = "precautions";
    public static final String ARG_STORAGE = "storage";

    // Resolved when fragment opened without user item id
    private int resolvedUserItemId = -1;
    private View loadingScrim; private View progress;
    private volatile boolean miniFetched=false, briefFetched=false;

    public static MedicinedeeperFragment newInstance(int pillId, int userItemId, String alias, String name, String imageUrl, String code, String note, String category,
                                                     String efficacy, String precautions, String storage) {
        MedicinedeeperFragment f = new MedicinedeeperFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_PILL_ID, pillId);
        b.putInt(ARG_USER_ITEM_ID, userItemId);
        b.putString(ARG_ALIAS, alias);
        b.putString(ARG_NAME, name);
        b.putString(ARG_IMAGE_URL, imageUrl);
        b.putString(ARG_CODE, code);
        b.putString(ARG_NOTE, note);
        b.putString(ARG_CATEGORY, category);
        b.putString(ARG_EFFICACY, efficacy);
        b.putString(ARG_PRECAUTIONS, precautions);
        b.putString(ARG_STORAGE, storage);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_medicinedeeper, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView btnReturn = view.findViewById(R.id.btn_return_deeper);
        TextView btnSetAlarm = view.findViewById(R.id.btn_set_alarm);
        TextView btnSave = view.findViewById(R.id.btn_save);
        ImageView iv = view.findViewById(R.id.iv_deeper_pill_image);
        TextView tvTitle = view.findViewById(R.id.tv_deeper_pill_name); // title (alias or category)
        TextView tvSubName = view.findViewById(R.id.tv_deeper_pill_subname); // real pill name
        EditText etAlias = view.findViewById(R.id.et_custom_name);
        TextView tvEffect = view.findViewById(R.id.tv_effect_info);
        TextView tvCaution = view.findViewById(R.id.tv_caution_info);
        TextView tvStorage = view.findViewById(R.id.tv_storage_info);
        loadingScrim = view.findViewById(R.id.loading_scrim_deeper);
        progress = view.findViewById(R.id.progress_deeper);

        Bundle args = getArguments();
        int pillId = args != null ? args.getInt(ARG_PILL_ID, -1) : -1;
        int userItemId = args != null ? args.getInt(ARG_USER_ITEM_ID, -1) : -1;
        String alias = args != null ? args.getString(ARG_ALIAS, "") : "";
        String name = args != null ? args.getString(ARG_NAME, "") : "";
        String imageUrl = args != null ? args.getString(ARG_IMAGE_URL, null) : null;
        String code = args != null ? args.getString(ARG_CODE, null) : null;
        String note = args != null ? args.getString(ARG_NOTE, null) : null;
        String category = args != null ? args.getString(ARG_CATEGORY, null) : null;
        String efficacy = args != null ? args.getString(ARG_EFFICACY, null) : null;
        String precautions = args != null ? args.getString(ARG_PRECAUTIONS, null) : null;
        String storage = args != null ? args.getString(ARG_STORAGE, null) : null;
        // Title is alias if present, otherwise category, otherwise name
        String title = (alias != null && !alias.trim().isEmpty()) ? alias : (category != null && !category.trim().isEmpty() ? category : name);
        tvTitle.setText(title != null ? title : "");
        tvSubName.setText(name != null ? name : "");
        etAlias.setText(alias);
        if (imageUrl != null && !imageUrl.isEmpty()) {
            try { com.bumptech.glide.Glide.with(this).load(imageUrl).into(iv); } catch (Throwable ignored) {}
        }

        btnReturn.setOnClickListener(v -> {
            if (getParentFragmentManager() != null) {
                getParentFragmentManager().popBackStack();
            }
        });

        btnSetAlarm.setOnClickListener(v -> {
            MedicineAlarmFragment f = MedicineAlarmFragment.newInstance(
                    (etAlias.getText()!=null && !etAlias.getText().toString().trim().isEmpty())? etAlias.getText().toString() : alias,
                    name,
                    code,
                    imageUrl,
                    (resolvedUserItemId >= 0 ? resolvedUserItemId : userItemId),
                    pillId
            );
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .addToBackStack(null)
                    .commit();
        });

        // Save button label is always '저장'
        btnSave.setText("저장");


        btnSave.setOnClickListener(v -> {
            if (pillId < 0) return;
            // Capture effectively-final copies for use in inner callbacks
            final int fPillId = pillId;
            final String fCode = code;
            final TextView fTvEffect = tvEffect;
            final TextView fTvCaution = tvCaution;
            final TextView fTvStorage = tvStorage;

            String aliasRaw = etAlias.getText() != null ? etAlias.getText().toString() : null;
            final String aliasParam = (aliasRaw != null && !aliasRaw.trim().isEmpty()) ? aliasRaw : null;
            String noteRaw = note; // could add EditText for note similarly
            final String noteParam = (noteRaw != null && !noteRaw.trim().isEmpty()) ? noteRaw : null;

            String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
            if (jwt == null || jwt.isEmpty()) {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show());
                return;
            }

            // Try PATCH first (existing my-pill). If it fails (e.g., not yet added), fallback to POST add.
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            org.json.JSONObject json = new org.json.JSONObject();
            try {
                if (aliasParam != null) json.put("alias", aliasParam); else json.put("alias", org.json.JSONObject.NULL);
                if (noteParam != null) json.put("note", noteParam); else json.put("note", org.json.JSONObject.NULL);
            } catch (Exception ignored) {}
            okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
            String patchUrl = "http://43.200.178.100:8000/me/pills/" + fPillId;
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(patchUrl).patch(body).header("Authorization","Bearer "+jwt);
            showLoading(true);
            client.newCall(rb.build()).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    // Network failure → attempt add as fallback if possible
                    attemptAddFallback(fPillId, aliasParam, fCode, fTvEffect, fTvCaution, fTvStorage);
                }
                @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                    if (response.isSuccessful()) {
                        if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                            showLoading(false);
                            android.widget.Toast.makeText(getContext(), "수정 완료", android.widget.Toast.LENGTH_SHORT).show();
                            Bundle res = new Bundle(); res.putBoolean("refresh", true);
                            getParentFragmentManager().setFragmentResult("mypage_refresh", res);
                            getParentFragmentManager().popBackStack();
                        });
                    } else {
                        // 404/400 etc → treat as not-existing and add it
                        attemptAddFallback(fPillId, aliasParam, fCode, fTvEffect, fTvCaution, fTvStorage);
                    }
                }
            });
        });

        // Fetch detailed summaries for interaction/warnings/storage when opening from My Page
        // Prefer code lookup; fallback to pillId if necessary (reusing by-imprint endpoint semantics)
        // Prefer values saved with user pill; fall back to network if missing
        if (tvEffect != null && efficacy != null && !efficacy.trim().isEmpty()) tvEffect.setText(efficacy);
        if (tvCaution != null && precautions != null && !precautions.trim().isEmpty()) tvCaution.setText(precautions);
        if (tvStorage != null && storage != null && !storage.trim().isEmpty()) tvStorage.setText(storage);

        boolean needFetch = (efficacy == null || efficacy.trim().isEmpty()) || (precautions == null || precautions.trim().isEmpty()) || (storage == null || storage.trim().isEmpty());
        if (needFetch && code != null && !code.trim().isEmpty()) {
            showLoading(true);
            fetchAndBindSummariesByCode(code, tvEffect, tvCaution, tvStorage, tvTitle, tvSubName, iv, alias);
        } else if (needFetch && pillId >= 0) {
            String pid = String.valueOf(pillId);
            // Fallback: if id_code is not available, try querying by-imprint with numeric id as external_code only if backend supports it.
            showLoading(true);
            fetchAndBindSummariesByCode(pid, tvEffect, tvCaution, tvStorage, tvTitle, tvSubName, iv, alias);
        }
    }

    // No resolve helper — save is PATCH-only for existing items

    // GET /pills/mini
    private void fetchMiniByCode(String code, TextView tvEffect, TextView tvCaution) {
        if (tvEffect == null && tvCaution == null) return;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/mini?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body() == null || getActivity()==null) return;
                String body = response.body().string();
                try {
                    org.json.JSONObject j = new org.json.JSONObject(body);
                    java.util.List<String> eff = jsonArrayToList(j.optJSONArray("effect_lines"));
                    java.util.List<String> prec = jsonArrayToList(j.optJSONArray("precautions_lines"));
                    getActivity().runOnUiThread(() -> {
                        if (tvEffect != null && eff != null && !eff.isEmpty()) tvEffect.setText(joinLines(eff));
                        if (tvCaution != null && prec != null && !prec.isEmpty()) tvCaution.setText(joinLines(prec));
                        miniFetched = true; maybeHideEmptyAndStop(tvEffect, tvCaution, null);
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    // GET /pills/brief
    private void fetchBriefByCode(String code, TextView tvCaution, TextView tvStorage) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/brief?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body() == null || getActivity()==null) return;
                String body = response.body().string();
                try {
                    org.json.JSONObject j = new org.json.JSONObject(body);
                    java.util.List<String> warnings = jsonArrayToList(j.optJSONArray("warnings_lines"));
                    java.util.List<String> storage = jsonArrayToList(j.optJSONArray("storage_lines"));
                    getActivity().runOnUiThread(() -> {
                        if (tvCaution != null && warnings != null && !warnings.isEmpty()) tvCaution.setText(joinLines(warnings));
                        if (tvStorage != null && storage != null && !storage.isEmpty()) tvStorage.setText(joinLines(storage));
                        briefFetched = true; maybeHideEmptyAndStop(null, tvCaution, tvStorage);
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    private void maybeHideEmptyAndStop(TextView tvEffect, TextView tvCaution, TextView tvStorage) {
        View root = getView(); if (root == null) { showLoading(false); return; }
        if (tvEffect != null) {
            TextView label = root.findViewById(R.id.label_effect);
            boolean empty = tvEffect.getText()==null || tvEffect.getText().toString().trim().isEmpty();
            tvEffect.setVisibility(empty?View.GONE:View.VISIBLE);
            if (label != null) label.setVisibility(empty?View.GONE:View.VISIBLE);
        }
        if (tvCaution != null) {
            TextView label = root.findViewById(R.id.label_caution);
            boolean empty = tvCaution.getText()==null || tvCaution.getText().toString().trim().isEmpty();
            tvCaution.setVisibility(empty?View.GONE:View.VISIBLE);
            if (label != null) label.setVisibility(empty?View.GONE:View.VISIBLE);
        }
        if (tvStorage != null) {
            TextView label = root.findViewById(R.id.label_storage);
            boolean empty = tvStorage.getText()==null || tvStorage.getText().toString().trim().isEmpty();
            tvStorage.setVisibility(empty?View.GONE:View.VISIBLE);
            if (label != null) label.setVisibility(empty?View.GONE:View.VISIBLE);
        }
        if (miniFetched && briefFetched) showLoading(false);
    }

    private void showLoading(boolean show) {
        if (loadingScrim != null) loadingScrim.setVisibility(show?View.VISIBLE:View.GONE);
        if (progress != null) progress.setVisibility(show?View.VISIBLE:View.GONE);
    }

    private void attemptAddFallback(int pillId, String aliasNew, String code,
                                    TextView tvEffect, TextView tvCaution, TextView tvStorage) {
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) {
            if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                showLoading(false);
                android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show();
            });
            return;
        }

        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();

        // If we have a code, check interactions before adding
        if (code != null && !code.trim().isEmpty()) {
            String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
            String checkUrl = "http://43.200.178.100:8000/interactions/check-against-user?code=" + enc;
            okhttp3.Request checkReq = new okhttp3.Request.Builder().url(checkUrl).header("Authorization","Bearer "+jwt).build();
            client.newCall(checkReq).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    // On failure, proceed to attempt add anyway (best-effort)
                    postAdd(pillId, aliasNew, tvEffect, tvCaution, tvStorage);
                }
                @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                    boolean canAdd = true;
                    if (response.isSuccessful() && response.body()!=null) {
                        String body = response.body().string();
                        org.json.JSONObject j = safeJson(body);
                        canAdd = (j != null && j.optBoolean("can_add", true));
                    }
                    if (!canAdd) {
                        if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                            showLoading(false);
                            android.widget.Toast.makeText(getContext(), "내 보관함과 복용 금기입니다", android.widget.Toast.LENGTH_SHORT).show();
                            getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment()).commit();
                        });
                    } else {
                        postAdd(pillId, aliasNew, tvEffect, tvCaution, tvStorage);
                    }
                }
            });
        } else {
            // No code, just attempt to add
            postAdd(pillId, aliasNew, tvEffect, tvCaution, tvStorage);
        }
    }

    private void postAdd(int pillId, String aliasNew,
                         TextView tvEffect, TextView tvCaution, TextView tvStorage) {
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) {
            if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                showLoading(false);
                android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show();
            });
            return;
        }

        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        org.json.JSONObject payload = new org.json.JSONObject();
        try {
            payload.put("pill_id", pillId);
            if (aliasNew != null && !aliasNew.trim().isEmpty()) payload.put("alias", aliasNew);
            if (tvEffect != null && tvEffect.getText()!=null) {
                String s = tvEffect.getText().toString().trim(); if (!s.isEmpty()) payload.put("efficacy", s);
            }
            if (tvCaution != null && tvCaution.getText()!=null) {
                String s = tvCaution.getText().toString().trim(); if (!s.isEmpty()) payload.put("precautions", s);
            }
            if (tvStorage != null && tvStorage.getText()!=null) {
                String s = tvStorage.getText().toString().trim(); if (!s.isEmpty()) payload.put("storage", s);
            }
        } catch (Exception ignored) {}
        okhttp3.RequestBody body = okhttp3.RequestBody.create(payload.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url("http://43.200.178.100:8000/me/pills").post(body).header("Authorization","Bearer "+jwt);
        okhttp3.Request req = rb.build();
        okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    showLoading(false);
                    android.widget.Toast.makeText(getContext(), "추가 실패", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    showLoading(false);
                    if (response.isSuccessful()) {
                        android.widget.Toast.makeText(getContext(), "추가되었습니다", android.widget.Toast.LENGTH_SHORT).show();
                        Bundle res = new Bundle(); res.putBoolean("refresh", true);
                        getParentFragmentManager().setFragmentResult("mypage_refresh", res);
                        getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment()).commit();
                    } else if (response.code() == 409) {
                        android.widget.Toast.makeText(getContext(), "이미 존재합니다", android.widget.Toast.LENGTH_SHORT).show();
                        Bundle res = new Bundle(); res.putBoolean("refresh", true);
                        getParentFragmentManager().setFragmentResult("mypage_refresh", res);
                        getParentFragmentManager().popBackStack();
                    } else {
                        android.widget.Toast.makeText(getContext(), "추가 실패("+response.code()+")", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private static org.json.JSONObject safeJson(String s) {
        try { return new org.json.JSONObject(s); } catch (Exception e) { return null; }
    }

    private static java.util.List<String> jsonArrayToList(org.json.JSONArray arr) {
        if (arr == null) return java.util.Collections.emptyList();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static String joinLines(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private void fetchAndBindSummariesByCode(String code,
                                             TextView tvEffect,
                                             TextView tvCaution,
                                             TextView tvStorage,
                                             TextView tvTitle,
                                             TextView tvSubName,
                                             ImageView iv,
                                             String currentAlias) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/by-imprint?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { /* silent */ }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body() == null) return;
                String body = response.body().string();
                org.json.JSONObject json; try { json = new org.json.JSONObject(body); } catch (Exception ex) { json = null; }
                if (json == null) return;
                org.json.JSONObject pill = json.optJSONObject("pill");
                if (pill == null) return;
                String name = pill.optString("name", null);
                String category = pill.optString("category", null);
                String imageUrl = pill.optString("image_url", null);
                String imprint = pill.optString("imprint", null);
                String external = pill.optString("external_code", null);
                String warningsShort = firstNonEmpty(
                        json.optString("warnings_summary_short", null),
                        pill.optString("warnings_summary_short", null),
                        json.optString("warnings_summary", null),
                        pill.optString("warnings_summary", "")
                );
                String storageShort = firstNonEmpty(
                        json.optString("storage_summary_short", null),
                        pill.optString("storage_summary_short", null),
                        json.optString("storage_summary", null),
                        pill.optString("storage_summary", "")
                );
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    // Preserve user alias as title if present; otherwise show category; otherwise name
                    if (tvTitle != null) {
                        if (currentAlias != null && !currentAlias.trim().isEmpty()) tvTitle.setText(currentAlias);
                        else if (category != null && !category.trim().isEmpty()) tvTitle.setText(category);
                        else if (name != null) tvTitle.setText(name);
                    }
                    if (tvSubName != null && name != null) tvSubName.setText(name);
                    if (imageUrl != null && iv != null) try { com.bumptech.glide.Glide.with(MedicinedeeperFragment.this).load(imageUrl).into(iv); } catch (Throwable ignore) {}
                    // Do not set caution/storage here; let /pills/mini and /pills/brief own these fields
                    // Once we know imprint/external_code, call mini/brief with the right code
                    String best = !isEmpty(imprint) ? imprint : (!isEmpty(external) ? external : code);
                    fetchMiniByCode(best, tvEffect, tvCaution);
                    fetchBriefByCode(best, tvCaution, tvStorage);
                });
            }
        });
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private static CharSequence renderHtmlOrFallback(String src, String fallback) {
        if (isEmpty(src)) return fallback;
        String cleaned = src.replace("\u00A0", " ").replace("&nbsp;", " ");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                return android.text.Html.fromHtml(cleaned, android.text.Html.FROM_HTML_MODE_LEGACY);
            } else {
                return android.text.Html.fromHtml(cleaned);
            }
        } catch (Throwable t) {
            return cleaned;
        }
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null) {
                String t = v.trim();
                if (!t.isEmpty() && !"null".equalsIgnoreCase(t)) return t;
            }
        }
        return "";
    }
}
