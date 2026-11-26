package com.edu.guide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/** 화면 전체를 덮는 투명한 그림판. 터치는 통과시키고 테두리와 말풍선만 그린다. */
public class OverlayView extends View {

    private Rect target;          // 화면 좌표
    private String bubble;        // 말풍선 큰 글
    private String sub;           // 말풍선 작은 글
    private String banner;        // 대상이 없을 때 위쪽 안내
    private float pulse = 0f;

    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];

    public OverlayView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        box.setStyle(Paint.Style.STROKE);
        box.setColor(Color.rgb(255, 59, 48));
        box.setStrokeWidth(5 * d);
        bubbleBg.setColor(Color.WHITE);
        bubbleLine.setStyle(Paint.Style.STROKE);
        bubbleLine.setColor(Color.rgb(255, 59, 48));
        bubbleLine.setStrokeWidth(3 * d);
        big.setColor(Color.rgb(25, 25, 25));
        big.setTextSize(24 * d);
        big.setFakeBoldText(true);
        small.setColor(Color.rgb(90, 90, 90));
        small.setTextSize(16 * d);
    }

    public void show(Rect screenRect, String bubbleText, String subText) {
        target = screenRect == null ? null : new Rect(screenRect);
        bubble = bubbleText;
        sub = subText;
        banner = null;
        invalidate();
    }

    public void showBanner(String text, String subText) {
        target = null;
        bubble = null;
        banner = text;
        sub = subText;
        invalidate();
    }

    public void clear() {
        target = null;
        bubble = null;
        banner = null;
        sub = null;
        invalidate();
    }

    public void setPulse(float p) {
        pulse = p;
        if (target != null) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = getResources().getDisplayMetrics().density;
        getLocationOnScreen(loc);
        int w = getWidth();

        if (target != null) {
            RectF r = new RectF(target);
            r.offset(-loc[0], -loc[1]);
            float grow = 4 * d + pulse * 6 * d;
            r.inset(-grow, -grow);
            box.setAlpha((int) (255 * (0.65f + 0.35f * (1 - pulse))));
            c.drawRoundRect(r, 18 * d, 18 * d, box);

            if (bubble != null) {
                drawBubble(c, d, w, r);
            }
        } else if (banner != null) {
            float pad = 16 * d;
            float top = getHeight() * 0.12f;
            drawCard(c, d, pad, top, w - pad, banner, sub, null);
        }
    }

    private void drawBubble(Canvas c, float d, int w, RectF r) {
        float pad = 16 * d;
        float cardW = w - pad * 2;
        StaticLayout bl = layout(bubble, big, (int) (cardW - pad * 2));
        StaticLayout sl = sub == null ? null : layout(sub, small, (int) (cardW - pad * 2));
        float cardH = pad * 2 + bl.getHeight() + (sl == null ? 0 : sl.getHeight() + 6 * d);

        boolean below = r.bottom + 14 * d + cardH < getHeight() - 40 * d;
        float top = below ? r.bottom + 14 * d : r.top - 14 * d - cardH;
        if (top < 0) top = 8 * d;

        // 꼬리 삼각형
        float cx = Math.max(pad + 24 * d, Math.min(w - pad - 24 * d, r.centerX()));
        Path tail = new Path();
        if (below) {
            tail.moveTo(cx - 14 * d, top + 1);
            tail.lineTo(cx + 14 * d, top + 1);
            tail.lineTo(cx, top - 14 * d);
        } else {
            tail.moveTo(cx - 14 * d, top + cardH - 1);
            tail.lineTo(cx + 14 * d, top + cardH - 1);
            tail.lineTo(cx, top + cardH + 14 * d);
        }
        tail.close();
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.rgb(255, 59, 48));
        c.drawPath(tail, tp);

        drawCard(c, d, pad, top, pad + cardW, bubble, sub, bl);
    }

    private void drawCard(Canvas c, float d, float left, float top, float right,
                          String title, String subText, StaticLayout pre) {
        float pad = 16 * d;
        int innerW = (int) (right - left - pad * 2);
        StaticLayout bl = pre != null ? pre : layout(title, big, innerW);
        StaticLayout sl = subText == null ? null : layout(subText, small, innerW);
        float h = pad * 2 + bl.getHeight() + (sl == null ? 0 : sl.getHeight() + 6 * d);
        RectF card = new RectF(left, top, right, top + h);
        c.drawRoundRect(card, 20 * d, 20 * d, bubbleBg);
        c.drawRoundRect(card, 20 * d, 20 * d, bubbleLine);
        c.save();
        c.translate(left + pad, top + pad);
        bl.draw(c);
        if (sl != null) {
            c.translate(0, bl.getHeight() + 6 * d);
            sl.draw(c);
        }
        c.restore();
    }

    private static StaticLayout layout(String s, TextPaint p, int width) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(width, 10))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build();
    }
}
