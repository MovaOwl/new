package ru.movaowl.slovopad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** После перезагрузки телефона лис возвращается на экран, если жил там. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        QuizReceiver.schedule(c);
        if (!c.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE).getBoolean("on", false)) return;
        if (!Settings.canDrawOverlays(c)) return;
        try { c.startForegroundService(new Intent(c, FoxService.class)); } catch (Exception ignored) { }
    }
}
