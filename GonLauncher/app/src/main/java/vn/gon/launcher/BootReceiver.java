package vn.gon.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bật tivi xong thì mở Gọn Launcher (Android 9 cho phép). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        HomeWatch.start(c);
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }
}
