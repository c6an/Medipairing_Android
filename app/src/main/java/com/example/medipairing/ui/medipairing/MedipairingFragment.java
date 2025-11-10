package com.example.medipairing.ui.medipairing;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Camera;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.example.medipairing.R;
import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Size;
import android.graphics.RectF;
import android.graphics.Rect;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MedipairingFragment extends Fragment {
    private static final String MODEL_FILE = "best_float16.tflite"; // float16 asset model
    private static final boolean SHOW_OVERLAYS = true; // hide all boxes/overlays

    private PreviewView previewView;
    private TextView infoBar;
    private PillBoxOverlayView overlayView;
    private Toolbar toolbar;
    private TextView toolbarTitle;
    private View progressScan;
    private PreviewView.ScaleType previewScaleType; // Store ScaleType here

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private Interpreter tflite;
    private Interpreter eastTflite;
    private int inputWidth = 0, inputHeight = 0, inputChannels = 3;
    private DataType inputType = DataType.FLOAT32;
    private List<String> labels;
    private int eastW = 0, eastH = 0; // EAST input size

    // ML Kit OCR
    private com.google.mlkit.vision.text.TextRecognizer textRecognizer; // Latin (imprint)
    private com.google.mlkit.vision.text.TextRecognizer koreanRecognizer; // Korean fallback for names
    private final java.util.Set<String> koDictNorm = new java.util.HashSet<>();
    private final java.util.Map<String,String> koNormToOrig = new java.util.HashMap<>();

    // Concurrency/teardown guards to avoid TFLite native crashes
    private final Object tfliteLock = new Object();
    private volatile boolean isTearingDown = false;
    private ImageAnalysis analysisUseCase;
    private boolean torchEnabled = false;
    private float linearZoom = 0f; // 0..1
    private boolean userZooming = false;

    // Stabilization fields
    private final java.util.ArrayDeque<RectF> boxHistory = new java.util.ArrayDeque<>();
    private static final int BOX_HISTORY_SIZE = 6; // relax: fewer frames needed
    private static final float BOX_STABLE_IOU = 0.60f; // relax stability threshold
    private static final float BOX_AREA_JITTER = 0.30f; // allow more jitter
    private static final long OCR_MIN_INTERVAL_MS = 250L; // run OCR more often
    private long lastOcrMs = 0L;
    private OcrCodeAggregator ocrAggregator = new OcrCodeAggregator(8, 0.60f, 400L);
    private String lastStableCode = null;
    private volatile String currentOcrHint = null; // 최근 OCR 후보 텍스트

    // Preprocessing config (adjust to your training)
    private static final boolean NORMALIZE_NEG_ONE_TO_ONE = false; // true if model trained with mean=0.5 std=0.5
    private static final boolean USE_LETTERBOX_RESIZE = false; // match reference (square resize, no letterbox)
    private static final boolean APPLY_SIGMOID_TO_SCORES = false; // YOLO11 tflite exports often include activation
    private static final boolean HAS_OBJECTNESS = false; // many YOLO tflite exports drop obj; use class score only
    private static final boolean CLASS_AGNOSTIC_NMS = false; // set true to suppress across classes
    private static final float SCORE_THRESHOLD = 0.25f; // align with reference defaults
    private static final float IOU_THRESHOLD = 0.45f;
    private static final int MAX_DETECTIONS = 30;
    private static final float MIN_BOX_SIDE_FRAC = 0.05f; // ignore tiny boxes (<5% of min side)

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) startCamera();
            });

    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_medipairing, container, false);
        previewView = view.findViewById(R.id.preview_view);
        infoBar = view.findViewById(R.id.info_bar);
        overlayView = view.findViewById(R.id.box_overlay);
        toolbar = view.findViewById(R.id.toolbar);
        toolbarTitle = view.findViewById(R.id.toolbar_title);
        progressScan = view.findViewById(R.id.progress_scan);
        View back = view.findViewById(R.id.btn_return);
        if (back != null) back.setOnClickListener(v -> {
            if (getParentFragmentManager()!=null) getParentFragmentManager().popBackStack();
        });

        // Ensure overlays visible as before
        if (overlayView != null && SHOW_OVERLAYS) overlayView.setVisibility(View.VISIBLE);
        // 초기에는 흰색 테마
        setScanBackground(false);
        // Cache current preview scale type for overlay mapping
        try { previewScaleType = previewView.getScaleType(); } catch (Throwable ignore) { previewScaleType = PreviewView.ScaleType.FILL_CENTER; }
        // Initial state text
        if (infoBar != null) infoBar.setText("초기화 중...");
        return view;
    }

    @Override public void onStart() {
        super.onStart();
        // 복귀 시 스캔 재개를 위해 teardown 플래그 해제
        isTearingDown = false;
        if (cameraExecutor == null || cameraExecutor.isShutdown()) {
            cameraExecutor = Executors.newSingleThreadExecutor();
        }
        currentOcrHint = null;
        ensurePermissionAndStart();
    }

    @Override public void onResume() {
        super.onResume();
        // 뒤로가기 복귀 시에도 즉시 스캔 가능하도록 보장
        isTearingDown = false;
        if (cameraProvider == null || analysisUseCase == null) {
            ensurePermissionAndStart();
        }
        if (overlayView != null && SHOW_OVERLAYS) overlayView.setVisibility(View.VISIBLE);
        currentOcrHint = null;
        if (infoBar != null) updateInfo("약을 인식시켜 주세요");
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        isTearingDown = true;
        if (analysisUseCase != null) {
            try { analysisUseCase.clearAnalyzer(); } catch (Throwable ignored) {}
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            cameraExecutor = null;
        }
        synchronized (tfliteLock) {
            if (tflite != null) {
                try { tflite.close(); } catch (Throwable ignored) {}
                tflite = null;
            }
        }
        if (eastTflite != null) {
            eastTflite.close();
            eastTflite = null;
        }
        if (textRecognizer != null) {
            try { textRecognizer.close(); } catch (Throwable ignore) {}
            textRecognizer = null;
        }
        boxHistory.clear();
        ocrAggregator.reset();
        lastStableCode = null;
        previewView = null;
        infoBar = null;
    }

    private void ensurePermissionAndStart() {
        if (getContext() == null) return;
        boolean granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void initInterpreter() {
        if (getContext() == null) return;
        if (tflite != null) return;

        try {
            java.nio.ByteBuffer modelBuffer = loadModelFromAssets(MODEL_FILE);
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(4);
            // XNNPACK generally gives best CPU performance
            try { opts.setUseXNNPACK(true); } catch (Throwable ignored) {}
            try { opts.setAllowFp16PrecisionForFp32(true); } catch (Throwable ignored) {}
            // Disable NNAPI for stability during rapid lifecycle changes

            tflite = new Interpreter(modelBuffer, opts);

            int[] inShape = tflite.getInputTensor(0).shape();
            // Robustly infer [1,H,W,C] vs [1,C,H,W] vs [H,W,C]
            if (inShape.length == 4) {
                if (inShape[3] == 3) {
                    // NHWC
                    inputHeight = inShape[1];
                    inputWidth = inShape[2];
                    inputChannels = inShape[3];
                } else if (inShape[1] == 3) {
                    // NCHW (rare in TFLite); we still feed NHWC buffer but dims are [1,3,H,W]
                    inputHeight = inShape[2];
                    inputWidth = inShape[3];
                    inputChannels = inShape[1];
                } else {
                    // Fallback guess
                    inputHeight = inShape[1];
                    inputWidth = inShape[2];
                    inputChannels = Math.max(inShape[3], 3);
                }
            } else if (inShape.length == 3) {
                inputHeight = inShape[0];
                inputWidth = inShape[1];
                inputChannels = inShape[2];
            }
            inputType = tflite.getInputTensor(0).dataType();

            labels = tryLoadLabels();
            if (SHOW_OVERLAYS && overlayView != null && getActivity() != null) {
                getActivity().runOnUiThread(() -> overlayView.setLabels(labels));
            }

            // Do not update info bar here; keep initial '초기화 중...' until scanning
        } catch (Exception e) {
            // Suppress model-load status in the info bar per latest UX
            // Optionally log or handle silently
        }
    }

    private void startCamera() {
        if (getContext() == null || getActivity() == null) return;

        initInterpreter();
        initEastInterpreter();
        initMlKitOcr();

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(getContext());
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindUseCases();
                // 바인딩 직후 초기 안내 갱신
                if (infoBar != null) updateInfo("약을 인식시켜 주세요");
                setScanBackground(false);
            } catch (ExecutionException | InterruptedException e) {
                updateInfo("카메라 초기화 실패: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    private void initEastInterpreter() {
        if (getContext() == null) return;
        if (eastTflite != null) return;
        try {
            // Optional: place your EAST model under assets/models/east_text_detection.tflite
            java.nio.ByteBuffer model = loadModelFromAssets("models/east_text_detection.tflite");
            Interpreter.Options opts = new Interpreter.Options();
            try { opts.setUseXNNPACK(true); } catch (Throwable ignored) {}
            opts.setNumThreads(4);
            eastTflite = new Interpreter(model, opts);
            int[] inShape = eastTflite.getInputTensor(0).shape(); // [1,H,W,3]
            if (inShape.length == 4) {
                // Prefer NHWC
                if (inShape[3] == 3) { eastH = inShape[1]; eastW = inShape[2]; }
                else if (inShape[1] == 3) { eastH = inShape[2]; eastW = inShape[3]; }
            }
        } catch (Exception e) {
            // Model is optional; if missing, leave eastTflite null
            eastTflite = null;
        }
    }

    private void initMlKitOcr() {
        // Primary: Latin (영문/숫자 각인 코드)
        try {
            textRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
            );
        } catch (Throwable t) {
            textRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
            );
        }
        // Fallback: Korean recognizer for 사전 기반 이름 매칭
        try {
            koreanRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    new com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build()
            );
        } catch (Throwable ignored) { koreanRecognizer = null; }
        loadKoreanDict();
    }

    private void runMlKitOcrOnRoi(Bitmap src, RectF roiRect) {
        if (textRecognizer == null) return;
        int left = Math.max(0, Math.round(roiRect.left));
        int top = Math.max(0, Math.round(roiRect.top));
        int width = Math.max(1, Math.round(roiRect.width()));
        int height = Math.max(1, Math.round(roiRect.height()));
        if (left + width > src.getWidth()) width = src.getWidth() - left;
        if (top + height > src.getHeight()) height = src.getHeight() - top;

        Bitmap roi = Bitmap.createBitmap(src, left, top, width, height);
        roi = scaleUpIfSmall(roi, 1024);

        java.util.List<Bitmap> variants = new java.util.ArrayList<>();
        try {
            variants.add(roi);
            Bitmap gray = ensureGrayscale(roi);
            variants.add(unsharp(gray, 1.0f));
            variants.add(sobelMagnitude(gray));
            variants.add(whiteTopHat(gray));
            variants.add(blackTopHat(gray));
            variants.add(otsuThreshold(gray));
            variants.add(adaptiveMeanThreshold(gray, 17, 7));
        } catch (Throwable t) {
            variants.clear();
            variants.add(roi);
        }

        for (Bitmap variant : variants) {
            com.google.mlkit.vision.common.InputImage image = com.google.mlkit.vision.common.InputImage.fromBitmap(variant, 0);
            textRecognizer.process(image)
                    .addOnSuccessListener(result -> {
                        java.util.ArrayList<RectF> rects = new java.util.ArrayList<>();
                        java.util.ArrayList<Token> tokens = new java.util.ArrayList<>();
                        for (com.google.mlkit.vision.text.Text.TextBlock block : result.getTextBlocks()) {
                            for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                                Rect bb = line.getBoundingBox();
                                if (bb != null) {
                                    RectF fr = new RectF(bb);
                                    fr.offset(left, top);
                                    rects.add(fr);
                                }
                                for (com.google.mlkit.vision.text.Text.Element el : line.getElements()) {
                                    String tx = normalizeAndPickCode(el.getText());
                                    Rect eb = el.getBoundingBox();
                                    if (tx != null && !tx.isEmpty() && eb != null) {
                                        RectF erf = new RectF(eb);
                                        erf.offset(left, top);
                                        tokens.add(new Token(tx, erf));
                                    }
                                }
                            }
                        }
                        String candidate = composeCodeFromTokens(tokens);
                        boolean hadCandidate = !candidate.isEmpty();
                        if (hadCandidate) currentOcrHint = candidate;
                        if (hadCandidate) {
                            ocrAggregator.add(candidate);
                            String stable = ocrAggregator.getStable();
                            if (stable != null && !stable.equals(lastStableCode)) {
                                lastStableCode = stable;
                                maybeRequestForStableCode();
                            }
                        }
                        if (!hadCandidate && koreanRecognizer != null && !koDictNorm.isEmpty()) {
                            koreanRecognizer.process(image)
                                    .addOnSuccessListener(ko -> {
                                        java.util.ArrayList<RectF> matchRects = new java.util.ArrayList<>();
                                        for (com.google.mlkit.vision.text.Text.TextBlock b : ko.getTextBlocks()) {
                                            for (com.google.mlkit.vision.text.Text.Line l : b.getLines()) {
                                                for (com.google.mlkit.vision.text.Text.Element e : l.getElements()) {
                                                    String raw = e.getText(); if (raw == null) continue;
                                                    String n = normalizeWhitespaceOnly(raw);
                                                    if (koDictNorm.contains(n)) {
                                                        Rect eb = e.getBoundingBox();
                                                        if (eb != null) { RectF erf = new RectF(eb); erf.offset(left, top); matchRects.add(erf); }
                                                    }
                                                }
                                            }
                                        }
                                        if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                                            if (SHOW_OVERLAYS && overlayView != null) {
                                                overlayView.setPreviewParams(src.getWidth(), src.getHeight(), previewScaleType != null ? previewScaleType : PreviewView.ScaleType.FILL_CENTER);
                                                overlayView.setTextRegions(matchRects);
                                            }
                                            updateInfo("검색 중...");
                                        });
                                    })
                                    .addOnFailureListener(e -> {
                                        if (getActivity()!=null) getActivity().runOnUiThread(() -> updateInfo("검색 중..."));
                                    });
                        } else {
                            if (getActivity() != null) {
                                final int imgW = src.getWidth();
                                final int imgH = src.getHeight();
                                getActivity().runOnUiThread(() -> {
                                    if (SHOW_OVERLAYS && overlayView != null) {
                                        overlayView.setPreviewParams(imgW, imgH, previewScaleType != null ? previewScaleType : PreviewView.ScaleType.FILL_CENTER);
                                        overlayView.setTextRegions(rects);
                                    }
                                    updateInfo("검색 중...");
                                });
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> updateInfo("OCR 실패: " + e.getMessage()));
                        }
                    });
        }
    }

    // Load Korean dictionary (normalized: NFC + remove whitespace)
    private void loadKoreanDict() {
        if (getContext() == null) return;
        String[] files = new String[]{"korean_word.txt", "korean.txt"};
        for (String fn : files) {
            try (java.io.InputStream is = getContext().getAssets().open(fn);
                 java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        String n = normalizeWhitespaceOnly(t);
                        koDictNorm.add(n);
                        koNormToOrig.put(n, t);
                    }
                }
                break;
            } catch (Throwable ignore) {}
        }
    }

    private static String normalizeWhitespaceOnly(String s) {
        if (s == null) return "";
        try {
            String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
            return n.replaceAll("\\s", "");
        } catch (Throwable t) { return s.replaceAll("\\s", ""); }
    }

    private void runMlKitOcrOnRois(Bitmap src, java.util.List<RectF> rois) {
        if (rois == null) return;
        for (RectF r : rois) {
            runMlKitOcrOnRoi(src, r);
        }
    }
    private RectF expandAndClip(RectF r, float padFrac, int refW, int refH) {
        float padX = r.width() * padFrac;
        float padY = r.height() * padFrac;
        RectF out = new RectF(r.left - padX, r.top - padY, r.right + padX, r.bottom + padY);
        clipRect(out, 0, 0, refW, refH);
        return out;
    }

    private List<RectF> runEastOnRoi(Bitmap src, RectF roiRect, float scoreThr, float nmsIoU, int maxRects) {
        java.util.ArrayList<RectF> out = new java.util.ArrayList<>();
        if (eastTflite == null || eastW <= 0 || eastH <= 0) return out;
        try {
            int left = Math.max(0, Math.round(roiRect.left));
            int top = Math.max(0, Math.round(roiRect.top));
            int width = Math.max(1, Math.round(roiRect.width()));
            int height = Math.max(1, Math.round(roiRect.height()));
            if (left + width > src.getWidth()) width = src.getWidth() - left;
            if (top + height > src.getHeight()) height = src.getHeight() - top;

            Bitmap roi = Bitmap.createBitmap(src, left, top, width, height);
            Bitmap resized = Bitmap.createScaledBitmap(roi, eastW, eastH, true);

            java.nio.ByteBuffer input = java.nio.ByteBuffer.allocateDirect(4 * eastW * eastH * 3);
            input.order(java.nio.ByteOrder.nativeOrder());
            int[] pixels = new int[eastW * eastH];
            resized.getPixels(pixels, 0, eastW, 0, 0, eastW, eastH);
            int idx = 0;
            for (int y = 0; y < eastH; y++) {
                for (int x = 0; x < eastW; x++) {
                    int p = pixels[idx++];
                    float r = ((p >> 16) & 0xFF) / 255.0f;
                    float g = ((p >> 8) & 0xFF) / 255.0f;
                    float b = (p & 0xFF) / 255.0f;
                    input.putFloat(r); input.putFloat(g); input.putFloat(b);
                }
            }
            input.rewind();

            // Prepare outputs (two tensors: score map and geometry map)
            int outCount = eastTflite.getOutputTensorCount();
            java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();

            // Try NHWC first
            int[] sShape = eastTflite.getOutputTensor(0).shape();
            int[] gShape = eastTflite.getOutputTensor(1).shape();

            // Unify to [1, h4, w4, c]
            float[][][][] score;
            float[][][][] geo;
            if (sShape.length == 4 && gShape.length == 4) {
                score = new float[sShape[0]][sShape[1]][sShape[2]][sShape[3]];
                geo = new float[gShape[0]][gShape[1]][gShape[2]][gShape[3]];
                outputs.put(0, score);
                outputs.put(1, geo);
            } else {
                // Fallback to other layout is not implemented in this minimal version
                return out;
            }

            eastTflite.runForMultipleInputsOutputs(new Object[]{input}, outputs);

            int h4 = score[0].length;
            int w4 = score[0][0].length;
            float strideX = eastW / (float) w4;
            float strideY = eastH / (float) h4;

            java.util.ArrayList<RectF> props = new java.util.ArrayList<>();
            for (int y = 0; y < h4; y++) {
                for (int x = 0; x < w4; x++) {
                    float sc = score[0][y][x][0];
                    if (sc < scoreThr) continue;
                    float d0 = geo[0][y][x][0];
                    float d1 = geo[0][y][x][1];
                    float d2 = geo[0][y][x][2];
                    float d3 = geo[0][y][x][3];
                    float angle = geo[0][y][x][4];

                    // Approximate axis-aligned box from center and distances (ignoring rotation for simplicity)
                    float cx = (x + 0.5f) * strideX;
                    float cy = (y + 0.5f) * strideY;
                    float w = d1 + d3;
                    float h = d0 + d2;
                    RectF r = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
                    // Map to original frame coordinates
                    RectF fr = new RectF(r.left * (width / (float) eastW) + left,
                            r.top * (height / (float) eastH) + top,
                            r.right * (width / (float) eastW) + left,
                            r.bottom * (height / (float) eastH) + top);
                    clipRect(fr, 0, 0, src.getWidth(), src.getHeight());
                    if (fr.width() > 4 && fr.height() > 4) props.add(fr);
                }
            }

            // NMS on text boxes
            props.sort((a, b) -> Float.compare(b.width() * b.height(), a.width() * a.height()));
            java.util.ArrayList<RectF> keep = new java.util.ArrayList<>();
            boolean[] removed = new boolean[props.size()];
            for (int i = 0; i < props.size(); i++) {
                if (removed[i]) continue;
                RectF a = props.get(i);
                keep.add(a);
                if (keep.size() >= maxRects) break;
                for (int j = i + 1; j < props.size(); j++) {
                    if (removed[j]) continue;
                    RectF b = props.get(j);
                    if (iou(a, b) > nmsIoU) removed[j] = true;
                }
            }
            return keep;
        } catch (Throwable t) {
            return out;
        }
    }
    private void bindUseCases() {
        if (getContext() == null || getActivity() == null || previewView == null || cameraProvider == null) return;

        cameraProvider.unbindAll();

        // Prefer 1080p for sharper OCR on engraved pills
        Size preferred = new Size(1920, 1080);

        Preview preview = new Preview.Builder()
                .setTargetResolution(preferred)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                ;

        analysisBuilder.setTargetResolution(preferred);

        ImageAnalysis analysis = analysisBuilder.build();
        analysisUseCase = analysis;

        analysis.setAnalyzer(cameraExecutor, this::analyze);

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        camera = cameraProvider.bindToLifecycle(this, selector, preview, analysis);
        setupGestureControls();
    }

    private volatile long lastInferenceMs = 0L;

    private void analyze(@NonNull ImageProxy imageProxy) {
        try {
            if (tflite == null || isTearingDown) return;

            long now = System.currentTimeMillis();
            if (now - lastInferenceMs < 100) { // ~10 FPS max
                return;
            }
            lastInferenceMs = now;

            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

            Bitmap bitmap = toBitmap(imageProxy);
            if (bitmap == null) return;

            // Rotate bitmap to match upright orientation instead of using ImageProcessingOptions
            Bitmap rotated = rotateBitmap(bitmap, rotationDegrees);

            final int overlayImgW = rotated.getWidth();
            final int overlayImgH = rotated.getHeight();

            List<Detection> dets;
            synchronized (tfliteLock) {
                if (isTearingDown || tflite == null) { imageProxy.close(); return; }
                dets = runYoloDetections(rotated, SCORE_THRESHOLD, IOU_THRESHOLD, MAX_DETECTIONS);
            }

            // Update overlay and info on main thread
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (SHOW_OVERLAYS && overlayView != null) {
                        overlayView.setPreviewParams(overlayImgW, overlayImgH, previewScaleType != null ? previewScaleType : PreviewView.ScaleType.FILL_CENTER);
                        overlayView.setDetections(dets);
                    }
                    if (dets.size() != 1) {
                        if (dets.isEmpty()) {
                            updateInfo("약을 인식시켜 주세요");
                            setScanBackground(false);
                        } else {
                            updateInfo("한 종류의 약만 화면에 노출시켜 주세요");
                            setScanBackground(true);
                        }
                        // Clear transient OCR hint so stale text doesn't persist
                        currentOcrHint = null;
                        if (SHOW_OVERLAYS && overlayView != null) overlayView.setTextRegions(java.util.Collections.emptyList());
                        boxHistory.clear();
                        ocrAggregator.reset();
                        lastStableCode = null;
                    }
                });
            }

            // Only when exactly one pill is detected
            if (dets.size() == 1) {
                RectF pill = dets.get(0).bbox;
                boolean stable = updateAndCheckBoxStability(pill);
                long tNow = System.currentTimeMillis();
                if (getActivity() != null) {
                    final String hint = ellipsize(currentOcrHint, 16);
                    getActivity().runOnUiThread(() -> {
                        updateInfo(hint != null && !hint.isEmpty() ? "검색 중... (" + hint + ")" : "검색 중...");
                        setScanBackground(true);
                    });
                }
                if ((tNow - lastOcrMs) >= OCR_MIN_INTERVAL_MS) {
                    lastOcrMs = tNow;
                    // Use only the YOLO bbox (with small padding) as parent ROI
                    RectF roi = expandAndClip(pill, 0.12f, rotated.getWidth(), rotated.getHeight());
                    // If EAST model is available, extract smaller text regions within the pill ROI
                    java.util.List<RectF> textRois;
                    if (eastTflite != null) {
                        textRois = runEastOnRoi(rotated, roi, 0.55f, 0.4f, 12);
                        if (getActivity()!=null && SHOW_OVERLAYS && overlayView!=null) {
                            final java.util.List<RectF> toShow = textRois;
                            final int imgW = rotated.getWidth();
                            final int imgH = rotated.getHeight();
                            getActivity().runOnUiThread(() -> {
                                overlayView.setPreviewParams(imgW, imgH, previewScaleType != null ? previewScaleType : PreviewView.ScaleType.FILL_CENTER);
                                overlayView.setTextRegions(toShow);
                            });
                        }
                    } else {
                        textRois = java.util.Collections.singletonList(roi);
                    }
                    runMlKitOcrOnRois(rotated, textRois);
                }

                // Auto-zoom to help tiny engravings if user isn't pinching
                if (!userZooming && camera != null) {
                    float frameArea = rotated.getWidth() * rotated.getHeight();
                    float pillArea = Math.max(0, pill.width()) * Math.max(0, pill.height());
                    float frac = pillArea / Math.max(1f, frameArea);
                    float targetZoom;
                    if (frac < 0.05f) targetZoom = 0.65f; // very small -> zoom in more
                    else if (frac < 0.10f) targetZoom = 0.45f;
                    else if (frac < 0.18f) targetZoom = 0.30f;
                    else targetZoom = 0.0f;
                    if (targetZoom > linearZoom + 0.05f) {
                        linearZoom = Math.min(1f, targetZoom);
                        try { camera.getCameraControl().setLinearZoom(linearZoom); } catch (Throwable ignore) {}
                    }
                }
            }
        } catch (Throwable t) {
            updateInfo("분석 오류: " + t.getMessage());
        } finally {
            imageProxy.close();
        }
    }

    private void setupGestureControls() {
        if (previewView == null || camera == null) return;
        try {
            GestureDetector tapDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    try {
                        MeteringPointFactory f = previewView.getMeteringPointFactory();
                        MeteringPoint p = f.createPoint(e.getX(), e.getY());
                        FocusMeteringAction act = new FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                .build();
                        camera.getCameraControl().startFocusAndMetering(act);
                    } catch (Throwable ignore) {}
                    return true;
                }
                @Override public void onLongPress(MotionEvent e) {
                    toggleTorch();
                }
                @Override public boolean onDown(MotionEvent e) { return true; }
            });

            android.view.ScaleGestureDetector scaleDetector = new android.view.ScaleGestureDetector(getContext(), new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                    userZooming = true; return true;
                }
                @Override public boolean onScale(android.view.ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    // map factor to linear zoom delta
                    float delta = (factor - 1f) * 0.5f; // tune sensitivity
                    linearZoom = Math.max(0f, Math.min(1f, linearZoom + delta));
                    try { camera.getCameraControl().setLinearZoom(linearZoom); } catch (Throwable ignore) {}
                    return true;
                }
                @Override public void onScaleEnd(android.view.ScaleGestureDetector detector) { /* keep userZooming until next analyze pass */ }
            });

            previewView.setOnTouchListener((v, ev) -> {
                boolean a = scaleDetector.onTouchEvent(ev);
                boolean b = tapDetector.onTouchEvent(ev);
                if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                    // allow auto-zoom again after user gesture ends
                    userZooming = false;
                }
                return a || b;
            });
        } catch (Throwable ignore) {}
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
            if (hasFlash != null && hasFlash) {
                torchEnabled = !torchEnabled;
                camera.getCameraControl().enableTorch(torchEnabled);
                if (infoBar != null) updateInfo(torchEnabled ? "조명 켬" : "조명 끔");
            }
        } catch (Throwable ignore) {}
    }

    private void updateInfo(String text) {
        if (infoBar == null) return;
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            infoBar.setText(text);
            boolean busy = false;
            if (text != null) {
                busy = text.contains("검색 중") || text.contains("초기화 중");
            }
            if (progressScan != null) progressScan.setVisibility(busy?View.VISIBLE:View.GONE);
        });
    }

    private void setScanBackground(boolean detected) {
        if (getContext() == null) return;
        int bg = detected ? androidx.core.content.ContextCompat.getColor(getContext(), R.color.login_bg)
                : androidx.core.content.ContextCompat.getColor(getContext(), android.R.color.white);
        int fg = detected ? androidx.core.content.ContextCompat.getColor(getContext(), android.R.color.white)
                : android.graphics.Color.parseColor("#2c2c2c");
        if (infoBar != null) {
            infoBar.setBackgroundColor(bg);
            infoBar.setTextColor(fg);
        }
        if (toolbar != null) toolbar.setBackgroundColor(bg);
        if (toolbarTitle != null) toolbarTitle.setTextColor(fg);
    }

    private static String ellipsize(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static Bitmap toBitmap(@NonNull ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            int pixelStride = plane.getPixelStride(); // expect 4 for RGBA_8888
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            int bmpWidth = width + Math.max(0, rowPadding / Math.max(1, pixelStride));
            Bitmap bmpWithPadding = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888);
            bmpWithPadding.copyPixelsFromBuffer(buffer);

            if (bmpWidth != width) {
                return Bitmap.createBitmap(bmpWithPadding, 0, 0, width, height);
            } else {
                return bmpWithPadding;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap rotateBitmap(@NonNull Bitmap src, int rotationDegrees) {
        if (rotationDegrees == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(rotationDegrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    // ===== Simple image preprocessing for low-contrast engravings =====
    private static Bitmap ensureGrayscale(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int y = (int) (0.299f * r + 0.587f * g + 0.114f * b);
            px[i] = 0xFF000000 | (y << 16) | (y << 8) | y;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap blur3x3Gray(Bitmap gray) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[] src = new int[w * h];
        gray.getPixels(src, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, cnt = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy; if (yy < 0 || yy >= h) continue;
                    int row = yy * w;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx; if (xx < 0 || xx >= w) continue;
                        int p = src[row + xx];
                        int v = p & 0xFF; // gray
                        sum += v; cnt++;
                    }
                }
                int v = sum / Math.max(1, cnt);
                dst[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private static Bitmap unsharp(Bitmap gray, float amount) {
        Bitmap blur = blur3x3Gray(gray);
        int w = gray.getWidth(), h = gray.getHeight();
        int[] g = new int[w * h];
        int[] b = new int[w * h];
        gray.getPixels(g, 0, w, 0, 0, w, h);
        blur.getPixels(b, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        for (int i = 0; i < g.length; i++) {
            int vg = g[i] & 0xFF; int vb = b[i] & 0xFF;
            int v = clamp(Math.round(vg + amount * (vg - vb)));
            dst[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap sobelMagnitude(Bitmap gray) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[] src = new int[w * h];
        gray.getPixels(src, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        int[] gxK = {-1,0,1,-2,0,2,-1,0,1};
        int[] gyK = {-1,-2,-1,0,0,0,1,2,1};
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int ix = 0, iy = 0, k = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int row = (y + dy) * w;
                    for (int dx = -1; dx <= 1; dx++) {
                        int v = src[row + (x + dx)] & 0xFF;
                        ix += v * gxK[k];
                        iy += v * gyK[k];
                        k++;
                    }
                }
                int mag = (int) Math.min(255, Math.hypot(ix, iy));
                dst[y * w + x] = 0xFF000000 | (mag << 16) | (mag << 8) | mag;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap morphErode3x3Gray(Bitmap gray) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[] src = new int[w * h];
        gray.getPixels(src, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int mn = 255;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy; if (yy < 0 || yy >= h) continue;
                    int row = yy * w;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx; if (xx < 0 || xx >= w) continue;
                        int v = src[row + xx] & 0xFF;
                        if (v < mn) mn = v;
                    }
                }
                dst[y * w + x] = 0xFF000000 | (mn << 16) | (mn << 8) | mn;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap morphDilate3x3Gray(Bitmap gray) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[] src = new int[w * h];
        gray.getPixels(src, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int mx = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy; if (yy < 0 || yy >= h) continue;
                    int row = yy * w;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx; if (xx < 0 || xx >= w) continue;
                        int v = src[row + xx] & 0xFF;
                        if (v > mx) mx = v;
                    }
                }
                dst[y * w + x] = 0xFF000000 | (mx << 16) | (mx << 8) | mx;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap whiteTopHat(Bitmap gray) {
        Bitmap er = morphErode3x3Gray(gray);
        Bitmap op = morphDilate3x3Gray(er); // opening = dilate(erode(gray))
        int w = gray.getWidth(), h = gray.getHeight();
        int[] g = new int[w * h];
        int[] o = new int[w * h];
        gray.getPixels(g, 0, w, 0, 0, w, h);
        op.getPixels(o, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        for (int i = 0; i < g.length; i++) {
            int vg = g[i] & 0xFF; int vo = o[i] & 0xFF;
            int v = clamp(vg - vo);
            dst[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap blackTopHat(Bitmap gray) {
        Bitmap di = morphDilate3x3Gray(gray);
        Bitmap cl = morphErode3x3Gray(di); // closing = erode(dilate(gray))
        int w = gray.getWidth(), h = gray.getHeight();
        int[] g = new int[w * h];
        int[] c = new int[w * h];
        gray.getPixels(g, 0, w, 0, 0, w, h);
        cl.getPixels(c, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        for (int i = 0; i < g.length; i++) {
            int vg = g[i] & 0xFF; int vc = c[i] & 0xFF;
            int v = clamp(vc - vg);
            dst[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap scaleUpIfSmall(Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m >= maxDim) return src;
        float s = (float) maxDim / Math.max(1, m);
        int nw = Math.max(1, Math.round(w * s));
        int nh = Math.max(1, Math.round(h * s));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static Bitmap otsuThreshold(Bitmap gray) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[] src = new int[w * h]; gray.getPixels(src, 0, w, 0, 0, w, h);
        int[] hist = new int[256];
        for (int p : src) hist[p & 0xFF]++;
        int total = w * h;
        float sum = 0; for (int i = 0; i < 256; i++) sum += i * hist[i];
        float sumB = 0; int wB = 0; int wF; float varMax = -1; int thr = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t]; if (wB == 0) continue; wF = total - wB; if (wF == 0) break;
            sumB += t * hist[t];
            float mB = sumB / wB; float mF = (sum - sumB) / wF;
            float varBetween = (float) wB * wF * (mB - mF) * (mB - mF);
            if (varBetween > varMax) { varMax = varBetween; thr = t; }
        }
        int[] dst = new int[w * h];
        for (int i = 0; i < src.length; i++) {
            int v = (src[i] & 0xFF) > thr ? 255 : 0;
            dst[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static Bitmap adaptiveMeanThreshold(Bitmap gray, int blockSize, int c) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[] src = new int[w * h]; gray.getPixels(src, 0, w, 0, 0, w, h);
        // integral image
        long[] integral = new long[(w + 1) * (h + 1)];
        for (int y = 1; y <= h; y++) {
            long rowsum = 0; int yi = (y - 1) * w; int idx = y * (w + 1) + 1;
            for (int x = 1; x <= w; x++) {
                int v = src[yi + (x - 1)] & 0xFF; rowsum += v;
                integral[idx] = integral[idx - (w + 1)] + rowsum; idx++;
            }
        }
        int r = Math.max(1, blockSize / 2);
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                int A = y0 * (w + 1) + x0;
                int B = y0 * (w + 1) + (x1 + 1);
                int C = (y1 + 1) * (w + 1) + x0;
                int D = (y1 + 1) * (w + 1) + (x1 + 1);
                int area = (x1 - x0 + 1) * (y1 - y0 + 1);
                long sum = integral[D] - integral[B] - integral[C] + integral[A];
                int mean = (int)(sum / Math.max(1, area));
                int v = (src[y * w + x] & 0xFF) > (mean - c) ? 255 : 0;
                dst[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    // ===== TFLite helpers =====
    private java.nio.ByteBuffer loadModelFromAssets(String filename) throws Exception {
        try {
            android.content.res.AssetFileDescriptor fd = requireContext().getAssets().openFd(filename);
            java.io.FileInputStream input = new java.io.FileInputStream(fd.getFileDescriptor());
            java.nio.channels.FileChannel channel = input.getChannel();
            long startOffset = fd.getStartOffset();
            long declaredLength = fd.getDeclaredLength();
            return channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        } catch (Throwable compressed) {
            // Fallback for compressed assets
            java.io.InputStream is = requireContext().getAssets().open(filename);
            byte[] bytes = readAllBytes(is);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(bytes.length);
            buffer.order(java.nio.ByteOrder.nativeOrder());
            buffer.put(bytes);
            buffer.rewind();
            return buffer;
        }
    }

    private static byte[] readAllBytes(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
        return baos.toByteArray();
    }

    private List<String> tryLoadLabels() {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        try {
            // Try common locations
            list = readLinesFromAsset("models/labels.txt");
            if (!list.isEmpty()) return list;
        } catch (Exception ignored) {}
        try {
            list = readLinesFromAsset("labels.txt");
        } catch (Exception ignored) {}
        return list;
    }

    private java.util.ArrayList<String> readLinesFromAsset(String path) throws Exception {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(requireContext().getAssets().open(path)));
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        br.close();
        return lines;
    }

    private List<Detection> runYoloDetections(Bitmap bitmap, float scoreThr, float iouThr, int maxDet) {
        java.util.ArrayList<Detection> modelSpace = new java.util.ArrayList<>();
        if (tflite == null || inputWidth <= 0 || inputHeight <= 0) return modelSpace;

        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        int padLeft = 0, padTop = 0;
        float scale = 1f;
        float scaleX = srcW / (float) inputWidth;
        float scaleY = srcH / (float) inputHeight;

        Bitmap resized;
        if (USE_LETTERBOX_RESIZE) {
            scale = Math.min(inputWidth / (float) srcW, inputHeight / (float) srcH);
            int scaledW = Math.round(srcW * scale);
            int scaledH = Math.round(srcH * scale);
            padLeft = (inputWidth - scaledW) / 2;
            padTop = (inputHeight - scaledH) / 2;
            resized = letterbox(bitmap, inputWidth, inputHeight);
        } else {
            resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        }

        java.nio.ByteBuffer inputBuffer = java.nio.ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * inputChannels);
        inputBuffer.order(java.nio.ByteOrder.nativeOrder());

        int[] pixels = new int[inputWidth * inputHeight];
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        int idx = 0;
        for (int y = 0; y < inputHeight; y++) {
            for (int x = 0; x < inputWidth; x++) {
                int p = pixels[idx++];
                float r = ((p >> 16) & 0xFF) / 255.0f;
                float g = ((p >> 8) & 0xFF) / 255.0f;
                float b = (p & 0xFF) / 255.0f;
                if (NORMALIZE_NEG_ONE_TO_ONE) {
                    r = (r - 0.5f) / 0.5f;
                    g = (g - 0.5f) / 0.5f;
                    b = (b - 0.5f) / 0.5f;
                }
                inputBuffer.putFloat(r);
                inputBuffer.putFloat(g);
                inputBuffer.putFloat(b);
            }
        }
        inputBuffer.rewind();

        int outCount = tflite.getOutputTensorCount();
        int[] outShape = tflite.getOutputTensor(0).shape();
        DataType outType = tflite.getOutputTensor(0).dataType();

        boolean decoded = false;

        if (outShape.length == 3) {
            // Expect [1, C, N] or [1, N, C]
            int dim1 = outShape[1];
            int dim2 = outShape[2];
            float[][][] outArr = new float[1][dim1][dim2];
            tflite.run(inputBuffer, outArr);

            // Prefer channels-first path like reference implementation
            boolean channelsFirst = dim1 < dim2; // channels fewer than boxes
            if (channelsFirst) decodeYoloChannelsFirst(outArr[0], scoreThr, modelSpace);
            else decodeYoloChannelsLast(outArr[0], scoreThr, modelSpace);
            decoded = true;
        } else if (outShape.length == 2) {
            // Often [1, N, 6] gets flattened by some exports to [N, 6]
            int dim0 = outShape[0];
            int dim1 = outShape[1];
            if (dim1 == 6) {
                float[][] out2 = new float[dim0][6];
                tflite.run(inputBuffer, out2);
                decodeYoloNmsLast(out2, scoreThr, modelSpace);
                decoded = true;
            }
        }

        if (!decoded) {
            updateInfo("지원되지 않는 출력형태: " + java.util.Arrays.toString(outShape) + ", " + outType.name() + " (tensors=" + outCount + ")");
        }

        // Map model-space boxes (letterboxed input) back to source-space
        java.util.ArrayList<Detection> srcSpace = new java.util.ArrayList<>(modelSpace.size());
        for (Detection d : modelSpace) {
            RectF m = d.bbox;
            float left, top, right, bottom;
            if (USE_LETTERBOX_RESIZE) {
                left = (m.left - padLeft) / scale;
                top = (m.top - padTop) / scale;
                right = (m.right - padLeft) / scale;
                bottom = (m.bottom - padTop) / scale;
            } else {
                left = m.left * scaleX;
                top = m.top * scaleY;
                right = m.right * scaleX;
                bottom = m.bottom * scaleY;
            }
            RectF s = new RectF(left, top, right, bottom);
            clipRect(s, 0, 0, srcW, srcH);
            if (validBox(s, srcW, srcH)) srcSpace.add(new Detection(s, d.labelId, d.score));
        }

        // NMS in source space
        srcSpace.sort((a, b) -> Float.compare(b.score, a.score));
        java.util.ArrayList<Detection> keep = new java.util.ArrayList<>();
        boolean[] removed = new boolean[srcSpace.size()];
        for (int i = 0; i < srcSpace.size(); i++) {
            if (removed[i]) continue;
            Detection di = srcSpace.get(i);
            keep.add(di);
            if (keep.size() >= maxDet) break;
            for (int j = i + 1; j < srcSpace.size(); j++) {
                if (removed[j]) continue;
                Detection dj = srcSpace.get(j);
                boolean sameClass = di.labelId == dj.labelId;
                if (iou(di.bbox, dj.bbox) > iouThr && (CLASS_AGNOSTIC_NMS || sameClass)) removed[j] = true;
            }
        }
        return keep;
    }

    private void decodeYoloChannelsFirst(float[][] cn, float scoreThr, java.util.ArrayList<Detection> out) {
        // cn shape: [C, N] where first 4 are [x,y,w,h], next maybe obj, then classes
        int C = cn.length;
        int N = cn[0].length;
        boolean hasObj = HAS_OBJECTNESS && (C - 5) >= 1;
        int classOffset = hasObj ? 5 : 4;
        int numClasses = Math.max(1, C - classOffset);

        for (int i = 0; i < N; i++) {
            float x = cn[0][i];
            float y = cn[1][i];
            float w = cn[2][i];
            float h = cn[3][i];
            float obj = hasObj ? sigmoid(cn[4][i]) : 1f;

            int bestId = 0;
            float bestScore = 0f;
            for (int c = 0; c < numClasses; c++) {
                float s = APPLY_SIGMOID_TO_SCORES ? sigmoid(cn[classOffset + c][i]) : cn[classOffset + c][i];
                if (s > bestScore) { bestScore = s; bestId = c; }
            }
            float conf = hasObj ? (obj * bestScore) : bestScore;
            if (conf < scoreThr) continue;

            // Scale normalized coords to pixels if needed
            boolean norm = (Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.max(Math.abs(w), Math.abs(h))) <= 1.5f);
            if (norm) { x *= inputWidth; y *= inputHeight; w *= inputWidth; h *= inputHeight; }
            RectF r = xywhToRect(x, y, w, h);
            clipRect(r, 0, 0, inputWidth, inputHeight);
            if (!validBox(r, inputWidth, inputHeight)) continue;

            out.add(new Detection(r, bestId, conf));
        }
    }

    private void decodeYoloChannelsLast(float[][] nc, float scoreThr, java.util.ArrayList<Detection> out) {
        // nc shape: [N, C]
        int N = nc.length;
        int C = nc[0].length;
        boolean hasObj = HAS_OBJECTNESS && (C - 5) >= 1;
        int classOffset = hasObj ? 5 : 4;
        int numClasses = Math.max(1, C - classOffset);

        for (int i = 0; i < N; i++) {
            float x = nc[i][0];
            float y = nc[i][1];
            float w = nc[i][2];
            float h = nc[i][3];
            float obj = hasObj ? sigmoid(nc[i][4]) : 1f;

            int bestId = 0;
            float bestScore = 0f;
            for (int c = 0; c < numClasses; c++) {
                float s = APPLY_SIGMOID_TO_SCORES ? sigmoid(nc[i][classOffset + c]) : nc[i][classOffset + c];
                if (s > bestScore) { bestScore = s; bestId = c; }
            }
            float conf = hasObj ? (obj * bestScore) : bestScore;
            if (conf < scoreThr) continue;

            boolean norm = (Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.max(Math.abs(w), Math.abs(h))) <= 1.5f);
            if (norm) { x *= inputWidth; y *= inputHeight; w *= inputWidth; h *= inputHeight; }
            RectF r = xywhToRect(x, y, w, h);
            clipRect(r, 0, 0, inputWidth, inputHeight);
            if (!validBox(r, inputWidth, inputHeight)) continue;

            out.add(new Detection(r, bestId, conf));
        }
    }

    // Handle NMS-applied outputs shape [N, 6] or [N, 6]-like
    private void decodeYoloNmsLast(float[][] dets, float scoreThr, java.util.ArrayList<Detection> out) {
        int N = dets.length;
        for (int i = 0; i < N; i++) {
            float a = dets[i][0];
            float b = dets[i][1];
            float c = dets[i][2];
            float d = dets[i][3];
            float conf = dets[i][4];
            int cls = Math.max(0, Math.round(dets[i][5]));

            if (conf < scoreThr) continue;

            RectF r;
            // Try to detect if xyxy normalized or absolute
            if (c > a && d > b) {
                // assume xyxy
                float x1 = a; float y1 = b; float x2 = c; float y2 = d;
                if (x2 <= 1f && y2 <= 1f) { x1 *= inputWidth; y1 *= inputHeight; x2 *= inputWidth; y2 *= inputHeight; }
                r = new RectF(x1, y1, x2, y2);
            } else {
                // assume xywh
                float x = a; float y = b; float w = c; float h = d;
                if (w <= 1f && h <= 1f) { x *= inputWidth; y *= inputHeight; w *= inputWidth; h *= inputHeight; }
                r = xywhToRect(x, y, w, h);
            }
            clipRect(r, 0, 0, inputWidth, inputHeight);
            if (!validBox(r, inputWidth, inputHeight)) continue;
            out.add(new Detection(r, cls, conf));
        }
    }

    private static RectF xywhToRect(float x, float y, float w, float h) {
        float left = x - w / 2f;
        float top = y - h / 2f;
        float right = x + w / 2f;
        float bottom = y + h / 2f;
        return new RectF(left, top, right, bottom);
    }

    private static void clipRect(RectF r, float minX, float minY, float maxX, float maxY) {
        r.left = Math.max(minX, Math.min(r.left, maxX));
        r.top = Math.max(minY, Math.min(r.top, maxY));
        r.right = Math.max(minX, Math.min(r.right, maxX));
        r.bottom = Math.max(minY, Math.min(r.bottom, maxY));
    }

    private static float iou(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);
        float inter = Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);
        float areaA = Math.max(0, a.width()) * Math.max(0, a.height());
        float areaB = Math.max(0, b.width()) * Math.max(0, b.height());
        float union = areaA + areaB - inter + 1e-6f;
        return inter / union;
    }

    private static float sigmoid(float x) {
        return (float)(1.0 / (1.0 + Math.exp(-x)));
    }

    private boolean validBox(RectF r, int refW, int refH) {
        if (r.width() <= 0 || r.height() <= 0) return false;
        float minSidePx = Math.min(refW, refH) * MIN_BOX_SIDE_FRAC;
        return r.width() >= minSidePx && r.height() >= minSidePx;
    }

    // ===== Stabilization helpers =====
    private boolean updateAndCheckBoxStability(RectF box) {
        // Keep history of last K boxes
        if (boxHistory.size() >= BOX_HISTORY_SIZE) boxHistory.removeFirst();
        boxHistory.addLast(new RectF(box));
        if (boxHistory.size() < BOX_HISTORY_SIZE) return false;

        // Check IoU stability vs previous history
        RectF curr = boxHistory.getLast();
        for (RectF prev : boxHistory) {
            if (prev == curr) continue;
            if (iou(curr, prev) < BOX_STABLE_IOU) return false;
        }
        // Check area jitter
        float mean = 0f;
        java.util.ArrayList<Float> areas = new java.util.ArrayList<>(boxHistory.size());
        for (RectF b : boxHistory) { float a = Math.max(0, b.width()) * Math.max(0, b.height()); areas.add(a); mean += a; }
        mean /= areas.size();
        float var = 0f;
        for (float a : areas) { float d = a - mean; var += d * d; }
        var /= areas.size();
        float std = (float)Math.sqrt(var);
        return (std / (mean + 1e-6f)) <= BOX_AREA_JITTER;
    }

    private static String normalizeAndPickCode(String raw) {
        if (raw == null) return "";
        String upper = raw.toUpperCase();
        // Replace common confusions
        upper = upper.replace('Ｏ','O').replace('Ｉ','I').replace('１','1').replace('０','0');
        // Keep letters, digits, hyphen and spaces
        upper = upper.replaceAll("[^A-Z0-9 -]", " ").trim();
        String[] tokens = upper.split(" +");
        String best = "";
        for (String t : tokens) {
            if (t.length() >= 2 && t.length() > best.length()) best = t;
        }
        return best;
    }

    private static class OcrCodeAggregator {
        private final int window;
        private final float minRatio;
        private final long minHoldMs;
        private final java.util.ArrayDeque<Item> buf = new java.util.ArrayDeque<>();

        private static class Item { final String code; final long ts; Item(String c,long t){code=c;ts=t;} }

        OcrCodeAggregator(int window, float minRatio, long minHoldMs) {
            this.window = window; this.minRatio = minRatio; this.minHoldMs = minHoldMs;
        }

        void add(String code) {
            if (code == null || code.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (buf.size() >= window) buf.removeFirst();
            buf.addLast(new Item(code, now));
        }

        String getStable() {
            if (buf.isEmpty()) return null;
            java.util.HashMap<String, Integer> cnt = new java.util.HashMap<>();
            java.util.HashMap<String, Long> firstTs = new java.util.HashMap<>();
            for (Item it : buf) {
                cnt.put(it.code, cnt.getOrDefault(it.code, 0) + 1);
                if (!firstTs.containsKey(it.code)) firstTs.put(it.code, it.ts);
            }
            String top = null; int best = 0;
            for (java.util.Map.Entry<String,Integer> e : cnt.entrySet()) {
                if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
            }
            if (top == null) return null;
            float ratio = best / (float) buf.size();
            long hold = System.currentTimeMillis() - firstTs.get(top);
            if (ratio >= minRatio && hold >= minHoldMs) return top;
            return null;
        }

        void reset() { buf.clear(); }
    }

    private static class Token {
        final String text;
        final RectF box;
        final float cx, cy;
        Token(String t, RectF b) { this.text = t; this.box = new RectF(b); this.cx = (b.left + b.right)/2f; this.cy = (b.top + b.bottom)/2f; }
    }

    private static String composeCodeFromTokens(java.util.List<Token> tokens) {
        if (tokens == null || tokens.isEmpty()) return "";
        // Pick two longest tokens if available; else the longest single token
        tokens.sort((a,b)-> Integer.compare(b.text.length(), a.text.length()));
        if (tokens.size() == 1) return tokens.get(0).text;
        Token a = tokens.get(0);
        Token b = tokens.get(1);
        // Order by reading direction (horizontal vs vertical)
        if (Math.abs(a.cy - b.cy) <= Math.abs(a.cx - b.cx)) {
            // horizontal: left-to-right
            Token left = a.cx <= b.cx ? a : b;
            Token right = a.cx <= b.cx ? b : a;
            return left.text + right.text;
        } else {
            // vertical: top-to-bottom
            Token top = a.cy <= b.cy ? a : b;
            Token bottom = a.cy <= b.cy ? b : a;
            return top.text + bottom.text;
        }
    }

    private String summaryText(List<Detection> dets) {
        int n = (dets == null) ? 0 : dets.size();
        return String.format(java.util.Locale.getDefault(), "약 감지 %d개", n);
    }

    public static class Detection {
        final RectF bbox;
        final int labelId;
        final float score;
        Detection(RectF bbox, int labelId, float score) { this.bbox = bbox; this.labelId = labelId; this.score = score; }
    }

    private static Bitmap letterbox(Bitmap src, int dstW, int dstH) {
        float scale = Math.min(dstW / (float) src.getWidth(), dstH / (float) src.getHeight());
        int scaledW = Math.round(src.getWidth() * scale);
        int scaledH = Math.round(src.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
        Bitmap out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF000000); // black padding
        float left = (dstW - scaledW) / 2f;
        float top = (dstH - scaledH) / 2f;
        c.drawBitmap(scaled, left, top, (Paint) null);
        return out;
    }

    // ====== Networking & navigation for pill info / interactions ======
    private volatile String lastRequestedCode = null;
    private final okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();

    private void maybeRequestForStableCode() {
        String code = lastStableCode;
        if (code == null || code.trim().isEmpty()) return;
        String norm = normalizeAndPickCode(code); // removes non-alnum and uppercases (already normalized)
        if (norm == null || norm.isEmpty()) return;
        if (norm.equals(lastRequestedCode)) return;
        lastRequestedCode = norm;
        fetchPillAndDecide(norm);
    }

    private void fetchPillAndDecide(String code) {
        String enc;
        try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/pills/by-imprint?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { postInfo("정보 요청 실패"); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                String body = response.body() != null ? response.body().string() : null;
                if (!response.isSuccessful() || body == null) { postInfo("정보 없음(" + response.code() + ")"); return; }
                org.json.JSONObject json = safeJson(body);
                org.json.JSONObject pill = json != null ? json.optJSONObject("pill") : null;
                if (pill == null) { postInfo("인식 데이터 없음"); return; }
                String name = pill.optString("name", "");
                String imageUrl = pill.optString("image_url", null);
                String category = pill.optString("category", null);
                if (category == null || category.trim().isEmpty()) {
                    // Fallback: try top-level category if present
                    category = json != null ? json.optString("category", "") : "";
                }
                int pillId = pill.optInt("id", -1);

                // Decide using user-specific interaction check when available
                decideAddabilityAndNavigate(code, pillId, name, imageUrl, category);
            }
        });
    }

    private void decideAddabilityAndNavigate(String code, int pillId, String name, String imageUrl, String category) {
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) {
            // No user context → show general good screen; add-flow will still recheck when user taps Add.
            navigateGood(name, imageUrl, category, code, pillId);
            return;
        }
        String enc; try { enc = java.net.URLEncoder.encode(code, "UTF-8"); } catch (Exception e) { enc = code; }
        String url = "http://43.200.178.100:8000/interactions/check-against-user?code=" + enc;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).header("Authorization","Bearer "+jwt).build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { navigateGood(name, imageUrl, category, code, pillId); }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body()==null) { navigateGood(name, imageUrl, category, code, pillId); return; }
                String body = response.body().string();
                org.json.JSONObject j = safeJson(body);
                boolean canAdd = j != null && j.optBoolean("can_add", true);
                if (!canAdd) navigateError(name, imageUrl, category, null, null, code);
                else navigateGood(name, imageUrl, category, code, pillId);
            }
        });
    }

    private void navigateGood(String name, String imageUrl, String category, String code, int pillId) {
        if (!isAdded()) return;
        getActivity().runOnUiThread(() -> {
            Fragment f = MedicinegoodFragment.newInstance(name, imageUrl, category, code, pillId, "", "", "");
            getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, f).addToBackStack(null).commit();
        });
    }
    private void navigateError(String name, String imageUrl, String category, String altName, String altImageUrl, String code) {
        if (!isAdded()) return;
        getActivity().runOnUiThread(() -> {
            Fragment f = MedicineerrorFragment.newInstance(name, imageUrl, category, altName, altImageUrl, code);
            getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, f).addToBackStack(null).commit();
        });
    }

    private void postInfo(String msg) { if (getActivity()!=null) getActivity().runOnUiThread(() -> updateInfo(msg)); }
    private static org.json.JSONObject safeJson(String s) { try { return new org.json.JSONObject(s);} catch(Exception e){ return null;} }
}
