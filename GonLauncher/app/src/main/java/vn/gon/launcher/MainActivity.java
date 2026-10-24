package vn.gon.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gọn Launcher – màn hình chính siêu nhẹ cho Android TV.
 * Hình nền toàn màn hình, đồng hồ góc trên, một hàng app gọn dưới đáy.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "gon";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_BG = "bg";

    private static final int TYPE_APP = 0, TYPE_SETTINGS = 1, TYPE_HIDDEN = 2, TYPE_CLEAN = 3, TYPE_WALL = 4, TYPE_HOMEFIX = 5;

    static class Item {
        int type = TYPE_APP;
        String label, pkg;
        ComponentName comp;
        Drawable banner, icon;
        int color = 0xFF2A3440;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final List<Item> hiddenApps = new ArrayList<>();
    private final List<View> tiles = new ArrayList<>();
    private LinearLayout row;
    private HorizontalScrollView scroller;
    private ImageView wallpaper;
    private LayoutInflater inf;
    private SharedPreferences prefs;
    private volatile boolean loading = false;
    private boolean needReload = false;
    private int focusIndex = 0;
    private boolean askedHome = false;

    private TextView timeView, dateView;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private void updateClock() {
        timeView.setText(Gon.time());
        dateView.setText(Gon.date());
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (hasWindowFocus()) loadApps(); else needReload = true;
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        inf = LayoutInflater.from(this);
        row = findViewById(R.id.row);
        scroller = findViewById(R.id.scroller);
        wallpaper = findViewById(R.id.wallpaper);
        timeView = findViewById(R.id.time);
        dateView = findViewById(R.id.date);
        updateClock();
        IntentFilter cf = new IntentFilter(Intent.ACTION_TIME_TICK);
        cf.addAction(Intent.ACTION_TIME_CHANGED);
        cf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, cf);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        // Quyền đọc bộ nhớ: để lấy hình nền của tivi và ảnh trong USB
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 33
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }

        applyBackground();
        loadApps();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        applyBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needReload) { needReload = false; loadApps(); }
        else if (!items.isEmpty() && !isDefaultHome()
                && (items.get(0).type == TYPE_HOMEFIX) == homeFixOn()) loadApps();
        HomeWatch.start(this);
        updateClock();
        focusTile(focusIndex);
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.ver)).setText("Gọn Launcher " + v + "  •  Nút Home: "
                + (isDefaultHome() ? "mặc định" : homeServiceOn(this) ? "BẬT (trợ năng)"
                : HomeDetect.usageGranted(this) ? "BẬT (theo dõi)" : "TẮT"));
        ui.postDelayed(this::askHomeService, 1500);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(clockReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusTile(0);
    }

    @Override
    public void onBackPressed() { focusTile(0); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showOptions(focusIndex);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    // ------------------------------------------------------------------ nút Home

    private boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Một trong 2 cách giữ nút Home đã bật chưa. */
    private boolean homeFixOn() {
        return homeServiceOn(this) || HomeDetect.usageGranted(this);
    }

    static boolean homeServiceOn(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/");
    }

    /** Tivi không cho đặt mặc định -> mời bật dịch vụ "Nút Home mở Gọn Launcher". */
    private void askHomeService() {
        if (askedHome || isFinishing()) return;
        if (isDefaultHome() || homeFixOn() || prefs.getBoolean("no_ask_home", false)) return;
        askedHome = true;
        new AlertDialog.Builder(this)
                .setTitle("Cho nút Home mở Gọn Launcher")
                .setMessage("Tivi không cho đặt launcher mặc định. Chọn 1 trong 2 cách:\n\n" +
                        "CÁCH 1 – Quyền sử dụng (nên thử trước):\n" +
                        "Bấm \"Cách 1\" → tìm Gọn Launcher → bật \"Cho phép truy cập dữ liệu sử dụng\".\n" +
                        "(Nếu không tự mở: Cài đặt → Ứng dụng → Quyền truy cập đặc biệt → Truy cập dữ liệu sử dụng)\n\n" +
                        "CÁCH 2 – Trợ năng:\n" +
                        "Bấm \"Cách 2\" → tìm \"Gọn Launcher – Nút Home\" → Bật → OK.\n\n" +
                        "Xong bấm Back quay lại: góc trên phải sẽ báo \"Nút Home: BẬT\".")
                .setPositiveButton("Cách 1", (d, w) -> openUsageAccess())
                .setNeutralButton("Cách 2", (d, w) -> openAccessibility())
                .setNegativeButton("Để sau", null)
                .show();
    }

    private void openUsageAccess() {
        Intent[] tries = {
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setPackage("com.android.settings"),
                new Intent().setClassName("com.android.tv.settings",
                        "com.android.tv.settings.device.apps.specialaccess.SpecialAppAccessActivity"),
                new Intent(Settings.ACTION_APPLICATION_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, "Chọn Gọn Launcher → bật Cho phép", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) { }
        }
        Toast.makeText(this, "Tivi không mở được mục này", Toast.LENGTH_LONG).show();
    }

    /**
     * Cài đặt của Android TV chỉ hiện TalkBack, không hiện dịch vụ của app ngoài.
     * Thử mở trang Trợ năng kiểu điện thoại (thường còn ẩn trong tivi) – trang này hiện đủ dịch vụ.
     */
    private void openAccessibility() {
        Intent[] tries = {
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$AccessibilitySettingsActivity"),
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).setPackage("com.android.settings"),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$AccessibilitySettingsActivity").setAction(Intent.ACTION_MAIN),
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        };
        for (int i = 0; i < tries.length; i++) {
            try {
                tries[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(tries[i]);
                if (i == tries.length - 1) {
                    Toast.makeText(this, "Tivi không có trang Trợ năng đầy đủ. Nếu chỉ thấy TalkBack, hãy chụp màn hình báo lại.",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Tìm mục \"Gọn Launcher – Nút Home\" → Bật → OK", Toast.LENGTH_LONG).show();
                }
                return;
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ hình nền

    private void applyBackground() {
        final String mode = prefs.getString(KEY_BG, "img1");
        new Thread(() -> {
            Drawable d = null;
            try {
                if (mode.startsWith("file:")) {
                    Bitmap bm = decodeScaled(mode.substring(5));
                    if (bm != null) d = new BitmapDrawable(getResources(), bm);
                } else if (mode.startsWith("grad")) {
                    d = gradient(mode);
                } else if (mode.startsWith("img")) {
                    d = builtIn(mode);
                } else {
                    d = tvWallpaper();
                }
            } catch (Throwable ignored) { }
            if (d == null) d = builtIn("img1");
            if (d == null) d = gradient("grad1");
            final Drawable fd = d;
            ui.post(() -> wallpaper.setImageDrawable(fd));
        }, "wallpaper").start();
    }

    /** Hình nền đang cài trên tivi, nếu không có thì hình nền gốc của hệ thống. */
    private Drawable tvWallpaper() {
        WallpaperManager wm = WallpaperManager.getInstance(this);
        Drawable d = null;
        try { d = wm.getDrawable(); } catch (Throwable ignored) { }
        if (d == null) {
            try { d = wm.getBuiltInDrawable(); } catch (Throwable ignored) { }
        }
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src == null || src.getWidth() < 64) return null; // ảnh đen 1 điểm -> bỏ
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = Math.max(dm.widthPixels, dm.heightPixels);
            if (src.getWidth() > w) {
                int h = (int) ((long) src.getHeight() * w / src.getWidth());
                Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
                try { wm.forgetLoadedWallpaper(); } catch (Throwable ignored) { }
                return new BitmapDrawable(getResources(), small);
            }
        }
        return d;
    }

    /** 3 ảnh phong cảnh có sẵn trong launcher. */
    private Drawable builtIn(String which) {
        int id;
        switch (which) {
            case "img2": id = R.drawable.wall_night; break;
            case "img3": id = R.drawable.wall_dawn; break;
            default:     id = R.drawable.wall_dusk; break;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inScaled = false;
        Bitmap bm = BitmapFactory.decodeResource(getResources(), id, o);
        return bm != null ? new BitmapDrawable(getResources(), bm) : null;
    }

    private Bitmap decodeScaled(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0) return null;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int target = Math.max(dm.widthPixels, dm.heightPixels);
        int s = 1;
        while (o.outWidth / (s * 2) >= target) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        o2.inPreferredConfig = Bitmap.Config.RGB_565; // tiết kiệm RAM
        return BitmapFactory.decodeFile(path, o2);
    }

    private static Drawable gradient(String which) {
        int[] c;
        switch (which) {
            case "grad2": c = new int[]{0xFF0F2027, 0xFF203A43, 0xFF2C5364}; break;   // xanh biển đêm
            case "grad3": c = new int[]{0xFF1A1A2E, 0xFF3A1C71, 0xFF7B4397}; break;   // tím
            case "grad4": c = new int[]{0xFF134E5E, 0xFF2E7D5B, 0xFF71B280}; break;   // xanh lá
            default:      c = new int[]{0xFF141E30, 0xFF243B55, 0xFF3A6073}; break;   // xanh xám
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
        return g;
    }

    private void chooseBackground() {
        final String[] opts = {"Hoàng hôn trên núi", "Đêm trăng bên hồ", "Bình minh trong sương",
                "Hình nền của tivi", "Chọn ảnh trong USB / bộ nhớ",
                "Màu xanh xám", "Màu xanh biển đêm", "Màu tím", "Màu xanh lá",
                "Cài màn hình chờ Gọn Launcher…"};
        new AlertDialog.Builder(this)
                .setTitle("Hình nền")
                .setItems(opts, (d, w) -> {
                    if (w <= 2) setBg("img" + (w + 1));
                    else if (w == 3) setBg("tv");
                    else if (w == 4) pickImage();
                    else if (w == opts.length - 1) openDreamSettings();
                    else setBg("grad" + (w - 4));
                }).show();
    }

    private void openDreamSettings() {
        Toast.makeText(this, "Chọn \"Gọn Launcher\" trong danh sách màn hình chờ", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    private void setBg(String mode) {
        prefs.edit().putString(KEY_BG, mode).apply();
        applyBackground();
    }

    /** Tìm ảnh trong bộ nhớ trong và USB (không cần app quản lý file). */
    private void pickImage() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 33
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            Toast.makeText(this, "Hãy cho phép đọc bộ nhớ rồi chọn lại", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Đang tìm ảnh…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<File> found = new ArrayList<>();
            List<File> roots = new ArrayList<>();
            roots.add(Environment.getExternalStorageDirectory());
            File[] vols = new File("/storage").listFiles();
            if (vols != null) for (File v : vols) {
                String n = v.getName();
                if (!n.equals("emulated") && !n.equals("self") && v.canRead()) roots.add(v);
            }
            for (File r : roots) scan(r, 0, found);
            ui.post(() -> {
                if (found.isEmpty()) {
                    Toast.makeText(this, "Không thấy ảnh .jpg/.png nào. Hãy chép ảnh vào USB rồi cắm vào tivi.", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[found.size()];
                for (int i = 0; i < names.length; i++) {
                    File fl = found.get(i);
                    names[i] = fl.getName() + "   (" + fl.getParentFile().getName() + ")";
                }
                new AlertDialog.Builder(this)
                        .setTitle("Chọn ảnh nền")
                        .setItems(names, (d, w) -> setBg("file:" + found.get(w).getAbsolutePath()))
                        .show();
            });
        }, "scan").start();
    }

    private void scan(File dir, int depth, List<File> out) {
        if (dir == null || depth > 3 || out.size() >= 200) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".") || n.equals("Android")) continue;
            if (f.isDirectory()) scan(f, depth + 1, out);
            else {
                String l = n.toLowerCase(Locale.ROOT);
                if ((l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp"))
                        && f.length() > 30 * 1024) out.add(f);
            }
        }
    }

    // ------------------------------------------------------------------ tải app

    private void loadApps() {
        if (loading) { needReload = true; return; }
        loading = true;
        new Thread(() -> {
            final List<Item> shown = new ArrayList<>();
            final List<Item> hidden = new ArrayList<>();
            build(shown, hidden);
            ui.post(() -> {
                loading = false;
                items.clear();
                items.addAll(shown);
                hiddenApps.clear();
                hiddenApps.addAll(hidden);
                renderRow();
                if (needReload) { needReload = false; loadApps(); }
            });
        }, "load-apps").start();
    }

    private void build(List<Item> shown, List<Item> hidden) {
        PackageManager pm = getPackageManager();
        String me = getPackageName();
        Map<String, Item> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, me, byPkg);
        collect(pm, Intent.CATEGORY_LAUNCHER, me, byPkg);

        Set<String> hiddenSet = prefs.getStringSet(KEY_HIDDEN, new HashSet<>());
        List<Item> apps = new ArrayList<>();
        for (Item it : byPkg.values()) {
            if (hiddenSet.contains(it.pkg)) hidden.add(it); else apps.add(it);
        }

        final Map<String, Integer> rank = new HashMap<>();
        String[] order = prefs.getString(KEY_ORDER, "").split(",");
        for (int i = 0; i < order.length; i++) if (!order[i].isEmpty()) rank.put(order[i], i);
        final Collator col = Collator.getInstance(new Locale("vi"));
        Collections.sort(apps, (a, b) -> {
            Integer ra = rank.get(a.pkg), rb = rank.get(b.pkg);
            if (ra != null && rb != null) return ra - rb;
            if (ra != null) return -1;
            if (rb != null) return 1;
            return col.compare(a.label, b.label);
        });
        Collections.sort(hidden, (a, b) -> col.compare(a.label, b.label));
        if (!isDefaultHome() && !homeFixOn()) {
            shown.add(fixed(TYPE_HOMEFIX, "Bật nút Home", R.drawable.ic_settings, 0xFFC62828));
        }
        shown.addAll(apps);

        shown.add(fixed(TYPE_SETTINGS, "Cài đặt", R.drawable.ic_settings, 0xFF37474F));
        shown.add(fixed(TYPE_WALL, "Hình nền", R.drawable.ic_wallpaper, 0xFF00796B));
        shown.add(fixed(TYPE_CLEAN, "Dọn dẹp tivi", R.drawable.ic_clean, 0xFF5E35B1));
        if (!hidden.isEmpty()) {
            shown.add(fixed(TYPE_HIDDEN, "App đã ẩn (" + hidden.size() + ")", R.drawable.ic_hidden, 0xFF455A64));
        }
    }

    private Item fixed(int type, String label, int icon, int color) {
        Item it = new Item();
        it.type = type;
        it.label = label;
        it.icon = getDrawable(icon);
        it.color = color;
        return it;
    }

    private void collect(PackageManager pm, String category, String me, Map<String, Item> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list;
        try { list = pm.queryIntentActivities(q, 0); } catch (Exception e) { return; }
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || me.equals(ai.packageName) || out.containsKey(ai.packageName)) continue;
            Item it = new Item();
            it.pkg = ai.packageName;
            it.comp = new ComponentName(ai.packageName, ai.name);
            CharSequence l = ri.loadLabel(pm);
            it.label = l != null ? l.toString() : ai.packageName;
            try {
                it.banner = ai.loadBanner(pm);
                if (it.banner == null) it.banner = ai.applicationInfo.loadBanner(pm);
            } catch (Exception ignored) { }
            if (it.banner == null) {
                try { it.icon = ri.loadIcon(pm); } catch (Exception ignored) { }
            }
            out.put(it.pkg, it);
        }
    }

    // ------------------------------------------------------------------ hàng app

    private void renderRow() {
        row.removeAllViews();
        tiles.clear();
        for (int i = 0; i < items.size(); i++) {
            final int pos = i;
            final Item it = items.get(i);
            View v = inf.inflate(R.layout.item_app, row, false);
            ImageView banner = v.findViewById(R.id.banner);
            ImageView icon = v.findViewById(R.id.icon);
            TextView label = v.findViewById(R.id.label);
            final View frame = v.findViewById(R.id.ring);
            label.setText(it.label);
            if (it.type == TYPE_HOMEFIX) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
                bg.setColor(0xFFC62828); // đỏ để dễ thấy
                v.findViewById(R.id.frame).setBackground(bg);
            }
            v.findViewById(R.id.frame).setClipToOutline(true);
            if (it.banner != null) {
                banner.setImageDrawable(it.banner);
                banner.setVisibility(View.VISIBLE);
                icon.setVisibility(View.GONE);
            } else {
                icon.setImageDrawable(it.icon);
            }
            final float elev = 10 * getResources().getDisplayMetrics().density;
            v.setOnClickListener(x -> open(it));
            v.setOnLongClickListener(x -> { showOptions(pos); return true; });
            v.setOnFocusChangeListener((x, has) -> {
                frame.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                        .translationZ(has ? elev : 0).setDuration(140).start();
                label.animate().translationY(has ? 6 * getResources().getDisplayMetrics().density : 0)
                        .setDuration(140).start();
                if (has) focusIndex = pos;
            });
            row.addView(v);
            tiles.add(v);
        }
        if (focusIndex >= tiles.size()) focusIndex = 0;
        focusTile(focusIndex);
    }

    private void focusTile(int i) {
        if (tiles.isEmpty()) return;
        if (i < 0 || i >= tiles.size()) i = 0;
        final View v = tiles.get(i);
        v.requestFocus();
        if (i == 0) scroller.smoothScrollTo(0, 0);
    }

    // ------------------------------------------------------------------ mở app

    private void open(Item it) {
        try {
            switch (it.type) {
                case TYPE_SETTINGS:
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                case TYPE_CLEAN:
                    startActivity(new Intent(this, CleanActivity.class));
                    return;
                case TYPE_WALL:
                    chooseBackground();
                    return;
                case TYPE_HIDDEN:
                    showHidden();
                    return;
                case TYPE_HOMEFIX:
                    askedHome = false;
                    prefs.edit().putBoolean("no_ask_home", false).apply();
                    askHomeService();
                    return;
            }
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(it.comp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception e) {
            Toast.makeText(this, "Không mở được " + it.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ tuỳ chọn

    private void showOptions(final int pos) {
        if (pos < 0 || pos >= items.size()) return;
        final Item it = items.get(pos);
        if (it.type != TYPE_APP) { open(it); return; }
        String[] opts = {"Đưa lên đầu", "Dời sang trái", "Dời sang phải", "Ẩn app này", "Thông tin app"};
        new AlertDialog.Builder(this)
                .setTitle(it.label)
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0: move(pos, firstApp()); break;
                        case 1: move(pos, pos - 1); break;
                        case 2: move(pos, pos + 1); break;
                        case 3: hide(it); break;
                        case 4: appInfo(it); break;
                    }
                }).show();
    }

    private int appCount() {
        int n = 0;
        for (Item i : items) if (i.type == TYPE_APP) n++;
        return n;
    }

    private int firstApp() {
        int f = 0;
        while (f < items.size() && items.get(f).type != TYPE_APP) f++;
        return f;
    }

    private void move(int from, int to) {
        int f = firstApp();
        if (to < f || to >= f + appCount() || from == to) return;
        Item it = items.remove(from);
        items.add(to, it);
        StringBuilder sb = new StringBuilder();
        for (Item i : items) if (i.type == TYPE_APP) sb.append(i.pkg).append(',');
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
        focusIndex = to;
        renderRow();
    }

    private void hide(Item it) {
        Set<String> s = new HashSet<>(prefs.getStringSet(KEY_HIDDEN, new HashSet<>()));
        s.add(it.pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, s).apply();
        Toast.makeText(this, "Đã ẩn " + it.label, Toast.LENGTH_SHORT).show();
        loadApps();
    }

    private void showHidden() {
        if (hiddenApps.isEmpty()) return;
        final String[] names = new String[hiddenApps.size()];
        for (int i = 0; i < names.length; i++) names[i] = hiddenApps.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Chọn app để hiện lại")
                .setItems(names, (d, which) -> {
                    Set<String> s = new HashSet<>(prefs.getStringSet(KEY_HIDDEN, new HashSet<>()));
                    s.remove(hiddenApps.get(which).pkg);
                    prefs.edit().putStringSet(KEY_HIDDEN, s).apply();
                    loadApps();
                }).show();
    }

    private void appInfo(Item it) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + it.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Tivi không hỗ trợ mục này", Toast.LENGTH_SHORT).show();
        }
    }
}
