package com.example.medipairing.ui.medipairing;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.View;

/**
 * Dim background with a circular transparent window in the center.
 * This helps users center only the pill (ignore boxes/leaflets).
 */
public class CircularMaskView extends View {
    private final Paint dimPaint = new Paint();
    private final Paint clearPaint = new Paint();

    public CircularMaskView(Context ctx) { this(ctx, null); }
    public CircularMaskView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        setWillNotDraw(false);
        dimPaint.setColor(0x88000000); // semi-transparent black
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        // Fill dim
        canvas.drawRect(0, 0, w, h, dimPaint);
        // Cut a perfect circle
        float r = Math.min(w, h) / 2f - dp(4);
        float cx = w / 2f, cy = h / 2f;
        canvas.drawCircle(cx, cy, r, clearPaint);
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }
}

