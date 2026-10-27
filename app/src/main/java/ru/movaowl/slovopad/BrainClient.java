package ru.movaowl.slovopad;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

import org.json.JSONArray;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Связь с мозгом в отдельном процессе. Вызывать из фонового потока. */
final class BrainClient {
    private static Messenger remote;
    private static ServiceConnection conn;
    private static Messenger replies;
    private static final Object LOCK = new Object();
    private static volatile String lastErr = "";

    static String lastError() { return lastErr; }

    private static boolean connect(Context c) throws InterruptedException {
        synchronized (LOCK) {
            if (remote != null && remote.getBinder().isBinderAlive()) return true;
            CountDownLatch l = new CountDownLatch(1);
            conn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName n, IBinder b) { remote = new Messenger(b); l.countDown(); }
                @Override public void onServiceDisconnected(ComponentName n) { remote = null; }
            };
            if (!c.getApplicationContext().bindService(new Intent(c, BrainService.class), conn, Context.BIND_AUTO_CREATE)) return false;
            return l.await(8, TimeUnit.SECONDS) && remote != null;
        }
    }

    /** null — не вышло, причина в lastError(). */
    static String chat(Context c, List<String[]> msgs, int max) {
        lastErr = "";
        java.io.File f = LocalBrain.model(c);
        if (f == null) { lastErr = "Мозг не скачан"; return null; }
        try {
            if (!connect(c)) { lastErr = "Мозг не запустился"; return null; }
            if (replies == null) {
                HandlerThread ht = new HandlerThread("brain-reply"); ht.start();
                replies = new Messenger(new Handler(ht.getLooper()) {
                    @Override public void handleMessage(Message m) { Waiter w = Waiter.cur; if (w != null && w.id == m.arg1) { w.out = m.getData().getString("out"); w.err = m.getData().getString("err", ""); w.l.countDown(); } }
                });
            }
            JSONArray a = new JSONArray();
            for (String[] x : msgs) a.put(new JSONArray().put(x[0]).put(x[1]));
            Waiter w = new Waiter();
            Waiter.cur = w;
            Message m = Message.obtain(null, BrainService.MSG_CHAT, w.id, 0);
            Bundle b = new Bundle(); b.putString("msgs", a.toString()); b.putString("path", f.getAbsolutePath()); b.putInt("max", max);
            m.setData(b); m.replyTo = replies;
            final IBinder.DeathRecipient dr = () -> { w.err = "Мозг упал — попробуй «Лёгкий» в Опциях"; w.l.countDown(); };
            remote.getBinder().linkToDeath(dr, 0);
            remote.send(m);
            boolean ok = w.l.await(120, TimeUnit.SECONDS);
            try { remote.getBinder().unlinkToDeath(dr, 0); } catch (Exception ignored) { }
            if (!ok) { lastErr = "Мозг думал слишком долго"; return null; }
            if (w.out == null || w.out.trim().isEmpty()) { lastErr = w.err == null || w.err.isEmpty() ? "Мозг промолчал" : w.err; remote = null; return null; }
            return w.out.trim();
        } catch (Exception e) {
            remote = null;
            lastErr = "Мозг упал — попробуй «Лёгкий» в Опциях";
            return null;
        }
    }

    static final class Waiter {
        static volatile Waiter cur;
        private static int seq = 0;
        final int id = ++seq;
        final CountDownLatch l = new CountDownLatch(1);
        volatile String out, err;
    }
}
