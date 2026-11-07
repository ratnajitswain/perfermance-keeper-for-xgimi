package local.perfkeeper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Installs + starts keeper.sh through the projector's own debug service.
 * Used at boot, when the app opens, and when settings change ("restart").
 * Runs independently of the screen, so leaving the app doesn't interrupt it.
 */
public class BootService extends Service {
    private static final String TAG = "PerfKeeper";
    private static final Object LOCK = new Object();

    static void ensure(Context c) { c.startService(new Intent(c, BootService.class)); }
    static void restart(Context c) { c.startService(new Intent(c, BootService.class).putExtra("restart", true)); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final boolean restart = intent != null && intent.getBooleanExtra("restart", false);
        new Thread(() -> {
            synchronized (LOCK) {
                Keeper k = new Keeper(this);
                for (int attempt = 1; attempt <= 40; attempt++) {      // up to ~4 minutes after boot
                    try {
                        k.connect(null);
                        if (restart || !k.running()) k.start();
                        Log.i(TAG, (restart ? "keeper restarted" : "keeper ensured") + " (attempt " + attempt + ")");
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "attempt " + attempt + ": " + e.getMessage());
                        k.close();
                        try { Thread.sleep(6000); } catch (InterruptedException ignored) { }
                    }
                }
                k.close();
            }
            stopSelf(startId);
        }, "keeper-start").start();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
