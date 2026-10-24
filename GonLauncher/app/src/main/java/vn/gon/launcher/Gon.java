package vn.gon.launcher;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.Calendar;
import java.util.Locale;

/** Tiện ích dùng chung cho launcher và màn hình chờ. */
final class Gon {
    private Gon() { }

    private static final String[] THU = {"", "Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};

    /** 01:02 */
    static String time() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** Chủ Nhật 25-12-2026 */
    static String date() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.ROOT, "%s %02d-%02d-%d", THU[c.get(Calendar.DAY_OF_WEEK)],
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    static final int[] WALLS = {R.drawable.wall_dusk, R.drawable.wall_night, R.drawable.wall_dawn};

    static Bitmap wall(Resources r, int index) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inScaled = false;
        return BitmapFactory.decodeResource(r, WALLS[((index % WALLS.length) + WALLS.length) % WALLS.length], o);
    }

    /** Màu chủ đạo của icon, làm tối đi để làm nền ô (giống Android TV). */
    static int tileColor(Drawable d) {
        if (d == null) return 0xFF2A3440;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(cv);
            long r = 0, g = 0, bl = 0, n = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 160) continue;
                Color.colorToHSV(p, hsv);
                int w = hsv[1] > 0.25f ? 3 : 1; // ưu tiên điểm có màu, bỏ qua viền trắng/xám
                r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; n += w;
            }
            b.recycle();
            if (n == 0) return 0xFF2A3440;
            int c = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(hsv[1], 0.75f);
            hsv[2] = Math.min(Math.max(hsv[2] * 0.6f, 0.18f), 0.42f);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return 0xFF2A3440;
        }
    }
}
