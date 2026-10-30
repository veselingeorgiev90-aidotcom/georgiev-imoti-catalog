package bg.imotipremier.pozdravi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Scheduler.fireIfDue(c);
        Scheduler.scheduleNext(c);
    }
}
