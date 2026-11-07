package local.perfkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the keeper when the projector finishes booting. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Keeper.prefs(c).getBoolean("autostart", true)) return;
        BootService.ensure(c);
    }
}
