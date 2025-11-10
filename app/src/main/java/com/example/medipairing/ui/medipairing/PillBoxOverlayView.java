package com.example.medipairing.ui.medipairing;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.camera.view.PreviewView;

import java.util.ArrayList;
import java.util.List;

public class PillBoxOverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ocrRoiFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ocrRoiStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Det> dets = new ArrayList<>();
    private final List<RectF> textRects = new ArrayList<>();
    private RectF ocrRoiRect = null; // single ROI rect for MLKit OCR
    private List<String> labels;
    private int srcW = 0, srcH = 0;
    private PreviewView.ScaleType scaleType = PreviewView.ScaleType.FILL_CENTER;

    public PillBoxOverlayView(Context context) { super(context); init(); }
    public PillBoxOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public PillBoxOverlayView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        boxPaint.setColor(Color.GREEN);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x5532CD32); // semi-transparent green

        textBoxPaint.setStyle(Paint.Style.STROKE);
        textBoxPaint.setStrokeWidth(3f);
        textBoxPaint.setColor(Color.YELLOW);

        textFillPaint.setStyle(Paint.Style.FILL);
        textFillPaint.setColor(0x55FFFF00); // semi-transparent yellow

        ocrRoiStrokePaint.setStyle(Paint.Style.STROKE);
        ocrRoiStrokePaint.setStrokeWidth(4f);
        ocrRoiStrokePaint.setColor(Color.CYAN);

        ocrRoiFillPaint.setStyle(Paint.Style.FILL);
        ocrRoiFillPaint.setColor(0x3320FFFF); // light cyan
    }

    public static class Det {
        public final RectF rect; // in source coordinates
        public final int labelId;
        public final float score;
        public Det(RectF rect, int labelId, float score) { this.rect = rect; this.labelId = labelId; this.score = score; }
    }

    public synchronized void setPreviewParams(int srcW, int srcH, PreviewView.ScaleType scaleType) {
        this.srcW = Math.max(1, srcW);
        this.srcH = Math.max(1, srcH);
        if (scaleType != null) this.scaleType = scaleType;
        postInvalidateOnAnimation();
    }

    public synchronized void setDetections(List<com.example.medipairing.ui.medipairing.MedipairingFragment.Detection> detections) {
        dets.clear();
        if (detections != null) {
            for (com.example.medipairing.ui.medipairing.MedipairingFragment.Detection d : detections) {
                dets.add(new Det(new RectF(d.bbox), d.labelId, d.score));
            }
        }
        postInvalidateOnAnimation();
    }

    public synchronized void setTextRegions(List<RectF> rects) {
        textRects.clear();
        if (rects != null) {
            for (RectF r : rects) textRects.add(new RectF(r));
        }
        postInvalidateOnAnimation();
    }

    public synchronized void setOcrRoi(RectF rect) {
        if (rect == null) {
            this.ocrRoiRect = null;
        } else {
            this.ocrRoiRect = new RectF(rect);
        }
        postInvalidateOnAnimation();
    }

    public synchronized void setLabels(List<String> labels) {
        this.labels = labels;
        postInvalidateOnAnimation();
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (srcW <= 0 || srcH <= 0) return;
        float vw = getWidth();
        float vh = getHeight();
        float sx = vw / (float) srcW;
        float sy = vh / (float) srcH;
        float scale;
        float dx = 0f, dy = 0f;

        switch (scaleType) {
            case FIT_CENTER:
                scale = Math.min(sx, sy);
                dx = (vw - srcW * scale) / 2f;
                dy = (vh - srcH * scale) / 2f;
                break;
            case FILL_START:
                scale = Math.max(sx, sy);
                dx = 0f;
                dy = 0f;
                break;
            case FILL_END:
                scale = Math.max(sx, sy);
                dx = vw - srcW * scale;
                dy = vh - srcH * scale;
                break;
            case FILL_CENTER:
            default:
                scale = Math.max(sx, sy);
                dx = (vw - srcW * scale) / 2f;
                dy = (vh - srcH * scale) / 2f;
                break;
        }

        if (!dets.isEmpty()) {
            for (Det d : dets) {
                RectF r = new RectF(
                        d.rect.left * scale + dx,
                        d.rect.top * scale + dy,
                        d.rect.right * scale + dx,
                        d.rect.bottom * scale + dy
                );
                canvas.drawRect(r, fillPaint);
                canvas.drawRect(r, boxPaint);
            }
        }

        if (!textRects.isEmpty()) {
            for (RectF tr : textRects) {
                RectF r = new RectF(
                        tr.left * scale + dx,
                        tr.top * scale + dy,
                        tr.right * scale + dx,
                        tr.bottom * scale + dy
                );
                canvas.drawRect(r, textFillPaint);
                canvas.drawRect(r, textBoxPaint);
            }
        }

        if (ocrRoiRect != null) {
            RectF r = new RectF(
                    ocrRoiRect.left * scale + dx,
                    ocrRoiRect.top * scale + dy,
                    ocrRoiRect.right * scale + dx,
                    ocrRoiRect.bottom * scale + dy
            );
            canvas.drawRect(r, ocrRoiFillPaint);
            canvas.drawRect(r, ocrRoiStrokePaint);
        }
    }
}
