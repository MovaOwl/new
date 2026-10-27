package ru.movaowl.slovopad;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Мозг лиса в отдельном процессе (:brain): если нейросеть упадёт, лис на экране останется жив. */
public class BrainService extends Service {
    static final int MSG_CHAT = 1, MSG_UNLOAD = 2;
    private Messenger messenger;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this, "мозг");
        HandlerThread ht = new HandlerThread("brain-ipc");
        ht.start();
        messenger = new Messenger(new Handler(ht.getLooper()) {
            @Override
            public void handleMessage(Message m) {
                if (m.what == MSG_UNLOAD) { LocalBrain.unload(); return; }
                if (m.what != MSG_CHAT) return;
                Bundle b = m.getData();
                Messenger reply = m.replyTo;
                int id = m.arg1;
                // большой стек для нативного кода
                new Thread(null, () -> {
                    String out = null, err = "";
                    try {
                        JSONArray a = new JSONArray(b.getString("msgs", "[]"));
                        List<String[]> msgs = new ArrayList<>();
                        for (int i = 0; i < a.length(); i++) msgs.add(new String[]{a.getJSONArray(i).getString(0), a.getJSONArray(i).getString(1)});
                        String path = b.getString("path", "");
                        if (!LocalBrain.lib()) err = "Движок нейросети не загрузился на этом телефоне";
                        else if (!new File(path).exists()) err = "Файл мозга не найден";
                        else {
                            CrashLog.step(BrainService.this, "загружаю/думаю: " + new File(path).getName() + " (" + new File(path).length() / 1000000 + " МБ), свободно ОЗУ ~" + (Runtime.getRuntime().maxMemory() / 1000000) + " МБ кучи");
                            out = LocalBrain.chatFile(path, msgs, b.getInt("max", 120));
                            if (out != null) CrashLog.step(BrainService.this, "ответил, " + out.length() + " симв.");
                            if (out == null) err = "Мозг не смог загрузиться (мало памяти?)";
                        }
                    } catch (Throwable t) { err = "Ошибка мозга: " + t.getClass().getSimpleName(); }
                    Message r = Message.obtain(null, MSG_CHAT, id, 0);
                    Bundle rb = new Bundle(); rb.putString("out", out); rb.putString("err", err); r.setData(rb);
                    try { if (reply != null) reply.send(r); } catch (RemoteException ignored) { }
                }, "brain", 32L << 20).start();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) { return messenger.getBinder(); }
}
