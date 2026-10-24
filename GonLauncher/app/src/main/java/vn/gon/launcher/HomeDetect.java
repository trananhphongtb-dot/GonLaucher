package vn.gon.launcher;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Process;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Nhận biết màn hình chính gốc của tivi (launcher Coocaa / Google). */
final class HomeDetect {
    private final Set<String> pkgs = new HashSet<>();
    private final Set<String> classes = new HashSet<>();

    void refresh(Context c) {
        pkgs.clear();
        classes.clear();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(i, 0);
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || c.getPackageName().equals(ai.packageName)) continue;
            String p = ai.packageName.toLowerCase(Locale.ROOT);
            if (p.contains("setupwizard") || p.contains("provision") || p.equals("com.android.settings")
                    || p.contains("tv.settings")) continue;
            pkgs.add(ai.packageName);
            classes.add(ai.name);
            if (ai.targetActivity != null) classes.add(ai.targetActivity);
        }
    }

    boolean isEmpty() { return pkgs.isEmpty(); }

    boolean isHome(String pkg, String cls) {
        if (pkg == null || cls == null || !pkgs.contains(pkg)) return false;
        if (classes.contains(cls)) return true;
        // Một số launcher gốc mở tiếp màn hình chính khác trong cùng gói
        String simple = cls.substring(cls.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return cls.startsWith(pkg) && (simple.contains("home") || simple.contains("launcher")
                || simple.equals("mainactivity"));
    }

    /** Đã được cấp quyền "Truy cập dữ liệu sử dụng" chưa. */
    static boolean usageGranted(Context c) {
        try {
            AppOpsManager aom = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return c.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }
}
