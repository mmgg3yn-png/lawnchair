/*
 * Copyright 2026 Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.launcher3.folder;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small layered-folder glyph used when a folder is stored inside another folder. */
final class FolderStackPreviewDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int count;

    FolderStackPreviewDrawable(int count) {
        this.count = Math.max(0, count);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        float left = b.left + w * .14f;
        float top = b.top + h * .22f;
        float right = b.right - w * .14f;
        float bottom = b.bottom - h * .16f;
        float radius = Math.min(w, h) * .10f;
        int layers = Math.min(3, Math.max(1, count));
        int[] colors = { 0xFF9CB6E8, 0xFF7898D0, 0xFF5579B8 };
        for (int i = layers - 1; i >= 0; i--) {
            float dx = (layers - 1 - i) * w * .075f;
            float dy = (layers - 1 - i) * h * .075f;
            paint.setColor(colors[Math.min(i, colors.length - 1)]);
            canvas.drawRoundRect(
                new RectF(left + dx, top + dy, right + dx, bottom + dy),
                radius, radius, paint
            );
        }
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(
            new RectF(left + w * .10f, top + h * .22f,
                left + w * .42f, top + h * .31f),
            radius * .45f, radius * .45f, paint
        );
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter); invalidateSelf();
    }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
