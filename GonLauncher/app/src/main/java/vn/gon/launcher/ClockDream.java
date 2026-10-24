package vn.gon.launcher;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.SharedPreferences;
import android.view.animation.LinearInterpolator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Màn hình chờ Gọn Launcher: ảnh phong cảnh chuyển mờ dần, trôi chậm, kèm đồng hồ.
 * Chọn ở: Cài đặt → Tùy chọn thiết bị → Màn hình chờ (Trình bảo vệ màn hình) → Gọn Launcher.
 */
public class ClockDream extends DreamService {

    private static final long SLIDE_MS = 25000;   // mỗi ảnh 25 giây
    private static final long FADE_MS = 2500;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<String> sources = new ArrayList<>();
    private ImageView a, b;
    private TextView time, date;
    private int index = 0;
    private boolean showingA = true;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            time.setText(Gon.time());
            date.setText(Gon.date());
            h.postDelayed(this, 15000);
        }
    };

    private final Runnable next = new Runnable() {
        @Override public void run() {
            showNext();
            h.postDelayed(this, SLIDE_MS);
        }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        a = new ImageView(this);
        b = new ImageView(this);
        for (ImageView iv : new ImageView[]{a, b}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            root.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.HORIZONTAL);
        clock.setBaselineAligned(true);
        time = new TextView(this);
        time.setTextSize(64);
        time.setTextColor(0xFFFFFFFF);
        time.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        time.setShadowLayer(8, 0, 0, 0xAA000000);
        date = new TextView(this);
        date.setTextSize(26);
        date.setTextColor(0xE6FFFFFF);
        date.setShadowLayer(8, 0, 0, 0xAA000000);
        date.setPadding(dp(22), 0, 0, 0);
        clock.addView(time);
        clock.addView(date);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = dp(56);
        lp.bottomMargin = dp(44);
        root.addView(clock, lp);
        setContentView(root);

        // Ảnh: 3 ảnh có sẵn + ảnh riêng anh đã chọn làm hình nền (nếu có)
        sources.add("img:0");
        sources.add("img:1");
        sources.add("img:2");
        SharedPreferences p = getSharedPreferences("gon", MODE_PRIVATE);
        String bg = p.getString("bg", "");
        if (bg.startsWith("file:")) sources.add(0, bg);
        else if (bg.startsWith("img")) {
            try { index = Integer.parseInt(bg.substring(3)) - 1; } catch (Exception ignored) { }
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        h.post(tick);
        h.post(next);
    }

    @Override
    public void onDreamingStopped() {
        h.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private void showNext() {
        final String src = sources.get(((index % sources.size()) + sources.size()) % sources.size());
        index++;
        new Thread(() -> {
            final Bitmap bm = load(src);
            h.post(() -> {
                if (bm == null) return;
                final ImageView in = showingA ? a : b;
                final ImageView out = showingA ? b : a;
                showingA = !showingA;
                in.setImageBitmap(bm);
                // hiệu ứng trôi chậm (Ken Burns): phóng nhẹ + lướt ngang
                float dir = (index % 2 == 0) ? 1f : -1f;
                in.animate().cancel();
                out.animate().cancel();
                in.setScaleX(1.0f); in.setScaleY(1.0f);
                in.setTranslationX(-dir * dp(20));
                in.setAlpha(0f);
                in.bringToFront();
                ((ViewGroup) time.getParent()).bringToFront(); // đồng hồ luôn nằm trên
                ObjectAnimator.ofFloat(in, "alpha", 0f, 1f).setDuration(FADE_MS).start();
                AnimatorSet drift = new AnimatorSet();
                drift.playTogether(
                        ObjectAnimator.ofFloat(in, "scaleX", 1f, 1.12f),
                        ObjectAnimator.ofFloat(in, "scaleY", 1f, 1.12f),
                        ObjectAnimator.ofFloat(in, "translationX", -dir * dp(20), dir * dp(20)));
                drift.setDuration(SLIDE_MS + FADE_MS);
                drift.setInterpolator(new LinearInterpolator());
                drift.start();
                h.postDelayed(() -> {
                    out.setAlpha(0f);
                    out.setImageDrawable(null); // giải phóng RAM ảnh cũ
                }, FADE_MS + 200);
            });
        }, "dream-load").start();
    }

    private Bitmap load(String src) {
        try {
            if (src.startsWith("img:")) return Gon.wall(getResources(), Integer.parseInt(src.substring(4)));
            String path = src.substring(5);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int target = Math.max(dm.widthPixels, dm.heightPixels);
            int s = 1;
            while (o.outWidth / (s * 2) >= target) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            return null;
        }
    }
}
