package vn.gon.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dịch vụ "Nút Home": khi tivi không cho đổi launcher mặc định,
 * mỗi lần launcher gốc hiện lên (bấm Home, bật tivi) sẽ mở ngay Gọn Launcher.
 * Chỉ nghe sự kiện đổi màn hình, không đọc nội dung màn hình.
 */
public class HomeService extends AccessibilityService {

    private final Set<String> homePkgs = new HashSet<>();
    private final Set<String> homeClasses = new HashSet<>();
    private long last = 0;

    @Override
    protected void onServiceConnected() {
        refresh();
    }

    private void refresh() {
        homePkgs.clear();
        homeClasses.clear();
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || getPackageName().equals(ai.packageName)) continue;
            // Bỏ qua trình cài đặt ban đầu / Settings fallback
            String p = ai.packageName.toLowerCase(Locale.ROOT);
            if (p.contains("setupwizard") || p.contains("provision") || p.equals("com.android.settings")
                    || p.contains("tv.settings")) continue;
            homePkgs.add(ai.packageName);
            homeClasses.add(ai.name);
            if (ai.targetActivity != null) homeClasses.add(ai.targetActivity);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null) return;
        if (homePkgs.isEmpty()) refresh();
        String p = pkg.toString();
        if (!homePkgs.contains(p)) return;

        String c = cls.toString();
        boolean isHome = homeClasses.contains(c);
        if (!isHome) {
            // Một số launcher gốc mở tiếp một màn hình chính khác trong cùng gói
            String simple = c.substring(c.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            isHome = c.startsWith(p) && (simple.contains("home") || simple.contains("launcher")
                    || simple.equals("mainactivity"));
        }
        if (!isHome) return;

        long now = SystemClock.elapsedRealtime();
        if (now - last < 800) return;
        last = now;
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) { }
    }

    @Override
    public void onInterrupt() { }
}
