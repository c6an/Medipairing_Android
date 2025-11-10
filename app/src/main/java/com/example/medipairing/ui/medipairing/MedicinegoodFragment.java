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

public class MedicinegoodFragment extends Fragment {
    public static final String ARG_NAME = "name";
    public static final String ARG_IMAGE_URL = "image_url";
    public static final String ARG_SUMMARY = "summary";
    public static final String ARG_CODE = "code";
    public static final String ARG_PILL_ID = "pill_id";
    public static final String ARG_INTERACTIONS = "interactions_short";
    public static final String ARG_WARNINGS = "warnings_short";
    public static final String ARG_STORAGE = "storage_short";

    public static final String ARG_CATEGORY = "category";
    public static MedicinegoodFragment newInstance(String name, String imageUrl, String category, String code, int pillId,
                                                   String interactionsShort, String warningsShort, String storageShort) {
        MedicinegoodFragment f = new MedicinegoodFragment();
        Bundle b = new Bundle();
        b.putString(ARG_NAME, name);
        b.putString(ARG_IMAGE_URL, imageUrl);
        b.putString(ARG_CATEGORY, category);
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
        return inflater.inflate(R.layout.fragment_medicinegood, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView btnReturn = view.findViewById(R.id.btn_return_good);
        ImageView ivPill = view.findViewById(R.id.iv_good_pill_image);
        TextView tvName = view.findViewById(R.id.tv_good_pill_name);
        TextView tvUsage = view.findViewById(R.id.tv_good_pill_usage);
        TextView btnMoreInfo = view.findViewById(R.id.btn_good_more_info);
        TextView btnAddToMyPage = view.findViewById(R.id.btn_good_add_to_mypage);
        final boolean[] miniDone = new boolean[]{false};
        final boolean[] briefDone = new boolean[]{false};
        final String[] effectStr = new String[]{""};
        final String[] precautionsStr = new String[]{""};
        final String[] storageStr = new String[]{""};

        Bundle args = getArguments();
        if (args != null) {
            String category = args.getString(ARG_CATEGORY, "");
            String imageUrl = args.getString(ARG_IMAGE_URL, null);
            String name = args.getString(ARG_NAME, "");
            tvName.setText(category);
            tvUsage.setText(name == null || name.isEmpty() ? "사용 정보 없음" : name);
            if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(this).load(imageUrl).into(ivPill);
                } catch (Throwable ignored) {}
            }
        }

        btnReturn.setOnClickListener(v -> {
            if (getParentFragmentManager() != null) {
                getParentFragmentManager().popBackStack();
            }
        });

        btnMoreInfo.setOnClickListener(v -> {
            if (getParentFragmentManager() != null) {
                String name = getArguments() != null ? getArguments().getString(ARG_NAME, "") : "";
                String imageUrl = getArguments() != null ? getArguments().getString(ARG_IMAGE_URL, null) : null;
                String summary = getArguments() != null ? getArguments().getString(ARG_SUMMARY, "") : "";
                String code = getArguments() != null ? getArguments().getString(ARG_CODE, "") : "";
                int pillId = getArguments() != null ? getArguments().getInt(ARG_PILL_ID, -1) : -1;
                String interactionsShort = getArguments() != null ? getArguments().getString(ARG_INTERACTIONS, "") : "";
                String warningsShort = getArguments() != null ? getArguments().getString(ARG_WARNINGS, "") : "";
                String storageShort = getArguments() != null ? getArguments().getString(ARG_STORAGE, "") : "";
                Fragment info = MedicineinfoFragment.newInstance(name, imageUrl, code, pillId,
                        interactionsShort, warningsShort, storageShort);
                getParentFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, info)
                        .addToBackStack(null)
                        .commit();
            }
        });

        // Hide add button entirely when logged out
        if (!com.example.medipairing.util.SessionManager.isLoggedIn(getContext())) {
            btnAddToMyPage.setVisibility(View.GONE);
        }

        // Start fetching mini/brief for note; disable button until ready
        String code = getArguments() != null ? getArguments().getString(ARG_CODE, "") : "";
        int pillId = getArguments() != null ? getArguments().getInt(ARG_PILL_ID, -1) : -1;
        btnAddToMyPage.setEnabled(false);
        btnAddToMyPage.setAlpha(0.6f);
        btnAddToMyPage.setText("로딩 중...");
        if (code != null && !code.trim().isEmpty()) {
            fetchMini(code, s -> { effectStr[0]=s[0]; precautionsStr[0]=s[1]; miniDone[0]=true; maybeEnable(btnAddToMyPage, miniDone[0], briefDone[0]); });
            fetchBrief(code, s -> { storageStr[0]=s[0]; briefDone[0]=true; maybeEnable(btnAddToMyPage, miniDone[0], briefDone[0]); });
        } else if (pillId >= 0) {
            String pid = String.valueOf(pillId);
            fetchMini(pid, s -> { effectStr[0]=s[0]; precautionsStr[0]=s[1]; miniDone[0]=true; maybeEnable(btnAddToMyPage, miniDone[0], briefDone[0]); });
            fetchBrief(pid, s -> { storageStr[0]=s[0]; briefDone[0]=true; maybeEnable(btnAddToMyPage, miniDone[0], briefDone[0]); });
        }

        btnAddToMyPage.setOnClickListener(v -> {
            if (!com.example.medipairing.util.SessionManager.isLoggedIn(getContext())) {
                android.widget.Toast.makeText(getContext(), "로그인 후 이용해주세요", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Bundle _args = getArguments();
            if (_args == null) return;
            int pillId2 = _args.getInt(ARG_PILL_ID, -1);
            String name = _args.getString(ARG_NAME, "");
            String category = _args.getString(ARG_CATEGORY, null);
            if (!(miniDone[0] && briefDone[0])) {
                android.widget.Toast.makeText(getContext(), "정보 수집 중입니다. 잠시만 기다려주세요", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (pillId2 < 0) {
                android.widget.Toast.makeText(getContext(), "추가 실패: pill_id 없음", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            String bestCode = _args.getString(ARG_CODE, null);
            if (bestCode == null || bestCode.trim().isEmpty()) bestCode = String.valueOf(pillId2);
            // Use category as alias (title), fallback to name when category is empty
            String alias = (category != null && !category.trim().isEmpty()) ? category : name;
            precheckAndAdd(bestCode, pillId2, alias, effectStr[0], precautionsStr[0], storageStr[0]);
        });
    }

    private void precheckAndAdd(String code, int pillId, String aliasName, String eff, String prec, String stor) {
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/interactions/check-against-user?code=" + enc;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url);
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show()); return; }
        rb.header("Authorization","Bearer "+jwt);
        client.newCall(rb.build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "네트워크 오류", android.widget.Toast.LENGTH_SHORT).show()); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                boolean canAdd = true;
                if (response.isSuccessful() && response.body()!=null) {
                    String b = response.body().string();
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(b);
                        if (j.has("can_add")) {
                            canAdd = j.optBoolean("can_add", true);
                        } else {
                            // Fallback: block only when highest_severity == contraindicated
                            String sev = j.optString("highest_severity", null);
                            canAdd = (sev == null || !"contraindicated".equalsIgnoreCase(sev));
                        }
                    } catch (Exception ignore) {}
                }
                if (!canAdd) {
                    if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                        android.widget.Toast.makeText(getContext(), "내 보관함에 있는 알약과 같이 복용할 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show();
                        // Navigate back to MyPage
                        getParentFragmentManager().beginTransaction()
                                .replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment())
                                .commit();
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
            if (eff != null && !eff.trim().isEmpty()) json.put("efficacy", eff);
            if (prec != null && !prec.trim().isEmpty()) json.put("precautions", prec);
            if (stor != null && !stor.trim().isEmpty()) json.put("storage", stor);
        } catch (Exception ignored) {}
        okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url("http://43.200.178.100:8000/me/pills").post(body);
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt != null && !jwt.isEmpty()) rb.header("Authorization", "Bearer " + jwt);
        client.newCall(rb.build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "추가 실패", android.widget.Toast.LENGTH_SHORT).show()); }
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
                });
            }
        });
    }

    private interface TwoStrCallback { void onResult(String[] arr); }

    private void maybeEnable(TextView btn, boolean mini, boolean brief) {
        if (btn == null) return;
        if (mini && brief) {
            // Ensure UI updates on main thread always
            btn.post(() -> {
                btn.setEnabled(true);
                btn.setAlpha(1f);
                btn.setText("약 등록하기");
            });
        }
    }

    private void fetchMini(String code, TwoStrCallback cb) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/mini?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { cb.onResult(new String[]{"",""}); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                String effStr = ""; String precStr = "";
                if (response.isSuccessful() && response.body()!=null) {
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(response.body().string());
                        java.util.List<String> eff = sanitizeLines(jsonArrayToList(j.optJSONArray("effect_lines")), "effect");
                        java.util.List<String> prec = sanitizeLines(jsonArrayToList(j.optJSONArray("precautions_lines")), "warnings");
                        if (eff!=null && !eff.isEmpty()) effStr = joinLines(eff);
                        if (prec!=null && !prec.isEmpty()) precStr = joinLines(prec);
                    } catch (Exception ignored) {}
                }
                cb.onResult(new String[]{effStr, precStr});
            }
        });
    }

    private void fetchBrief(String code, java.util.function.Consumer<String[]> cb) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/brief?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { cb.accept(new String[]{""}); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                String storage = "";
                if (response.isSuccessful() && response.body()!=null) {
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(response.body().string());
                        java.util.List<String> stor = sanitizeLines(jsonArrayToList(j.optJSONArray("storage_lines")), "storage");
                        if (stor.isEmpty()) stor = sanitizeLines(jsonArrayToList(j.optJSONArray("storage_liens")), "storage");
                        if (stor!=null && !stor.isEmpty()) storage = joinLines(stor);
                    } catch (Exception ignored) {}
                }
                cb.accept(new String[]{storage});
            }
        });
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
        String[] storageKeep = new String[]{"보관", "저장", "밀폐", "건조", "실온", "냉장", "습기", "직사광선"}; // not enforced
        for (String s : lines) {
            if (s == null) continue;
            String t = s.replace('\n',' ').replaceAll("\\s+"," ").trim();
            if (t.isEmpty()) continue;
            boolean bad = false;
            for (String b : ban) { if (t.contains(b)) { bad = true; break; } }
            if (bad) continue;
            // Do not require storage keywords; accept provided storage_lines after ban filtering
            out.add(t);
        }
        if (out.size() > 3) return new java.util.ArrayList<>(out.subList(0,3));
        return out;
    }
}
