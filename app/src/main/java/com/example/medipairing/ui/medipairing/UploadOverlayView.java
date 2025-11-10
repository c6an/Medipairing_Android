package com.example.medipairing.ui.medipairing;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class UploadOverlayView extends View {
    private final Paint paint;
    private List<Rect> boundingBoxes = new ArrayList<>();
    private int imageWidth = 1;
    private int imageHeight = 1;

    public UploadOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setColor(Color.YELLOW);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6.0f);
    }

    public void setResults(List<Rect> boxes, int imgW, int imgH) {
        this.boundingBoxes = boxes != null ? boxes : new ArrayList<>();
        this.imageWidth = imgW > 0 ? imgW : 1;
        this.imageHeight = imgH > 0 ? imgH : 1;
        invalidate();
    }

    public void clear() {
        this.boundingBoxes.clear();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boundingBoxes.isEmpty() || imageWidth <= 1 || imageHeight <= 1) return;

        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float imageAspect = (float) imageWidth / imageHeight;
        float viewAspect = (float) viewWidth / viewHeight;

        float scale;
        float offsetX = 0f, offsetY = 0f;
        if (imageAspect > viewAspect) {
            scale = viewWidth / imageWidth;
            offsetY = (viewHeight - imageHeight * scale) / 2f;
        } else {
            scale = viewHeight / imageHeight;
            offsetX = (viewWidth - imageWidth * scale) / 2f;
        }

        for (Rect r : boundingBoxes) {
            RectF m = new RectF(
                    r.left * scale + offsetX,
                    r.top * scale + offsetY,
                    r.right * scale + offsetX,
                    r.bottom * scale + offsetY
            );
            canvas.drawRect(m, paint);
        }
    }
}

