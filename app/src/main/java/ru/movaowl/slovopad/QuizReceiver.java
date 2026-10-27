package ru.movaowl.slovopad;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/** Экзамен в шторке: лис спрашивает слово в уведомлении, отвечаешь прямо там. */
public class QuizReceiver extends BroadcastReceiver {
    static final String PREF = "quiz";
    static final String ASK = "ru.movaowl.slovopad.QUIZ_ASK";
    static final String ANSWER = "ru.movaowl.slovopad.QUIZ_ANSWER";
    static final String GIVEUP = "ru.movaowl.slovopad.QUIZ_GIVEUP";
    static final String KEY_TEXT = "answer";
    static final String CH = "quiz";
    static final int NID = 21;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (ASK.equals(a)) { ask(c, i.getBooleanExtra("now", false)); if (!i.getBooleanExtra("now", false)) schedule(c); }
        else if (ANSWER.equals(a)) answer(c, i);
        else if (GIVEUP.equals(a)) giveUp(c);
        else if (Intent.ACTION_BOOT_COMPLETED.equals(a)) schedule(c);
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    /* ---------- расписание ---------- */
    static void schedule(Context c) {
        SharedPreferences p = prefs(c);
        int every = p.getInt("every", 0);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 31, new Intent(c, QuizReceiver.class).setAction(ASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (am == null) return;
        am.cancel(pi);
        if (every <= 0) return;
        int from = p.getInt("from", 9), to = p.getInt("to", 22);
        Calendar t = Calendar.getInstance();
        t.add(Calendar.MINUTE, every);
        int h = t.get(Calendar.HOUR_OF_DAY);
        if (h < from) { t.set(Calendar.HOUR_OF_DAY, from); t.set(Calendar.MINUTE, 5); }
        else if (h >= to) { t.add(Calendar.DAY_OF_MONTH, 1); t.set(Calendar.HOUR_OF_DAY, from); t.set(Calendar.MINUTE, 5); }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
    }

    private static void channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Экзамен в шторке", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setDescription("Лис спрашивает слова — отвечай прямо в уведомлении");
            nm.createNotificationChannel(ch);
        }
    }

    /* ---------- вопрос ---------- */
    static void ask(Context c, boolean now) {
        SharedPreferences p = prefs(c);
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (!now && (h < p.getInt("from", 9) || h >= p.getInt("to", 22))) return;
        JSONArray pool;
        try { pool = new JSONArray(p.getString("pool", "[]")); } catch (Exception e) { return; }
        if (pool.length() == 0) return;
        String last = p.getString("en", "");
        Random r = new Random();
        JSONArray w = pool.optJSONArray(r.nextInt(pool.length()));
        for (int k = 0; k < 5 && w != null && w.optString(0).equals(last) && pool.length() > 1; k++) w = pool.optJSONArray(r.nextInt(pool.length()));
        if (w == null) return;
        String en = w.optString(0), ru = w.optString(1);
        p.edit().putString("en", en).putString("ru", ru).apply();
        channel(c);

        RemoteInput ri = new RemoteInput.Builder(KEY_TEXT).setLabel("По-английски…").build();
        PendingIntent piAns = PendingIntent.getBroadcast(c, 32, new Intent(c, QuizReceiver.class).setAction(ANSWER), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent piGive = PendingIntent.getBroadcast(c, 33, new Intent(c, QuizReceiver.class).setAction(GIVEUP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action reply = new Notification.Action.Builder((Icon) null, "Ответить", piAns).addRemoteInput(ri).build();
        Notification.Action give = new Notification.Action.Builder((Icon) null, "Не помню", piGive).build();
        String first = ru.split("[,;]")[0].trim();
        post(c, new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_fox)
                .setContentTitle("🦊 Как по-английски «" + first + "»?")
                .setContentText("Напиши ответ прямо здесь — «Ответить»")
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .addAction(reply).addAction(give)
                .build());
    }

    /* ---------- ответ ---------- */
    private static void answer(Context c, Intent i) {
        Bundle b = RemoteInput.getResultsFromIntent(i);
        String said = b != null && b.getCharSequence(KEY_TEXT) != null ? b.getCharSequence(KEY_TEXT).toString() : "";
        SharedPreferences p = prefs(c);
        String en = p.getString("en", ""), ru = p.getString("ru", "");
        if (en.isEmpty()) return;
        String a = norm(said), want = norm(en);
        String title, text;
        boolean ok;
        if (said.matches("(?s).*[А-Яа-яЁё].*")) {
            ok = false; title = "Нужно по-английски 🙂"; text = "«" + ru + "» — " + en;
        } else {
            int d = lev(a, want);
            ok = a.equals(want) || (want.length() >= 5 && d <= 1) || (want.length() >= 9 && d <= 2);
            if (a.equals(want)) { title = "✅ Верно! " + en + " +1 🪙"; text = "«" + ru + "». Лис доволен — ещё вопрос будет позже."; }
            else if (ok) { title = "✅ Почти! Пишется: " + en + " +1 🪙"; text = "Ты написал «" + said.trim() + "». Засчитано."; }
            else { title = "❌ Мимо: " + en; text = "Ты написал «" + said.trim() + "». «" + ru + "» — " + en + ". Слово вернётся в повтор."; }
        }
        log(c, en, ok);
        result(c, title, text);
    }

    private static void giveUp(Context c) {
        SharedPreferences p = prefs(c);
        String en = p.getString("en", ""), ru = p.getString("ru", "");
        if (en.isEmpty()) return;
        log(c, en, false);
        result(c, "Ответ: " + en, "«" + ru + "» — " + en + ". Ничего, повторим 🦊");
    }

    private static void result(Context c, String title, String text) {
        PendingIntent piMore = PendingIntent.getBroadcast(c, 34, new Intent(c, QuizReceiver.class).setAction(ASK).putExtra("now", true), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        post(c, new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_fox)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60000L)
                .addAction(new Notification.Action.Builder((Icon) null, "Ещё вопрос", piMore).build())
                .build());
    }

    private static void post(Context c, Notification n) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(NID, n); } catch (Exception ignored) { }
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 35, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /* результаты забирает игра при открытии */
    private static void log(Context c, String en, boolean ok) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray l = new JSONArray(p.getString("log", "[]"));
            l.put(new JSONObject().put("en", en).put("ok", ok).put("ts", System.currentTimeMillis()));
            p.edit().putString("log", l.toString()).apply();
        } catch (Exception ignored) { }
        if (ok) c.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE).edit().putLong("fed", System.currentTimeMillis()).apply();
    }

    static String norm(String s) {
        s = s.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^a-z' -]", " ").replaceAll("\\s+", " ").trim();
        return s.replaceFirst("^(to|a|an|the) ", "");
    }

    static int lev(String a, String b) {
        int[] d = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) d[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = d[0]; d[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int t = d[j];
                d[j] = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = t;
            }
        }
        return d[b.length()];
    }
}
