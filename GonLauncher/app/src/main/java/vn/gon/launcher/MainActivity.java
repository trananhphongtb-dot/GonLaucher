package vn.gon.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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
 * Không thư viện ngoài, không chạy ngầm, không quảng cáo.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "gon";
    private static final String KEY_ORDER = "order";   // danh sách package theo thứ tự, cách nhau bởi dấu ,
    private static final String KEY_HIDDEN = "hidden"; // các package bị ẩn

    private static final int TYPE_APP = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_HIDDEN = 2;
    private static final int TYPE_CLEAN = 3;

    static class Item {
        int type = TYPE_APP;
        String label;
        String pkg;
        ComponentName comp;
        Drawable banner;
        Drawable icon;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final List<Item> hiddenApps = new ArrayList<>();
    private GridView grid;
    private Adapter adapter;
    private SharedPreferences prefs;
    private volatile boolean loading = false;
    private boolean needReload = false;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Cài / gỡ / cập nhật app -> tải lại danh sách
            if (hasWindowFocus()) loadApps(); else needReload = true;
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        grid = findViewById(R.id.grid);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> open(items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showOptions(pos);
            return true;
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needReload) {
            needReload = false;
            loadApps();
        }
        grid.requestFocus();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** Bấm Home khi đang ở màn hình chính -> về ô đầu tiên. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!items.isEmpty()) grid.setSelection(0);
        grid.requestFocus();
    }

    /** Launcher không được thoát khi bấm Back. */
    @Override
    public void onBackPressed() {
        if (grid.getSelectedItemPosition() > 0) grid.setSelection(0);
    }

    /** Nút Menu trên remote cũng mở tuỳ chọn cho ô đang chọn. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = grid.getSelectedItemPosition();
            if (pos >= 0) showOptions(pos);
            return true;
        }
        return super.onKeyDown(keyCode, e);
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
                int keep = grid.getSelectedItemPosition();
                items.clear();
                items.addAll(shown);
                hiddenApps.clear();
                hiddenApps.addAll(hidden);
                adapter.notifyDataSetChanged();
                if (keep >= 0 && keep < items.size()) grid.setSelection(keep);
                if (needReload) { needReload = false; loadApps(); }
            });
        }, "load-apps").start();
    }

    private void build(List<Item> shown, List<Item> hidden) {
        PackageManager pm = getPackageManager();
        String me = getPackageName();
        Map<String, Item> byPkg = new LinkedHashMap<>();

        // 1) App dành cho TV (ưu tiên vì có banner ngang)
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, me, byPkg);
        // 2) App điện thoại còn lại
        collect(pm, Intent.CATEGORY_LAUNCHER, me, byPkg);

        Set<String> hiddenSet = prefs.getStringSet(KEY_HIDDEN, new HashSet<>());
        List<Item> apps = new ArrayList<>();
        for (Item it : byPkg.values()) {
            if (hiddenSet.contains(it.pkg)) hidden.add(it); else apps.add(it);
        }

        // Sắp xếp: theo thứ tự người dùng đã chỉnh, app mới thêm xếp theo ABC ở cuối
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
        shown.addAll(apps);

        // Ô cố định ở cuối
        Item s = new Item();
        s.type = TYPE_SETTINGS;
        s.label = "Cài đặt";
        s.icon = getDrawable(android.R.drawable.ic_menu_preferences);
        shown.add(s);

        Item c = new Item();
        c.type = TYPE_CLEAN;
        c.label = "Dọn dẹp tivi";
        c.icon = getDrawable(android.R.drawable.ic_menu_delete);
        shown.add(c);

        if (!hidden.isEmpty()) {
            Item h = new Item();
            h.type = TYPE_HIDDEN;
            h.label = "App đã ẩn (" + hidden.size() + ")";
            h.icon = getDrawable(android.R.drawable.ic_menu_view);
            shown.add(h);
        }
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

    // ------------------------------------------------------------------ mở app

    private void open(Item it) {
        try {
            if (it.type == TYPE_SETTINGS) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (it.type == TYPE_CLEAN) {
                startActivity(new Intent(this, CleanActivity.class));
                return;
            }
            if (it.type == TYPE_HIDDEN) {
                showHidden();
                return;
            }
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .setComponent(it.comp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
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
                        case 0: move(pos, 0); break;
                        case 1: move(pos, pos - 1); break;
                        case 2: move(pos, pos + 1); break;
                        case 3: hide(it); break;
                        case 4: appInfo(it); break;
                    }
                })
                .show();
    }

    private int appCount() {
        int n = 0;
        for (Item i : items) if (i.type == TYPE_APP) n++;
        return n;
    }

    private void move(int from, int to) {
        int n = appCount();
        if (to < 0 || to >= n || from == to) return;
        Item it = items.remove(from);
        items.add(to, it);
        saveOrder();
        adapter.notifyDataSetChanged();
        grid.setSelection(to);
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) if (i.type == TYPE_APP) sb.append(i.pkg).append(',');
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
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
                })
                .show();
    }

    private void appInfo(Item it) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + it.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Tivi không hỗ trợ mục này", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ adapter

    static class Holder {
        ImageView banner, icon;
        TextView label;
    }

    class Adapter extends BaseAdapter {
        private final LayoutInflater inf = LayoutInflater.from(MainActivity.this);

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                v = inf.inflate(R.layout.item_app, parent, false);
                h = new Holder();
                h.banner = v.findViewById(R.id.banner);
                h.icon = v.findViewById(R.id.icon);
                h.label = v.findViewById(R.id.label);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            Item it = items.get(p);
            h.label.setText(it.label);
            if (it.banner != null) {
                h.banner.setImageDrawable(it.banner);
                h.banner.setVisibility(View.VISIBLE);
                h.icon.setVisibility(View.GONE);
            } else {
                h.icon.setImageDrawable(it.icon);
                h.icon.setVisibility(View.VISIBLE);
                h.banner.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
