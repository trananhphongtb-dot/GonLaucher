package vn.gon.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.KeyPair;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Màn hình "Dọn dẹp tivi": bật/tắt app hệ thống, app rác bằng ADB chạy ngay trên tivi.
 * Các gói cốt lõi (Cài đặt, bàn phím, Wi-Fi, HDMI, chip TV...) bị khoá, không hiện ra để tránh hỏng máy.
 */
public class CleanActivity extends Activity {

    /** Gợi ý tắt: an toàn trên Android TV 9. */
    private static final String[] SUGGEST = {
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            "com.google.android.leanbacklauncher.recommendations",
            "com.google.android.tvrecommendations",
            "com.google.android.feedback",
            "com.google.android.music",
            "com.google.android.videos",
            "com.google.android.play.games",
            "com.google.android.youtube.tvmusic",
            "com.google.android.apps.mediashell",
            "com.android.printspooler",
            "com.android.dreams.basic",
            "com.android.gallery3d",
            "com.android.email",
            "com.android.calendar",
            "com.android.musicfx",
    };

    /** Từ khoá của các gói KHÔNG được đụng tới. */
    private static final String[] LOCK_WORDS = {
            "systemui", "settings", "setting", "inputmethod", "keyboard", "latin", "ime",
            "packageinstaller", "permission", "webview", "gms", "gsf", "vending", "bluetooth",
            "wifi", "network", "ethernet", "connectivity", "tethering", "provider", "media",
            "tvinput", "tv.input", "hdmi", "source", "input", "remote", "keychain", "shell",
            "certinstaller", "defcontainer", "provision", "setupwizard", "setup", "factory",
            "mstar", "realtek", "mediatek", "mtk", "amlogic", "hisilicon", "tvos", "tvservice",
            "tv.service", "framework", "server", "ext.services", "ext.shared", "telephony",
            "phone", "audio", "display", "storage", "documentsui", "location", "time",
            "update", "ota", "katniss", "atv", "dtv", "livetv", "tv.player", "player",
            "pq", "picture", "sound", "power", "standby", "cec", "launcher3"
    };

    static final class App {
        String pkg, label;
        boolean on, suggested;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Adb adb = new Adb();
    private final List<App> apps = new ArrayList<>();
    private SharedPreferences prefs;
    private TextView status;
    private ListView list;
    private RowAdapter adapter;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("gon", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(dp(48), dp(24), dp(48), dp(16));

        TextView title = new TextView(this);
        title.setText("Dọn dẹp tivi");
        title.setTextColor(0xFFF2F4F6);
        title.setTextSize(30);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF9AA4AE);
        status.setTextSize(16);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        addBtn(bar, "Tắt nhóm gợi ý", v -> disableSuggested());
        addBtn(bar, "Giải phóng RAM", v -> run("am kill-all", "Đã dừng các app chạy ngầm"));
        addBtn(bar, "Tăng tốc hiệu ứng", v -> run(
                "settings put global window_animation_scale 0.5;" +
                "settings put global transition_animation_scale 0.5;" +
                "settings put global animator_duration_scale 0.5", "Đã giảm hiệu ứng chuyển cảnh"));
        addBtn(bar, "Bật lại tất cả", v -> confirmRestore());
        root.addView(bar);

        TextView note = new TextView(this);
        note.setText("Bấm OK vào từng app để BẬT / TẮT.  ★ = nên tắt.  Các thành phần hệ thống quan trọng đã được khoá, không hiện ở đây.");
        note.setTextColor(0xFF9AA4AE);
        note.setTextSize(15);
        note.setPadding(0, dp(12), 0, dp(8));
        root.addView(note);

        list = new ListView(this);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setDividerHeight(0);
        list.setOnItemClickListener((p, v, pos, id) -> toggle(apps.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        loadList();
        connect();
    }

    @Override
    protected void onDestroy() {
        bg.execute(adb::close);
        bg.shutdown();
        super.onDestroy();
    }

    private void addBtn(LinearLayout bar, String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(16);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(10);
        bar.addView(btn, lp);
    }

    private void setStatus(final String s) { ui.post(() -> status.setText(s)); }

    private void toast(final String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    // ------------------------------------------------------------ danh sách app

    static boolean locked(String pkg, String me) {
        if (pkg.equals(me) || pkg.equals("android")) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String w : LOCK_WORDS) if (p.contains(w)) return true;
        return false;
    }

    private static boolean isSuggested(String pkg) {
        for (String s : SUGGEST) if (s.equals(pkg)) return true;
        return false;
    }

    private void loadList() {
        bg.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            final List<App> res = new ArrayList<>();
            String me = getPackageName();
            for (ApplicationInfo ai : all) {
                boolean sug = isSuggested(ai.packageName);
                if (!sug && locked(ai.packageName, me)) continue;
                App a = new App();
                a.pkg = ai.packageName;
                CharSequence l = ai.loadLabel(pm);
                a.label = l != null ? l.toString() : ai.packageName;
                a.on = isOn(pm, ai.packageName);
                a.suggested = sug;
                res.add(a);
            }
            final Collator col = Collator.getInstance(new Locale("vi"));
            Collections.sort(res, (x, y) -> {
                if (x.suggested != y.suggested) return x.suggested ? -1 : 1;
                return col.compare(x.label, y.label);
            });
            ui.post(() -> {
                apps.clear();
                apps.addAll(res);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private boolean isOn(PackageManager pm, String pkg) {
        try {
            int st = pm.getApplicationEnabledSetting(pkg);
            if (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                return pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
            }
            return st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------ ADB

    /** Kết nối (chạy trên luồng nền). Trả về false nếu không được. */
    private boolean ensure() {
        if (adb.isOpen()) return true;
        setStatus("Đang kết nối ADB của tivi…");
        try {
            KeyPair kp = Adb.keys(prefs);
            adb.connect(kp, () -> setStatus(
                    "Trên tivi hiện bảng \"Cho phép gỡ lỗi\": tick ô \"Luôn cho phép\" rồi bấm CHO PHÉP."));
            adbOk = true;
            setStatus("Đã kết nối ADB. Bấm OK vào app để bật / tắt ngay.");
            return true;
        } catch (Exception e) {
            adb.close();
            adbOk = false;
            setStatus("Chế độ thủ công (tivi chưa mở ADB): bấm OK vào app → trang Thông tin app → chọn TẮT hoặc BUỘC DỪNG.");
            ui.post(this::showAdbHelp);
            return false;
        }
    }

    private void connect() { bg.execute(this::ensure); }

    private void run(final String cmd, final String done) {
        bg.execute(() -> {
            if (!ensure()) return;
            try {
                adb.shell(cmd);
                toast(done);
            } catch (Exception e) {
                adb.close();
                toast("Lỗi: " + e.getMessage());
            }
        });
    }

    private volatile boolean adbOk = false;

    /** Không có ADB: mở trang Thông tin app để bấm Tắt / Buộc dừng bằng tay. */
    private void openInfo(App a) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + a.pkg)));
        } catch (Exception e) {
            toast("Tivi không mở được trang thông tin app");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadList(); // cập nhật trạng thái sau khi người dùng tắt app bằng tay
    }

    private void toggle(final App a) {
        if (!adbOk) { openInfo(a); return; }
        if (!a.on) { setApp(a, true); return; }
        if (a.pkg.contains("launcher") && !isDefaultHome()) {
            new AlertDialog.Builder(this)
                    .setTitle("Chưa đặt Gọn Launcher làm mặc định")
                    .setMessage("Vẫn tắt launcher gốc? Sau khi tắt, nút Home sẽ mở Gọn Launcher.")
                    .setPositiveButton("Tắt", (d, w) -> setApp(a, false))
                    .setNegativeButton("Huỷ", null).show();
            return;
        }
        setApp(a, false);
    }

    private boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void setApp(final App a, final boolean on) {
        bg.execute(() -> {
            if (!ensure()) return;
            try {
                adb.shell(on ? "pm enable " + a.pkg : "pm disable-user --user 0 " + a.pkg);
                remember(a.pkg, !on);
                final boolean now = isOn(getPackageManager(), a.pkg);
                ui.post(() -> { a.on = now; adapter.notifyDataSetChanged(); });
                if (now == on) toast((on ? "Đã bật " : "Đã tắt ") + a.label);
                else toast("Tivi không cho " + (on ? "bật " : "tắt ") + a.label);
            } catch (Exception e) {
                adb.close();
                toast("Lỗi: " + e.getMessage());
            }
        });
    }

    /** Ghi nhớ các app do mình tắt để "Bật lại tất cả". */
    private void remember(String pkg, boolean disabled) {
        Set<String> s = new HashSet<>(prefs.getStringSet("disabled_by_me", new HashSet<>()));
        if (disabled) s.add(pkg); else s.remove(pkg);
        prefs.edit().putStringSet("disabled_by_me", s).apply();
    }

    private void disableSuggested() {
        if (!adbOk) { toast("Cần ADB để tắt hàng loạt. Ở chế độ thủ công, hãy bấm vào từng app có ★"); return; }
        final List<App> todo = new ArrayList<>();
        for (App a : apps) if (a.suggested && a.on) todo.add(a);
        if (todo.isEmpty()) { toast("Các app gợi ý đã tắt hết rồi"); return; }
        StringBuilder sb = new StringBuilder();
        for (App a : todo) sb.append("• ").append(a.label).append('\n');
        new AlertDialog.Builder(this)
                .setTitle("Tắt " + todo.size() + " app?")
                .setMessage(sb.toString())
                .setPositiveButton("Tắt", (d, w) -> { for (App a : todo) setApp(a, false); })
                .setNegativeButton("Huỷ", null).show();
    }

    private void confirmRestore() {
        final Set<String> s = new HashSet<>(prefs.getStringSet("disabled_by_me", new HashSet<>()));
        if (s.isEmpty()) { toast("Chưa có app nào bị tắt bởi Gọn Launcher"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Bật lại " + s.size() + " app đã tắt?")
                .setPositiveButton("Bật lại", (d, w) -> bg.execute(() -> {
                    if (!ensure()) return;
                    try {
                        for (String p : s) { adb.shell("pm enable " + p); remember(p, false); }
                        toast("Đã bật lại tất cả");
                        loadList();
                    } catch (Exception e) {
                        adb.close();
                        toast("Lỗi: " + e.getMessage());
                    }
                }))
                .setNegativeButton("Huỷ", null).show();
    }

    private boolean helpShown = false;

    private void showAdbHelp() {
        if (helpShown || isFinishing()) return;
        helpShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Cần bật Gỡ lỗi ADB")
                .setMessage("1. Cài đặt → Thiết bị → Giới thiệu → bấm 7 lần vào \"Bản dựng\".\n" +
                        "2. Quay lại → Tùy chọn nhà phát triển → bật \"Gỡ lỗi USB\" (và \"Gỡ lỗi qua mạng/ADB qua mạng\" nếu có).\n" +
                        "3. Mở lại màn hình Dọn dẹp và bấm CHO PHÉP khi tivi hỏi.")
                .setPositiveButton("Mở Cài đặt", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (Exception e) {
                        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("Để sau", null).show();
    }

    // ------------------------------------------------------------ adapter

    class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            LinearLayout row;
            TextView state, name;
            if (v == null) {
                row = new LinearLayout(CleanActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                state = new TextView(CleanActivity.this);
                state.setTextSize(16);
                state.setGravity(Gravity.CENTER);
                row.addView(state, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
                name = new TextView(CleanActivity.this);
                name.setTextSize(18);
                name.setTextColor(0xFFF2F4F6);
                name.setPadding(dp(16), 0, 0, 0);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(new TextView[]{state, name});
                v = row;
            }
            TextView[] t = (TextView[]) v.getTag();
            state = t[0];
            name = t[1];
            App a = apps.get(p);
            state.setText(a.on ? "ĐANG BẬT" : "ĐÃ TẮT");
            state.setTextColor(a.on ? 0xFF81C784 : 0xFFE57373);
            name.setText((a.suggested ? "★ " : "") + a.label + "\n" + a.pkg);
            name.setTextColor(a.on ? 0xFFF2F4F6 : Color.GRAY);
            return v;
        }
    }
}
