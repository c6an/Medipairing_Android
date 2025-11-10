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

public class MedicineinfoFragment extends Fragment {
    public static final String ARG_NAME = "name";
    public static final String ARG_IMAGE_URL = "image_url";
    public static final String ARG_CODE = "code";
    public static final String ARG_PILL_ID = "pill_id";
    public static final String ARG_INTERACTIONS = "interactions_short";
    public static final String ARG_WARNINGS = "warnings_short";
    public static final String ARG_STORAGE = "storage_short";

    public static final String ARG_CATEGORY = "category";

    // State for mini/brief gating and note assembly
    private TextView btnAddRef;
    private volatile boolean miniDone = false;
    private volatile boolean briefDone = false;
    private volatile String effectLinesStr = "";
    private volatile String precautionsLinesStr = "";
    private volatile String storageLinesStr = "";
    private volatile String bestCodeForInfo = null; // resolved imprint/external or pillId
    private View loadingScrim; private View progress;

    public static MedicineinfoFragment newInstance(String name, String imageUrl, String code, int pillId,
                                                   String interactionsShort, String warningsShort, String storageShort) {
        MedicineinfoFragment f = new MedicineinfoFragment();
        Bundle b = new Bundle();
        b.putString(ARG_NAME, name);
        b.putString(ARG_IMAGE_URL, imageUrl);
        b.putString(ARG_CODE, code);
        b.putInt(ARG_PILL_ID, pillId);
        b.putString(ARG_INTERACTIONS, interactionsShort);
        b.putString(ARG_WARNINGS, warningsShort);
        b.putString(ARG_STORAGE, storageShort);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_medicineinfo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView btnReturn = view.findViewById(R.id.btn_return_info);
        TextView btnAddMedicine = view.findViewById(R.id.btn_add_medicine_info);
        btnAddRef = btnAddMedicine;
        ImageView iv = view.findViewById(R.id.iv_info_pill_image);
        TextView tvName = view.findViewById(R.id.tv_info_pill_name); // category title
        TextView tvSubName = view.findViewById(R.id.tv_info_pill_subname); // real pill name
        TextView tvEffect = view.findViewById(R.id.tv_info_effect_info);
        TextView tvInteraction = null; // optional section not present in layout
        TextView tvCaution = view.findViewById(R.id.tv_info_caution_info);
        TextView tvStorage = view.findViewById(R.id.tv_info_storage_info);
        loadingScrim = view.findViewById(R.id.loading_scrim_info);
        progress = view.findViewById(R.id.progress_info);

        btnReturn.setOnClickListener(v -> {
            if (getParentFragmentManager() != null) {
                getParentFragmentManager().popBackStack();
            }
        });

        Bundle args = getArguments();
        if (args != null) {
            String name = args.getString(ARG_NAME, "");
            String imageUrl = args.getString(ARG_IMAGE_URL, null);
            String code = args.getString(ARG_CODE, "");
            int pillId = args.getInt(ARG_PILL_ID, -1);
            String interactionsShort = args.getString(ARG_INTERACTIONS, "");
            String warningsShort = args.getString(ARG_WARNINGS, "");
            String storageShort = args.getString(ARG_STORAGE, "");
            String category = args.getString(ARG_CATEGORY, "");

            tvName.setText(category);
            // Initially, show pill name as subtitle; category will be filled after fetch
            tvSubName.setText(name);
            if (tvEffect != null) tvEffect.setText("");
            // Let /pills/mini and /pills/brief populate these sections
            tvCaution.setText("");
            tvStorage.setText("");
            if (imageUrl != null && !imageUrl.isEmpty()) {
                try { com.bumptech.glide.Glide.with(this).load(imageUrl).into(iv); } catch (Throwable ignored) {}
            }
            // Hide add button entirely when logged out
            if (!com.example.medipairing.util.SessionManager.isLoggedIn(getContext())) {
                btnAddMedicine.setVisibility(View.GONE);
            }

            // Disable add until mini+brief fetched
            btnAddMedicine.setEnabled(false);
            btnAddMedicine.setAlpha(0.6f);
            btnAddMedicine.setText("로딩 중...");

            btnAddMedicine.setOnClickListener(v -> {
                if (!com.example.medipairing.util.SessionManager.isLoggedIn(getContext())) {
                    android.widget.Toast.makeText(getContext(), "로그인 후 이용해주세요", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!(miniDone && briefDone)) {
                    android.widget.Toast.makeText(getContext(), "정보 수집 중입니다. 잠시만 기다려주세요", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pillId < 0) {
                    android.widget.Toast.makeText(getContext(), "추가 실패: pill_id 없음", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String codeForCheck = bestCodeForInfo != null ? bestCodeForInfo : (code!=null && !code.trim().isEmpty()? code : String.valueOf(pillId));
                precheckAndAdd(codeForCheck, pillId, (category!=null?category:""), effectLinesStr, precautionsLinesStr, storageLinesStr);
            });

            // 보완: 상세 요약을 재조회하여 효과/용법/주의/보관을 각각 채움
            String codeTrim = (code!=null)?code.trim():"";
            showLoading(true);
            if (!codeTrim.isEmpty() && !"null".equalsIgnoreCase(codeTrim)) {
                // Resolve proper imprint/external_code via by-imprint then fetch mini/brief with that code
                fetchAndBindByCode(codeTrim, tvName, tvSubName, iv, tvEffect, tvCaution, tvStorage);
            } else if (name != null && !name.trim().isEmpty()) {
                // Fallback: find pill by strict name, then continue with its imprint/external
                fetchAndBindByNameStrict(name, tvName, tvSubName, iv, tvEffect, tvCaution, tvStorage);
            } else if (pillId >= 0) {
                // Last resort: try by-imprint using pillId string (server may accept)
                String pid = String.valueOf(pillId);
                fetchAndBindByCode(pid, tvName, tvSubName, iv, tvEffect, tvCaution, tvStorage);
            }
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private static org.json.JSONObject safeJson(String s) {
        try { return new org.json.JSONObject(s); } catch (Exception e) { return null; }
    }

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

    private void fetchAndBindByCode(String code,
                                    TextView tvName,
                                    TextView tvSubName,
                                    ImageView iv,
                                    TextView tvEffect,
                                    TextView tvCaution,
                                    TextView tvStorage) {
        // Guard: avoid meaningless requests like code=null or empty
        if (code == null) return;
        String codeTrim = code.trim();
        if (codeTrim.isEmpty() || "null".equalsIgnoreCase(codeTrim)) return;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(codeTrim, "UTF-8"); } catch (Exception e) { enc = codeTrim; }
        String url = "http://43.200.178.100:8000/pills/by-imprint?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { }
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
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (category != null) tvName.setText(category);
                    if (name != null) tvSubName.setText(name);
                    if (imageUrl != null) try { com.bumptech.glide.Glide.with(MedicineinfoFragment.this).load(imageUrl).into(iv); } catch (Throwable ignore) {}
                    // After we know imprint/external_code, call mini/brief with a reliable code
                String best = (!isEmpty(imprint) && !"null".equalsIgnoreCase(imprint)) ? imprint :
                               ((!isEmpty(external) && !"null".equalsIgnoreCase(external)) ? external : code);
                if (!isEmpty(best) && !"null".equalsIgnoreCase(best)) {
                    bestCodeForInfo = best;
                    fetchMiniByCode(best, tvEffect, tvCaution);
                    fetchBriefByCode(best, tvCaution, tvStorage);
                } else {
                    // Avoid null code requests; enable add to prevent infinite loading
                    miniDone = true; briefDone = true; maybeEnableAdd();
                }
                });
            }
        });
    }

    private void fetchAndBindByNameStrict(String name,
                                           TextView tvName,
                                           TextView tvSubName,
                                           ImageView iv,
                                           TextView tvEffect,
                                           TextView tvCaution,
                                           TextView tvStorage) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(name, "UTF-8"); } catch (Exception e) { enc = name; }
        String url = "http://43.200.178.100:8000/search/pills/by_name?strict=true&name=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body() == null) { if (getActivity()!=null) getActivity().runOnUiThread(() -> { miniDone = true; briefDone = true; maybeEnableAdd(); }); return; }
                String body = response.body().string();
                org.json.JSONArray arr;
                try { arr = new org.json.JSONArray(body); } catch (Exception ex) { arr = null; }
                if (arr == null || arr.length() == 0) { if (getActivity()!=null) getActivity().runOnUiThread(() -> { miniDone = true; briefDone = true; maybeEnableAdd(); }); return; }
                org.json.JSONObject o = arr.optJSONObject(0);
                if (o == null) return;
                String category = o.optString("category", null);
                String pillName = o.optString("name", null);
                String imageUrl = o.optString("image_url", null);
                String imprint = o.optString("imprint", null);
                String external = o.optString("external_code", null);
                String best = (!isEmpty(imprint) && !"null".equalsIgnoreCase(imprint)) ? imprint :
                               ((!isEmpty(external) && !"null".equalsIgnoreCase(external)) ? external : null);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (category != null) tvName.setText(category);
                    if (pillName != null) tvSubName.setText(pillName);
                    if (imageUrl != null) { try { com.bumptech.glide.Glide.with(MedicineinfoFragment.this).load(imageUrl).into(iv);} catch (Throwable ignore) {} }
                    if (!isEmpty(best) && !"null".equalsIgnoreCase(best)) {
                        bestCodeForInfo = best;
                        fetchMiniByCode(best, tvEffect, tvCaution);
                        fetchBriefByCode(best, tvCaution, tvStorage);
                    } else {
                        // No usable code: enable add to avoid stuck loading
                        miniDone = true; briefDone = true; maybeEnableAdd();
                    }
                });
            }
        });
    }

    private void precheckAndAdd(String code, int pillId, String aliasName, String eff, String prec, String stor) {
        showLoading(true);
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) {
            if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show());
            showLoading(false);
            return;
        }
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/interactions/check-against-user?code=" + enc;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).header("Authorization","Bearer "+jwt).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "네트워크 오류", android.widget.Toast.LENGTH_SHORT).show()); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body()==null) { if (getActivity()!=null) getActivity().runOnUiThread(() -> { android.widget.Toast.makeText(getContext(), "서버 오류", android.widget.Toast.LENGTH_SHORT).show(); showLoading(false);} ); return; }
                String body = response.body().string();
                org.json.JSONObject j = safeJson(body);
                boolean canAdd = j != null && j.optBoolean("can_add", true);
                if (!canAdd) {
                    if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                        android.widget.Toast.makeText(getContext(), "내 보관함과 복용 금기입니다", android.widget.Toast.LENGTH_SHORT).show();
                        getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment()).commit();
                        showLoading(false);
                    });
                } else {
                    doAddWithFields(pillId, aliasName, eff, prec, stor);
                }
            }
        });
    }

    private void doAddWithFields(int pillId, String aliasName, String eff, String prec, String stor) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("pill_id", pillId);
            json.put("alias", aliasName != null ? aliasName : "");
            if (!isEmpty(eff)) json.put("efficacy", eff);
            if (!isEmpty(prec)) json.put("precautions", prec);
            if (!isEmpty(stor)) json.put("storage", stor);
        } catch (Exception ignored) {}
        okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show()); return; }
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url("http://43.200.178.100:8000/me/pills").post(body).header("Authorization","Bearer "+jwt);
        client.newCall(rb.build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "추가 실패", android.widget.Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        android.widget.Toast.makeText(getContext(), "약 추가하기", android.widget.Toast.LENGTH_SHORT).show();
                        getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment()).commit();
                    } else if (response.code() == 409) {
                        android.widget.Toast.makeText(getContext(), "이미 존재하는 약입니다", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        android.widget.Toast.makeText(getContext(), "추가 실패("+response.code()+")", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    showLoading(false);
                });
            }
        });
    }

    private void showLoading(boolean show) {
        if (loadingScrim != null) loadingScrim.setVisibility(show?View.VISIBLE:View.GONE);
        if (progress != null) progress.setVisibility(show?View.VISIBLE:View.GONE);
        if (btnAddRef != null) btnAddRef.setEnabled(!show);
    }

    // Mini: effect + precautions 2–3 lines
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
                    java.util.List<String> eff = sanitizeLines(jsonArrayToList(j.optJSONArray("effect_lines")), "effect");
                    java.util.List<String> prec = sanitizeLines(jsonArrayToList(j.optJSONArray("precautions_lines")), "warnings");
                    getActivity().runOnUiThread(() -> {
                        if (tvEffect != null && eff != null && !eff.isEmpty()) {
                            effectLinesStr = joinLines(eff);
                            tvEffect.setText(effectLinesStr);
                        }
                        if (tvCaution != null && prec != null && !prec.isEmpty()) {
                            precautionsLinesStr = joinLines(prec);
                            tvCaution.setText(precautionsLinesStr);
                        }
                        miniDone = true;
                        maybeEnableAdd();
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    // Brief: warnings + storage + interactions 2–3 lines
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
                    java.util.List<String> warningsTmp = sanitizeLines(jsonArrayToList(j.optJSONArray("warnings_lines")), "warnings");
                    java.util.List<String> storageTmp = sanitizeLines(jsonArrayToList(j.optJSONArray("storage_lines")), "storage");
                    if (storageTmp.isEmpty()) {
                        // handle misspelled key
                        storageTmp = sanitizeLines(jsonArrayToList(j.optJSONArray("storage_liens")), "storage");
                    }
                    final java.util.List<String> warningsF = warningsTmp;
                    final java.util.List<String> storageF = storageTmp;
                    getActivity().runOnUiThread(() -> {
                        if (tvCaution != null && warningsF != null && !warningsF.isEmpty()) {
                            precautionsLinesStr = joinLines(warningsF);
                            tvCaution.setText(precautionsLinesStr);
                        }
                        if (tvStorage != null && storageF != null && !storageF.isEmpty()) {
                            storageLinesStr = joinLines(storageF);
                            tvStorage.setText(storageLinesStr);
                        }
                        briefDone = true;
                        maybeEnableAdd();
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    private void maybeEnableAdd() {
        if (btnAddRef == null) return;
        if (miniDone && briefDone) {
            btnAddRef.setEnabled(true);
            btnAddRef.setAlpha(1f);
            btnAddRef.setText("약 추가 하기");
            // Hide empty sections
            View root = getView();
            if (root != null) {
                TextView eff = root.findViewById(R.id.tv_info_effect_info);
                TextView caut = root.findViewById(R.id.tv_info_caution_info);
                TextView stor = root.findViewById(R.id.tv_info_storage_info);
                View lEff = root.findViewById(R.id.label_info_effect);
                View lCau = root.findViewById(R.id.label_info_caution);
                View lSto = root.findViewById(R.id.label_info_storage);
                if (eff != null) {
                    boolean empty = eff.getText()==null || eff.getText().toString().trim().isEmpty();
                    eff.setVisibility(empty?View.GONE:View.VISIBLE);
                    if (lEff!=null) lEff.setVisibility(empty?View.GONE:View.VISIBLE);
                }
                if (caut != null) {
                    boolean empty = caut.getText()==null || caut.getText().toString().trim().isEmpty();
                    caut.setVisibility(empty?View.GONE:View.VISIBLE);
                    if (lCau!=null) lCau.setVisibility(empty?View.GONE:View.VISIBLE);
                }
                if (stor != null) {
                    boolean empty = stor.getText()==null || stor.getText().toString().trim().isEmpty();
                    stor.setVisibility(empty?View.GONE:View.VISIBLE);
                    if (lSto!=null) lSto.setVisibility(empty?View.GONE:View.VISIBLE);
                }
            }
            showLoading(false);
        }
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

    private static java.util.List<String> sanitizeLines(java.util.List<String> lines, String kind) {
        if (lines == null) return java.util.Collections.emptyList();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String[] ban = new String[]{
                "의약품통합정보시스템", "의약품제품정보", "전체메뉴닫기", "첨부문서", "제조업체/제조소",
                "생동성", "원료약품", "ATC코드", "DUR", "포장정보", "보험", "재심사", "RMP",
                "top", "이전", "닫" };
        String[] storageKeep = new String[]{"보관", "저장", "밀폐", "건조", "실온", "냉장", "습기", "직사광선"}; // no longer enforced
        for (String s : lines) {
            if (s == null) continue;
            String t = s.replace('\n',' ').replaceAll("\\s+"," ").trim();
            if (t.isEmpty()) continue;
            boolean bad = false;
            for (String b : ban) { if (t.contains(b)) { bad = true; break; } }
            if (bad) continue;
            // Do not enforce storage keywords; trust API storage_lines as-is (only ban page chrome)
            out.add(t);
        }
        // Limit to 3 lines as UI summary
        if (out.size() > 3) return new java.util.ArrayList<>(out.subList(0,3));
        return out;
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
