package com.example.medipairing.ui.medipairing;

import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medipairing.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MedicineUploadFragment extends Fragment {
    private static final String TAG = "MedicineUpload";
    private static final boolean DEBUG_OCR = true;
    private static final int MIN_TOKEN_LEN = 2;
    private static final int MAX_USED_TOKENS = 10;

    private TextView btnPick;
    private TextView btnAddAll;
    private View progress;
    private View loadingScrim;
    private RecyclerView recycler;
    private ImageView imageView;
    private UploadOverlayView overlay;
    private List<Rect> lastBoxes = new ArrayList<>();
    private int lastImgW = 0, lastImgH = 0;
    private java.util.concurrent.ExecutorService uploadExecutor;
    private final ArrayList<PillItem> items = new ArrayList<>();
    private PillAdapter adapter;
    // Dictionary of known Korean medicine names (original and normalized)
    private Set<String> dict = new HashSet<>();
    private Set<String> dictNorm = new HashSet<>();
    private java.util.Map<String, String> normToOriginal = new java.util.HashMap<>();

    private com.google.mlkit.vision.text.TextRecognizer koRecognizer; // reused
    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::onImagePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_medicine_upload, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        btnPick = view.findViewById(R.id.btn_pick_image);
        btnAddAll = view.findViewById(R.id.btn_add_all);
        progress = view.findViewById(R.id.progress);
        loadingScrim = view.findViewById(R.id.loading_scrim);
        recycler = view.findViewById(R.id.recycler_candidates);
        imageView = view.findViewById(R.id.iv_upload_image);
        overlay = view.findViewById(R.id.upload_overlay);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new PillAdapter(items, this::openDeeper);
        recycler.setAdapter(adapter);

        loadDict();
        if (uploadExecutor == null || uploadExecutor.isShutdown()) {
            uploadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        btnPick.setOnClickListener(v -> imagePicker.launch("image/*"));
        if (btnAddAll != null) {
            btnAddAll.setVisibility(View.GONE); // 업로드 화면에서는 일괄 추가 대신 상세로 이동해서 추가하도록 함
        }
        View back = view.findViewById(R.id.btn_return_upload);
        if (back != null) back.setOnClickListener(v -> {
            if (getParentFragmentManager()!=null) getParentFragmentManager().popBackStack();
        });
    }

    private void loadDict() {
        // Prefer korean_word.txt to match the reference strategy; fallback to korean.txt
        String[] candidates = new String[]{"korean_word.txt", "korean.txt"};
        InputStream is = null;
        for (String fn : candidates) {
            try { is = requireContext().getAssets().open(fn); break; } catch (Throwable ignore) {}
        }
        if (is == null) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = safeText(line);
                if (!t.isEmpty()) {
                    dict.add(t);
                    String n = normalizeWhitespaceOnly(t);
                    dictNorm.add(n);
                    normToOriginal.put(n, t);
                }
            }
        } catch (Throwable ignore) {}
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) return;
        showProgress(true);
        if (overlay != null) overlay.clear();
        // Decode and process off the UI thread so spinner can animate
        uploadExecutor.submit(() -> {
            Bitmap bmp = loadBitmapFromUri(uri);
            if (bmp == null) { runOnUi(() -> { showProgress(false); toast("이미지를 불러오지 못했습니다"); }); return; }
            runOnUi(() -> {
                if (imageView != null) imageView.setImageBitmap(bmp);
            });
            // Kick off OCR (async); progress will be hidden when candidates prepared
            runKoreanOcr(bmp);
        });
    }

    private Bitmap loadBitmapFromUri(Uri uri) {
        try {
            ContentResolver cr = requireContext().getContentResolver();
            android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(cr, uri);
            Bitmap full = android.graphics.ImageDecoder.decodeBitmap(src, (decoder, info, src1) -> decoder.setMutableRequired(false));
            int maxSide = 1600;
            float scale = Math.min(1f, maxSide / (float)Math.max(full.getWidth(), full.getHeight()));
            if (scale < 1f) {
                int w = Math.max(1, Math.round(full.getWidth()*scale));
                int h = Math.max(1, Math.round(full.getHeight()*scale));
                return Bitmap.createScaledBitmap(full, w, h, true);
            }
            return full;
        } catch (IOException e) {
            return null;
        }
    }

    private void runKoreanOcr(Bitmap bmp) {
        if (koRecognizer == null) {
            try {
                koRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                        new com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build());
            } catch (Throwable t) { showProgress(false); toast("OCR 초기화 실패"); return; }
        }
        final int[] rotations = new int[]{0, 90, 270};
        runKoreanOcrSequential(bmp, rotations, 0, new ArrayList<>());
    }

    private void runKoreanOcrSequential(Bitmap bmp, int[] rotations, int idx, ArrayList<String> accWords) {
        if (idx >= rotations.length) {
            if (accWords.isEmpty()) toast("사전과 일치하는 단어가 없습니다");
            fetchPillsForWords(accWords);
            return;
        }
        int rot = rotations[idx];
        com.google.mlkit.vision.common.InputImage image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, rot);
        koRecognizer.process(image)
                .addOnSuccessListener(text -> {
                    // Apply stricter dictionary matching with core pattern
                    java.util.Map<String, Rect> matches = findMatches(text);
                    // Only update overlay for base rotation (idx==0) to keep coordinates aligned
                    if (overlay != null && idx == 0) {
                        List<Rect> boxes = new ArrayList<>(matches.values());
                        if (!boxes.isEmpty()) {
                            lastBoxes = boxes;
                            lastImgW = image.getWidth();
                            lastImgH = image.getHeight();
                            overlay.setResults(lastBoxes, lastImgW, lastImgH);
                        } else if (!lastBoxes.isEmpty() && lastImgW > 0 && lastImgH > 0) {
                            // Preserve last good overlay to avoid jump/clear
                            overlay.setResults(lastBoxes, lastImgW, lastImgH);
                        }
                    }
                    List<String> words = new ArrayList<>(matches.keySet());
                    if (words != null && !words.isEmpty()) accWords.addAll(words);
                    runKoreanOcrSequential(bmp, rotations, idx + 1, accWords);
                })
                .addOnFailureListener(e -> runKoreanOcrSequential(bmp, rotations, idx + 1, accWords));
    }

    // Reference-like strict match: use block core pattern and ensure full name match against dictionary entries
    private final java.util.regex.Pattern coreKeywordPattern = java.util.regex.Pattern.compile("^[가-힣]+(?:\\([^)]*\\))?");

    private java.util.Map<String, Rect> findMatches(com.google.mlkit.vision.text.Text visionText) {
        java.util.LinkedHashMap<String, Rect> out = new java.util.LinkedHashMap<>();
        if (visionText == null || dict == null || dict.isEmpty()) return out;
        for (com.google.mlkit.vision.text.Text.TextBlock block : visionText.getTextBlocks()) {
            String cleaned = block.getText() != null ? block.getText().replaceAll("[^가-힣a-zA-Z0-9()]", "") : "";
            if (cleaned.isEmpty()) continue;
            java.util.regex.Matcher m = coreKeywordPattern.matcher(cleaned);
            if (!m.find()) continue;
            String coreOfBlock = m.group();
            String baseBlock = baseHangul(coreOfBlock);
            if (baseBlock.isEmpty()) continue;
            // If next char is Hangul (continuation), skip
            if (cleaned.length() > coreOfBlock.length()) {
                char next = cleaned.charAt(coreOfBlock.length());
                if (String.valueOf(next).matches("^[가-힣]$")) continue;
            }
            // Compare with dictionary cores
            for (String kw : dict) {
                java.util.regex.Matcher km = coreKeywordPattern.matcher(kw);
                if (km.find()) {
                    String coreOfKeyword = km.group();
                    String baseKw = baseHangul(coreOfKeyword);
                    // Rule: compare only hangul prefix before parentheses
                    if (!baseKw.isEmpty() && baseBlock.equals(baseKw)) {
                        Rect bb = block.getBoundingBox();
                        if (bb != null) out.put(kw, bb);
                    }
                }
            }
        }
        return out;
    }

    private static String baseHangul(String s) {
        if (s == null) return "";
        try {
            String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
            // take leading hangul letters up to first non-hangul or '('
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^([가-힣]+)").matcher(n);
            if (mm.find()) return mm.group(1);
            return "";
        } catch (Throwable t) { return ""; }
    }

    private List<String> extractDictWords(com.google.mlkit.vision.text.Text text) {
        // Per-element exact matches, plus 2-gram/3-gram within a line (normalized) exact matches
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (text == null || dictNorm.isEmpty()) return new ArrayList<>();
        for (com.google.mlkit.vision.text.Text.TextBlock b : text.getTextBlocks()) {
            for (com.google.mlkit.vision.text.Text.Line l : b.getLines()) {
                java.util.List<com.google.mlkit.vision.text.Text.Element> es = l.getElements();
                int nEl = (es!=null)? es.size():0;
                if (nEl == 0) continue;
                // single tokens
                for (int i=0;i<nEl;i++) {
                    String raw = safeText(es.get(i).getText());
                    if (raw.length() < 2) continue;
                    String n = normalizeWhitespaceOnly(raw);
                    if (dictNorm.contains(n)) out.add(normToOriginal.getOrDefault(n, raw));
                }
                // 2/3-grams (contiguous) within the same line
                for (int i=0;i<nEl;i++) {
                    String t1 = normalizeWhitespaceOnly(safeText(es.get(i).getText()));
                    if (i+1 < nEl) {
                        String t2 = normalizeWhitespaceOnly(safeText(es.get(i+1).getText()));
                        String bi = t1 + t2;
                        if (bi.length() >= 4 && dictNorm.contains(bi)) out.add(normToOriginal.getOrDefault(bi, safeText(es.get(i).getText())+safeText(es.get(i+1).getText())));
                    }
                    if (i+2 < nEl) {
                        String t2 = normalizeWhitespaceOnly(safeText(es.get(i+1).getText()));
                        String t3 = normalizeWhitespaceOnly(safeText(es.get(i+2).getText()));
                        String tri = t1 + t2 + t3;
                        if (tri.length() >= 5 && dictNorm.contains(tri)) out.add(normToOriginal.getOrDefault(tri, safeText(es.get(i).getText())+safeText(es.get(i+1).getText())+safeText(es.get(i+2).getText())));
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String safeText(String s) { return s == null ? "" : s.trim(); }

    private ArrayList<String> addKoreanTokens(Set<String> sink, String text) {
        String normed = norm(text);
        String[] toks = normed.split("[^가-힣]+");
        ArrayList<String> out = new ArrayList<>();
        for (String t : toks) { if (t != null) { String s = t.trim(); if (s.length() >= 2) { sink.add(s); out.add(s);} } }
        return out;
    }

    private static String norm(String s) {
        try {
            String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
            // Remove whitespace for stable substring matching
            return n.replaceAll("\\s+", "");
        } catch (Throwable t) { return s!=null? s.replaceAll("\\s+", ""):""; }
    }

    // Strict Korean normalization: remove spaces and keep only Hangul and digits
    private static String normKoStrict(String s) {
        if (s == null) return "";
        String n;
        try { n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC); }
        catch (Throwable t) { n = s; }
        n = n.replaceAll("\\s", "");
        n = n.replaceAll("[^가-힣0-9]", "");
        return n;
    }

    // Only remove whitespace (for per-element comparison to dictionary)
    private static String normalizeWhitespaceOnly(String s) {
        if (s == null) return "";
        try {
            String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
            return n.replaceAll("\\s", "");
        } catch (Throwable t) { return s.replaceAll("\\s", ""); }
    }

    

    private static final java.util.Set<String> STOPWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            // 매우 일반적인 단어/문장 어미
            "있습니다","합니다","입니다","하였다","하세요","가능","불가",
            // 안내/문서 공통어
            "복용","주의","보관","저장","사용","주의사항","경고","약","의약품","제품","정보","개요","안내","첨부문서",
            // 제형/포장 등 일반명
            "정","캡슐","시럽","연질","연질캡슐","서방정","정제","정품"
    ));

    private void fetchPillsForWords(List<String> words) {
        if (words == null || words.isEmpty()) { showProgress(false); toast("인식된 단어가 없습니다"); return; }
        // Filter out generic words; keep length >= 2 and hangul/digits only
        // Deduplicate tokens while preserving order
        java.util.LinkedHashSet<String> filteredSet = new java.util.LinkedHashSet<>();
        for (String w : words) {
            if (w == null) continue;
            String t = w.trim();
            if (t.length() < 2) continue;
            // 허용: 한글/숫자/괄호 — 샘플 사전 형식을 그대로 인정
            if (!t.matches("[가-힣0-9()]+")) continue;
            if (STOPWORDS.contains(t)) continue;
            filteredSet.add(t);
        }
        ArrayList<String> filtered = new ArrayList<>(filteredSet);
        if (filtered.isEmpty()) { showProgress(false); toast("사전 매칭이 너무 일반적입니다"); return; }
        // Prefer longer tokens; limit to top 10 to reduce load
        java.util.Collections.sort(filtered, (a,b)-> Integer.compare(b.length(), a.length()));
        if (filtered.size() > 10) filtered = new ArrayList<>(filtered.subList(0,10));
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { showProgress(false); toast("로그인이 필요합니다"); return; }
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        Map<Integer, PillItem> acc = new HashMap<>();

        final int total = filtered.size();
        final int[] done = {0};
        for (String w : filtered) {
            final String token = w;
            final String tokenNorm = normKoStrict(token);
            // 코어 단어(괄호 앞) 추출 — 서버 검색 폴백용
            String tokenCoreTmp = token;
            java.util.regex.Matcher km = coreKeywordPattern.matcher(token);
            if (km.find()) tokenCoreTmp = km.group();
            final String tokenCore = tokenCoreTmp;
            String enc; try { enc = java.net.URLEncoder.encode(token, "UTF-8"); } catch (Exception e) { enc = token; }
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url("http://43.200.178.100:8000/search/pills/by_name?strict=true&name="+enc);
            rb.header("Authorization","Bearer "+jwt);
            client.newCall(rb.build()).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { step(); }
                @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                    final int before = acc.size();
                    if (response.isSuccessful() && response.body()!=null) {
                        String body = response.body().string();
                        try {
                            org.json.JSONArray arr = new org.json.JSONArray(body);
                            if (arr.length() == 0) {
                                // Fallback: 코어 단어로 한 번 더 시도
                                if (tokenCore != null && !tokenCore.equals(token)) {
                                    String enc2; try { enc2 = java.net.URLEncoder.encode(tokenCore, "UTF-8"); } catch (Exception e) { enc2 = tokenCore; }
                                    okhttp3.Request.Builder rb2 = new okhttp3.Request.Builder().url("http://43.200.178.100:8000/search/pills/by_name?strict=true&name="+enc2);
                                    rb2.header("Authorization","Bearer "+jwt);
                                    client.newCall(rb2.build()).enqueue(new okhttp3.Callback() {
                                        @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { step(); }
                                        @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                                            if (response.isSuccessful() && response.body()!=null) {
                                                String body = response.body().string();
                                                try {
                                                    org.json.JSONArray arr = new org.json.JSONArray(body);
                                                    for (int i=0;i<arr.length();i++) {
                                                        org.json.JSONObject o = arr.getJSONObject(i);
                                                        String name = o.optString("name", null);
                                                        if (name == null) continue;
                                                        String cleaned = name.replaceAll("[^가-힣a-zA-Z0-9()]", "");
                                                        java.util.regex.Matcher nm = coreKeywordPattern.matcher(cleaned);
                                                        String nameCore = null; if (nm.find()) nameCore = nm.group();
                                                        if (nameCore == null) continue;
                                                        if (!normKoStrict(nameCore).equals(normKoStrict(tokenCore))) continue;
                                                        int id = o.optInt("id", -1);
                                                        String category = o.optString("category", null);
                                                        String imageUrl = o.optString("image_url", null);
                                                        String imprint = o.optString("imprint", null);
                                                        String externalCode = o.optString("external_code", null);
                                                        if (id>0) acc.put(id, new PillItem(id, name, category, imageUrl, imprint, externalCode));
                                                    }
                                                } catch (Exception ignore) {}
                                            }
                                            step();
                                        }
                                    });
                                    return;
                                } else {
                                    step();
                                    return;
                                }
                            }
                            // Verify actual pill name using the same core pattern rule
                            for (int i=0;i<arr.length();i++) {
                                org.json.JSONObject o = arr.getJSONObject(i);
                                String name = o.optString("name", null);
                                if (name == null) continue;
                                String cleaned = name.replaceAll("[^가-힣a-zA-Z0-9()]", "");
                                java.util.regex.Matcher nm = coreKeywordPattern.matcher(cleaned);
                                String nameCore = null;
                                if (nm.find()) nameCore = nm.group();
                                if (nameCore == null) continue;
                                // Compare base hangul (before parentheses) equality
                                if (!baseHangul(nameCore).equals(baseHangul(tokenCore))) continue;
                                int id = o.optInt("id", -1);
                                String category = o.optString("category", null);
                                String imageUrl = o.optString("image_url", null);
                                String imprint = o.optString("imprint", null);
                                String externalCode = o.optString("external_code", null);
                                if (id>0) acc.put(id, new PillItem(id, name, category, imageUrl, imprint, externalCode));
                            }
                        } catch (Exception ignore) {}
                    } else { step(); return; }
                    step();
                }
                private void step(){
                    synchronized (done) {
                        done[0]++;
                        if (done[0] >= total) {
                            requireActivity().runOnUiThread(() -> {
                                items.clear();
                                items.addAll(acc.values());
                                adapter.notifyDataSetChanged();
                                showProgress(false);
                                if (items.isEmpty()) { toast("검색된 약이 없습니다"); }
                            });
                        }
                    }
                }
            });
        }
    }

    private void confirmAdd(PillItem item) {
        // Deprecated: 업로드 화면에서 바로 추가하지 않고 상세 화면으로 이동하여 추가하도록 변경
        openDeeper(item);
    }

    private void openDeeper(PillItem item) {
        String alias = (item.category != null && !item.category.trim().isEmpty()) ? item.category : item.name;
        String code = item.bestCode();
        Fragment f = MedicinedeeperFragment.newInstance(
                item.id,
                -1,
                alias,
                item.name,
                item.imageUrl,
                code,
                null,
                item.category,
                "",
                "",
                ""
        );
        if (getParentFragmentManager()!=null) {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .addToBackStack(null)
                    .commit();
        }
    }

    private void addPill(PillItem item) {
        String code = item.bestCode();
        if (code == null || code.trim().isEmpty()) { toast("단어가 인식되지 않았습니다"); return; }
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { toast("로그인이 필요합니다"); return; }
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/interactions/check-against-user?code=" + enc;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).header("Authorization","Bearer "+jwt).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { runOnUi(() -> toast("네트워크 오류")); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body()==null) { runOnUi(() -> toast("서버 오류")); return; }
                String body = response.body().string();
                org.json.JSONObject j = safeJson(body);
                boolean canAdd = j != null && j.optBoolean("can_add", true);
                if (!canAdd) {
                    runOnUi(() -> {
                        toast("내 보관함과 복용 금기입니다");
                        getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment()).commit();
                    });
                } else {
                    proceedAddAfterEnrich(item, code, true);
                }
            }
        });
    }

    private void proceedAddAfterEnrich(PillItem item, String code, boolean navigateOnSuccess) {
        showProgress(true);
        fetchMiniForCode(code, mini -> {
            final String eff = mini[0];
            final String prec = mini[1];
            fetchBriefForCode(code, stor -> {
                final String storage = stor[0];
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                org.json.JSONObject json = new org.json.JSONObject();
                try {
                    json.put("pill_id", item.id);
                    json.put("alias", item.category!=null?item.category:item.name);
                    if (eff!=null && !eff.trim().isEmpty()) json.put("efficacy", eff);
                    if (prec!=null && !prec.trim().isEmpty()) json.put("precautions", prec);
                    if (storage!=null && !storage.trim().isEmpty()) json.put("storage", storage);
                } catch (Exception ignore) {}
                okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
                okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url("http://43.200.178.100:8000/me/pills").post(body);
                String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
                if (jwt == null || jwt.isEmpty()) { runOnUi(() -> { showProgress(false); toast("로그인이 필요합니다"); }); return; }
                rb.header("Authorization","Bearer "+jwt);
                client.newCall(rb.build()).enqueue(new okhttp3.Callback() {
                    @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { runOnUi(() -> { showProgress(false); toast("추가 실패"); }); }
                    @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                        runOnUi(() -> {
                            showProgress(false);
                            if (response.isSuccessful()) {
                                toast("추가되었습니다");
                                if (navigateOnSuccess) {
                                    getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment()).commit();
                                }
                            } else if (response.code()==409) {
                                toast("이미 존재합니다");
                            } else {
                                toast("추가 실패("+response.code()+")");
                            }
                        });
                    }
                });
            });
        });
    }

    private void addAllRecognized() {
        if (items.isEmpty()) { toast("추가할 후보가 없습니다"); return; }
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { toast("로그인이 필요합니다"); return; }
        showProgress(true);
        // Sequentially process to avoid overloading and to preserve order
        final int total = items.size();
        final int[] idx = {0};
        final int[] ok = {0};
        final int[] skipped = {0}; // contraindicated
        final int[] exists = {0};
        final int[] fail = {0};

        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (idx[0] >= total) {
                showProgress(false);
                String summary = "성공:"+ok[0]+" 건, 금기:"+skipped[0]+" 건, 존재:"+exists[0]+" 건, 실패:"+fail[0]+" 건";
                toast(summary);
                if (ok[0] > 0) {
                    getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new com.example.medipairing.ui.mypage.MyPageFragment()).commit();
                }
                return;
            };
            PillItem it = items.get(idx[0]++);
            String code = it.bestCode();
            if (code == null || code.trim().isEmpty()) { fail[0]++; step[0].run(); return; }
            String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
            String url = "http://43.200.178.100:8000/interactions/check-against-user?code=" + enc;
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder().url(url).header("Authorization","Bearer "+jwt).build();
            client.newCall(req).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { fail[0]++; runOnUi(step[0]); }
                @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                    boolean canAdd = false;
                    if (response.isSuccessful() && response.body()!=null) {
                        String body = response.body().string();
                        org.json.JSONObject j = safeJson(body);
                        canAdd = (j != null && j.optBoolean("can_add", true));
                    }
                    if (!canAdd) { skipped[0]++; runOnUi(step[0]); return; }
                    // proceed add without navigation
                    proceedAddAfterEnrich(it, code, false);
                    ok[0]++; runOnUi(step[0]);
                }
            });
        };
        step[0].run();
    }

    private void fetchMiniForCode(String code, java.util.function.Consumer<String[]> cb) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/mini?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { cb.accept(new String[]{"",""}); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                String effStr = ""; String precStr = "";
                if (response.isSuccessful() && response.body()!=null) {
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(response.body().string());
                        java.util.List<String> eff = jsonArrayToList(j.optJSONArray("effect_lines"));
                        java.util.List<String> prec = jsonArrayToList(j.optJSONArray("precautions_lines"));
                        if (eff!=null && !eff.isEmpty()) effStr = joinLines(eff);
                        if (prec!=null && !prec.isEmpty()) precStr = joinLines(prec);
                    } catch (Exception ignore) {}
                }
                cb.accept(new String[]{effStr, precStr});
            }
        });
    }

    private void fetchBriefForCode(String code, java.util.function.Consumer<String[]> cb) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/brief?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { cb.accept(new String[]{""}); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                String storStr = "";
                if (response.isSuccessful() && response.body()!=null) {
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(response.body().string());
                        java.util.List<String> stor = jsonArrayToList(j.optJSONArray("storage_lines"));
                        if (stor!=null && !stor.isEmpty()) storStr = joinLines(stor);
                    } catch (Exception ignore) {}
                }
                cb.accept(new String[]{storStr});
            }
        });
    }

    private static java.util.List<String> jsonArrayToList(org.json.JSONArray arr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (arr == null) return out;
        for (int i=0;i<arr.length();i++) {
            String s = arr.optString(i, null);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static org.json.JSONObject safeJson(String s) {
        try { return new org.json.JSONObject(s); } catch (Exception e) { return null; }
    }

    private static String joinLines(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<lines.size();i++) { if (i>0) sb.append('\n'); sb.append(lines.get(i)); }
        return sb.toString();
    }

    private void showProgress(boolean show) {
        if (progress!=null) progress.setVisibility(show?View.VISIBLE:View.GONE);
        if (loadingScrim!=null) loadingScrim.setVisibility(show?View.VISIBLE:View.GONE);
        if (show) {
            if (loadingScrim != null) loadingScrim.bringToFront();
            if (progress != null) progress.bringToFront();
        }
        if (btnPick!=null) btnPick.setEnabled(!show);
        if (btnAddAll!=null) btnAddAll.setEnabled(!show);
        if (recycler!=null) recycler.setAlpha(show?0.5f:1f);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (uploadExecutor != null) { uploadExecutor.shutdownNow(); uploadExecutor = null; }
    }
    private void toast(String s) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), s, android.widget.Toast.LENGTH_SHORT).show()); }
    private void runOnUi(Runnable r) { if (getActivity()!=null) getActivity().runOnUiThread(r); }

    private void showMatchedWords(List<String> words) {
        if (getActivity()==null) return;
        String msg = String.join("\n", words);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("매칭된 단어 ("+words.size()+")")
                .setMessage(msg)
                .setPositiveButton("확인", null)
                .show();
    }

    private static class PillItem {
        final int id; final String name; final String category; final String imageUrl; final String imprint; final String externalCode;
        PillItem(int id, String name, String category, String imageUrl, String imprint, String externalCode){ this.id=id; this.name=name; this.category=category; this.imageUrl=imageUrl; this.imprint=imprint; this.externalCode=externalCode; }
        String bestCode(){ if (externalCode!=null && externalCode.trim().matches("\\d+")) return externalCode.trim(); return imprint!=null?imprint.trim():null; }
    }

    private static class PillAdapter extends RecyclerView.Adapter<PillAdapter.VH> {
        private final List<PillItem> data; private final java.util.function.Consumer<PillItem> onAdd;
        PillAdapter(List<PillItem> data, java.util.function.Consumer<PillItem> onAdd){ this.data=data; this.onAdd=onAdd; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            PillItem p = data.get(i);
            h.title.setText(p.category!=null?p.category:p.name);
            h.subtitle.setText(p.name);
            if (p.imageUrl!=null && !p.imageUrl.isEmpty()) { try { com.bumptech.glide.Glide.with(h.itemView).load(p.imageUrl).into(h.image);} catch(Throwable t){ h.image.setImageResource(R.mipmap.ic_launcher);} } else { h.image.setImageResource(R.mipmap.ic_launcher);} 
            h.itemView.setOnClickListener(v -> onAdd.accept(p));
        }
        @Override public int getItemCount(){ return data.size(); }
        static class VH extends RecyclerView.ViewHolder { TextView title, subtitle; ImageView image; VH(View v){ super(v); title=v.findViewById(R.id.tv_search_result_title); subtitle=v.findViewById(R.id.tv_search_result_subtitle); image=v.findViewById(R.id.iv_search_result_image);} }
    }
}
