package vn.gon.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Cách 2 cho nút Home (khi tivi giấu mục Trợ năng):
 * dùng quyền "Truy cập dữ liệu sử dụng" để biết màn hình Coocaa vừa mở -> chuyển sang Gọn Launcher.
 * Chỉ kiểm tra 2 lần/giây, rất nhẹ.
 */
public class HomeWatch extends Service {

    private static final long PERIOD = 500;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final HomeDetect detect = new HomeDetect();
    private UsageStatsManager usm;
    private long lastHandled = 0;
    private long lastRefresh = 0;

    static void start(Context c) {
        if (!HomeDetect.usageGranted(c) || MainActivity.homeServiceOn(c)) return;
        try {
            Intent i = new Intent(c, HomeWatch.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            check();
            h.postDelayed(this, PERIOD);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("home", "Nút Home",
                    NotificationManager.IMPORTANCE_MIN));
            n = new Notification.Builder(this, "home")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Gọn Launcher")
                    .setContentText("Nút Home mở Gọn Launcher")
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Gọn Launcher")
                    .setPriority(Notification.PRIORITY_MIN)
                    .build();
        }
        startForeground(7, n);
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        lastHandled = System.currentTimeMillis();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        h.removeCallbacks(loop);
        h.post(loop);
        return START_STICKY;
    }

    private void check() {
        if (!HomeDetect.usageGranted(this) || MainActivity.homeServiceOn(this)) { stopSelf(); return; }
        long now = System.currentTimeMillis();
        if (detect.isEmpty() || now - lastRefresh > 60000) { detect.refresh(this); lastRefresh = now; }
        UsageEvents ev;
        try { ev = usm.queryEvents(now - 4000, now); } catch (Exception e) { return; }
        if (ev == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null, cls = null;
        long ts = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                pkg = e.getPackageName();
                cls = e.getClassName();
                ts = e.getTimeStamp();
            }
        }
        if (pkg == null || ts <= lastHandled) return;
        lastHandled = ts;
        if (detect.isHome(pkg, cls)) {
            try {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
