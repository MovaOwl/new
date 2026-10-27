package ru.movaowl.slovopad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Лис, который гуляет по экрану поверх всех приложений. */
public class FoxService extends Service {
    static final String PREF = "fox";
    static final String ACT_STOP = "ru.movaowl.slovopad.FOX_STOP";
    static final String CH = "fox";
    static final String ACT_HEARD = "ru.movaowl.slovopad.FOX_HEARD";

    private WindowManager wm;
    private LinearLayout root;
    private ImageView img;
    private TextView bub;
    private WindowManager.LayoutParams lp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Map<String, Bitmap[]> cache = new HashMap<>();

    private JSONObject data;
    private JSONObject meta;
    private List<String> idles = new ArrayList<>();
    private List<String[]> lines = new ArrayList<>();
    private int stage = 0;
    private long everyMs = 180000;

    private String anim = null;
    private Bitmap[] frames;
    private int frame = 0, loopsLeft = 0;
    private float fps = 6;
    private int targetX = -1;
    private float speed = 0;
    private long nextPhraseAt = 0;
    private String curEn = null, curRu = null;
    private boolean showRu = false;
    private int foxH, screenW, screenH;
    private float dp;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ticking = false;
    private FoxBrain brain;
    static volatile FoxService I;
    private FoxHands hands;
    /* сценарии: путешествие к точке, спрятаться, сесть на значок */
    private int travX = -1, travY = -1, baseY = 0;
    private Runnable onArrive;
    private boolean busy = false;
    private String appKind = "";      // home / read / video / chat / camera / ""
    private long homeActAt = 0;
    private boolean thinking = false;
    /* словопад от лиса: слова, которые ты знаешь */
    private final List<String[]> words = new ArrayList<>();
    private long dropEveryMs = 20 * 60000L, nextDropAt = 0;
    private boolean inDrop = false;
    private int homeX, homeY;
    private TextView wordV;
    private WindowManager.LayoutParams wlp;
    /* голод: 0 сыт, 1 проголодался, 2 грустит, 3 обиделся, 4 ушёл */
    private int mood = -1;
    private long moodCheckAt = 0, peekUntil = 0, nextPeekAt = 0;
    private static final String[][] CALL_HUNGRY = {
        {"I'm hungry! Feed me some words!", "Я голодный! Покорми меня словами!"},
        {"Let's play a little?", "Поиграем немножко?"},
        {"Tap me — let's learn some words!", "Нажми на меня — поучим слова!"},
        {"My tummy is growling…", "У меня урчит в животе…"},
        {"Ten words, and I'm full!", "Десять слов — и я сытый!"}};
    private static final String[][] CALL_SAD = {
        {"I miss you…", "Я скучаю…"},
        {"Where are you? Come play with me!", "Где ты? Иди поиграй со мной!"},
        {"Just five minutes? Please?", "Всего пять минут? Пожалуйста?"},
        {"I'm so bored without you.", "Мне так скучно без тебя."}};
    private static final String[][] CALL_GRUMPY = {
        {"Hmph! You forgot about me.", "Хмф! Ты про меня забыл."},
        {"Fine. I'll just sit here. Alone.", "Ладно. Посижу тут. Один."},
        {"Are we still friends?", "Мы ещё друзья?"}};
    private static final String[] GONE = {"I'm going away… Play with me to bring me back!", "Я ухожу… Поиграй со мной, чтобы я вернулся!"};

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() { super.onCreate(); CrashLog.install(this, "лис на экране"); I = this; }

    FoxBrain brain() { if (brain == null) brain = new FoxBrain(this); return brain; }
    void say(String t, long ms) { if (root != null) { curEn = null; showBub(t, ms); } }
    void anim(String n, int loops) { if (root != null) play(n, loops); }

    /* ---------- глаза: какое приложение открыто ---------- */
    void onEyes(boolean on) { h.post(() -> { if (root != null && on && stage > 0) showBub("👀 Теперь я вижу твой экран! I can see now.", 5000); }); }

    void onApp(String pkg, boolean home) {
        h.post(() -> {
            if (root == null) return;
            String p = pkg.toLowerCase(java.util.Locale.ROOT);
            String k = home ? "home"
                    : p.matches(".*(camera|scanner).*") ? "camera"
                    : p.matches(".*(litres|readera|moonreader|fbreader|kindle|books|librera|pocketbook|aldiko|bookmate|reader|ebook).*") ? "read"
                    : p.matches(".*(youtube|rutube|vkvideo|netflix|kinopoisk|ivi|okko|tiktok|video|player|wink|kion|premier).*") ? "video"
                    : p.matches(".*(telegram|whatsapp|viber|vkontakte|\\.vk\\.|messag|mms|max\\.|tamtam|discord).*") ? "chat" : "";
            if (k.equals(appKind)) return;
            appKind = k;
            if (busy) return;
            if ("camera".equals(k)) { root.setVisibility(View.GONE); return; }
            if (mood != 4) root.setVisibility(View.VISIBLE);
            if (stage <= 0 || dayPhase() == 2) return;
            if ("read".equals(k)) { playFor("read", 120, 300); if (rnd.nextInt(3) == 0) showBub("📖 Let's read together! — Почитаем вместе", 5000); }
            else if ("video".equals(k)) { goTo((int) (8 * dp), baseY, false, () -> playFor("sit", 60, 180)); }
            else if ("chat".equals(k)) { goTo(screenW - foxH - (int) (8 * dp), baseY, false, () -> playFor("sleep", 60, 180)); }
            else if ("home".equals(k)) { homeActAt = System.currentTimeMillis() + 4000; }
        });
    }

    /* ---------- руки: движение к точке ---------- */
    void goTo(int x, int y, boolean run, Runnable then) {
        goToPx(x, y, (run ? 330f : 110f) * dp, then);
        play(run && has("run") ? "run" : "walk", 999);
        img.setScaleX(travX < lp.x ? -1 : 1);
    }

    /* плавное движение 60 кадров/с, отдельно от смены картинок */
    private float fx, fy, speedPx;
    private long moveLast;

    void goToPx(int x, int y, float pxPerSec, Runnable then) {
        targetX = -1;
        travX = Math.max(0, Math.min(x, screenW - foxH)); travY = Math.max((int) (24 * dp), y); onArrive = then;
        speedPx = Math.max(40f, pxPerSec);
        fx = lp.x; fy = lp.y;
        moveLast = android.os.SystemClock.uptimeMillis();
        h.removeCallbacks(mover);
        h.post(mover);
    }

    private final Runnable mover = new Runnable() {
        @Override public void run() {
            if (root == null || travX < 0) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min(60, now - moveLast) / 1000f; moveLast = now;
            int ty = travY - (bub.getVisibility() == View.VISIBLE ? img.getTop() : 0);   // цель — для самой лисы, а не облачка
            float dx = travX - fx, dy = ty - fy;
            double dist = Math.sqrt(dx * dx + dy * dy);
            float step = speedPx * dt;
            if (dist <= step || dist < 1) {
                lp.x = travX; lp.y = ty; travX = -1; clampAndUpdate();
                Runnable r = onArrive; onArrive = null;
                if (r != null) r.run(); else pickNext();
                return;
            }
            fx += dx / dist * step; fy += dy / dist * step;
            lp.x = Math.round(fx); lp.y = Math.round(fy);
            clampAndUpdate();
            h.postDelayed(this, 16);
        }
    };

    /** Пока лис работает руками, касания проходят сквозь него — чтобы не мешал своим же свайпам и тапам. */
    void touchThrough(boolean on) {
        if (lp == null || root == null) return;
        int f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        int nf = on ? lp.flags | f : lp.flags & ~f;
        if (nf == lp.flags) return;
        lp.flags = nf;
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    void startJob() { busy = true; touchThrough(true); img.setClipBounds(null); }

    /** Лис хватает рабочий стол лапой и тащит его вместе со свайпом. next — к следующей странице (влево). */
    void pushPage(boolean next, boolean firstTime, Runnable done) {
        FoxEyes e = FoxEyes.I;
        if (e == null || root == null) { if (done != null) done.run(); return; }
        startJob(); bub.setVisibility(View.GONE);
        float y = screenH * 0.42f;
        float x0 = next ? screenW * 0.86f : screenW * 0.14f, x1 = next ? screenW * 0.14f : screenW * 0.86f;
        int fy0 = (int) (y - foxH * 0.55f);
        int sx = next ? (int) (x0 - foxH * 0.35f) : (int) (x0 - foxH * 0.9f);
        int ex = next ? (int) (x1 - foxH * 0.35f) : (int) (x1 - foxH * 0.9f);
        goTo(sx, fy0, true, () -> {
            img.setScaleX(next ? -1 : 1);
            play(has("wave") ? "wave" : "jump", 1);                    // замах лапой
            h.postDelayed(() -> {
                long dur = 560;
                play(has("run") ? "run" : "walk", 999);
                goToPx(ex, fy0, Math.abs(ex - lp.x) * 1000f / dur + 1, () -> play("sit", 999));
                img.setScaleX(next ? -1 : 1);
                e.swipe(x0, x1, y, dur, () -> h.postDelayed(() -> { if (done != null) done.run(); }, 650));
            }, firstTime ? 650 : 280);
        });
    }

    /** Подбежать к значку и стукнуть по нему лапой. */
    void tapIcon(android.graphics.Rect r, Runnable click) {
        startJob(); bub.setVisibility(View.GONE);
        goTo(r.centerX() - foxH / 2, r.top - foxH + (int) (16 * dp), true, () -> {
            play(has("wave") ? "wave" : "jump", 1);
            long d = frames != null ? (long) (frames.length * 1000 / Math.max(1f, fps) * 0.55f) : 500;
            h.postDelayed(() -> {
                play(has("cheer") ? "cheer" : "jump", 2);
                if (click != null) click.run();
            }, Math.max(350, Math.min(900, d)));
        });
    }

    private int imgW() { return img.getLayoutParams() != null && img.getLayoutParams().width > 0 ? img.getLayoutParams().width : foxH; }

    void runToIcon(android.graphics.Rect r, Runnable then) {
        startJob(); bub.setVisibility(View.GONE);
        goTo(r.centerX() - foxH / 2, r.top - foxH + (int) (6 * dp), true, then);
    }

    void cmdDone() { h.postDelayed(() -> { busy = false; touchThrough(false); img.setClipBounds(null); goTo(lp.x, baseY, false, () -> playFor("sit", 20, 40)); }, 1500); }

    /** Спрятаться за значком: видна только голова над иконкой. */
    void peekBehind(android.graphics.Rect r) {
        busy = true; bub.setVisibility(View.GONE); h.removeCallbacks(hideBub);
        goTo(r.centerX() - foxH / 2, r.top - foxH + (int) (foxH * 0.45f), false, () -> {
            int visible = Math.max(1, r.top - lp.y);
            img.setClipBounds(new android.graphics.Rect(0, 0, imgW(), Math.min(foxH, visible)));
            play(has("surprise") ? "surprise" : "sit", 999);
            h.postDelayed(() -> { if (busy) { busy = false; img.setClipBounds(null); goTo(lp.x, baseY, false, () -> playFor("sit", 20, 40)); } }, 12000 + rnd.nextInt(10000));
        });
    }

    /** Сесть на значок. */
    void sitOnIcon(android.graphics.Rect r) {
        busy = true; img.setClipBounds(null); bub.setVisibility(View.GONE);
        goTo(r.centerX() - foxH / 2, r.top - foxH + (int) (10 * dp), false, () -> {
            play(rnd.nextBoolean() ? "sit" : "read", 999);
            h.postDelayed(() -> { if (busy) { busy = false; goTo(lp.x, baseY, false, () -> playFor("sit", 20, 40)); } }, 20000 + rnd.nextInt(20000));
        });
    }

    void cmdCome() { busy = false; img.setClipBounds(null); showBub("Here I am! — Я тут!", 3000); goTo(screenW / 2 - foxH / 2, screenH / 2 - foxH / 2, true, () -> play(has("wave") ? "wave" : "jump", 2)); }
    void cmdSleep() { busy = false; img.setClipBounds(null); showBub("Good night… — Сплю…", 3000); playFor("sleep", 300, 600); }
    void cmdWake() { showBub("I'm awake! — Проснулся!", 3000); play(has("wake") ? "wake" : "jump", 1); }
    void cmdDance() { showBub("Woo-hoo!", 2500); play(has("laugh") ? "laugh" : "jump", 3); }
    void cmdHide() {
        FoxEyes e = FoxEyes.I;
        if (e != null && e.onHome()) {
            java.util.List<FoxEyes.Icon> ic = e.icons();
            if (!ic.isEmpty()) { showBub("You can't see me! — Меня не видно!", 2500); final android.graphics.Rect r = ic.get(rnd.nextInt(ic.size())).r; h.postDelayed(() -> peekBehind(r), 1200); return; }
        }
        showBub("Hide and seek! — Прячусь!", 2500);
        busy = true;
        goTo(screenW - (int) (12 * dp), baseY, true, () -> {
            img.setClipBounds(new android.graphics.Rect(0, 0, (int) (imgW() * 0.35f), foxH));
            h.postDelayed(() -> { busy = false; img.setClipBounds(null); goTo(screenW - foxH * 2, baseY, false, () -> playFor("sit", 20, 40)); }, 15000);
        });
    }

    /** На рабочем столе лис иногда играет со значками. */
    private boolean homePlay() {
        FoxEyes e = FoxEyes.I;
        if (e == null || !e.onHome() || System.currentTimeMillis() < homeActAt || rnd.nextInt(100) >= 35) return false;
        java.util.List<FoxEyes.Icon> ic = e.icons();
        if (ic.isEmpty()) return false;
        android.graphics.Rect r = ic.get(rnd.nextInt(ic.size())).r;
        homeActAt = System.currentTimeMillis() + 60000;
        if (rnd.nextBoolean()) peekBehind(r); else sitOnIcon(r);
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("on", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        if (intent != null && intent.hasExtra("data")) {
            p.edit().putString("data", intent.getStringExtra("data")).putBoolean("on", true).apply();
        }
        startFg();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        loadData(p.getString("data", "{}"));
        if (root == null) build();
        if (!ticking) { ticking = true; pickNext(); h.post(tick); }
        if (intent != null && ACT_HEARD.equals(intent.getAction())) {
            try { heard(intent.getStringExtra("kind"), intent.getStringExtra("text")); }
            catch (Throwable t) { CrashLog.note(this, "лис: " + t); thinking = false; showBub("Ой, я споткнулся 🙈 Попробуй ещё раз", 4000); }
        }
        return START_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Лис на экране", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, FoxService.class).setAction(ACT_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat_fox)
                .setContentTitle(mood == 4 ? "Лис ушёл…" : mood == 3 ? "Лис обиделся" : mood == 2 ? "Лис грустит и скучает" : mood == 1 ? "Лис проголодался" : "Лис гуляет по экрану")
                .setContentText(mood >= 1 ? "Поиграй в Словопад — покорми его словами" : "Нажми, чтобы открыть Словопад")
                .setContentIntent(piOpen)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Спрятать лиса", piStop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, n);
    }

    private void loadData(String json) {
        try {
            data = new JSONObject(json);
            meta = data.optJSONObject("meta");
            if (meta == null) meta = new JSONObject();
            stage = data.optInt("stage", 0);
            everyMs = Math.max(60000, data.optLong("every", 10) * 60000L);
            idles.clear();
            JSONArray a = data.optJSONArray("idle");
            if (a != null) for (int i = 0; i < a.length(); i++) idles.add(a.getString(i));
            lines.clear();
            JSONArray l = data.optJSONArray("lines");
            if (l != null) for (int i = 0; i < l.length(); i++) {
                JSONArray x = l.getJSONArray(i);
                lines.add(new String[]{x.getString(0), x.optString(1, ""), x.optString(2, "")});
            }
            words.clear();
            JSONArray wd = data.optJSONArray("words");
            if (wd != null) for (int i = 0; i < wd.length(); i++) { JSONArray x = wd.getJSONArray(i); words.add(new String[]{x.getString(0), x.optString(1, "")}); }
            long de = data.optLong("dropEvery", 20);
            dropEveryMs = de <= 0 ? 0 : Math.max(3, de) * 60000L;
        } catch (Exception e) {
            data = new JSONObject();
            meta = new JSONObject();
        }
        if (nextDropAt == 0 && dropEveryMs > 0) nextDropAt = System.currentTimeMillis() + Math.min(dropEveryMs, 5 * 60000L);
        if (nextPhraseAt == 0) nextPhraseAt = System.currentTimeMillis() + 20000;
    }

    private void build() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        dp = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        foxH = (int) (78 * dp);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        bub = new TextView(this);
        bub.setTextColor(Color.parseColor("#1b2333"));
        bub.setTextSize(14);
        bub.setMaxWidth((int) (260 * dp));
        bub.setMaxLines(12);
        int pd = (int) (10 * dp);
        bub.setPadding(pd, (int) (7 * dp), pd, (int) (7 * dp));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(14 * dp);
        g.setStroke((int) (1.5f * dp), Color.parseColor("#2a3550"));
        bub.setBackground(g);
        bub.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.bottomMargin = (int) (4 * dp);
        root.addView(bub, bl);

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(img, new LinearLayout.LayoutParams(foxH, foxH));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = screenW / 2;
        lp.y = screenH - foxH - (int) (150 * dp);
        baseY = lp.y;

        root.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean drag; long downAt;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; drag = false; downAt = System.currentTimeMillis(); return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) + Math.abs(dy) > 12 * dp) drag = true;
                        if (drag) { lp.x = ox + (int) dx; lp.y = oy + (int) dy; targetX = -1; clampAndUpdate(); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!drag && System.currentTimeMillis() - downAt > 600) openAssist();
                        else if (!drag) onTap();
                        else { busy = false; img.setClipBounds(null); travX = -1; baseY = lp.y; if (stage > 0) play(has("surprise") ? "surprise" : "sit", 1); }
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(root, lp); } catch (Exception e) { stopSelf(); }

        // без голоса: лис только пишет
    }

    private boolean has(String name) { return meta.has(name); }

    private long lastFed() {
        long f = getSharedPreferences(PREF, MODE_PRIVATE).getLong("fed", 0);
        if (f == 0) { f = System.currentTimeMillis(); getSharedPreferences(PREF, MODE_PRIVATE).edit().putLong("fed", f).apply(); }
        return f;
    }

    private int calcMood() {
        if (stage <= 0) return 0;
        double hrs = (System.currentTimeMillis() - lastFed()) / 3600000.0;
        return hrs < 8 ? 0 : hrs < 20 ? 1 : hrs < 36 ? 2 : hrs < 72 ? 3 : 4;
    }

    private void checkMood() {
        int m = calcMood();
        if (m == mood) return;
        int was = mood; mood = m;
        if (was >= 2 && m == 0) {
            root.setVisibility(View.VISIBLE);
            curEn = "Yay! You're back! I'm full!"; curRu = "Ура! Ты вернулся! Я сытый!";
            showBub(curEn + "\n" + curRu, 8000);
            play(has("cheer") ? "cheer" : has("jump") ? "jump" : "sit", 3);
        }
        root.setVisibility(m == 4 ? View.GONE : View.VISIBLE);
        if (m == 4) nextPeekAt = System.currentTimeMillis() + 60000;
        startFg();
    }

    private void showBub(String text, long ms) {
        bub.setText(text);
        bub.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideBub); h.postDelayed(hideBub, ms);
        h.postDelayed(this::clampAndUpdate, 50);
    }

    /* долгое нажатие: лис слушает (невидимый экран распознавания) */
    private boolean listenOk = false;

    private void openAssist() {
        if (thinking) return;
        if (stage <= 0) { showBub("Zzz… Выучи 10 слов — проснусь и поболтаем", 4000); return; }
        closeTyping();
        listenOk = false;
        targetX = -1; travX = -1; busy = false; img.setClipBounds(null);
        showBub("🎤 Включаю уши…", 4000);
        try { brain().warm(data != null ? data.optJSONObject("ai") : null, Math.max(0, mood), stage); } catch (Throwable ignored) { }
        play(has("surprise") ? "surprise" : "sit", 999);
        boolean started = true;
        try { startActivity(new Intent(this, ListenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { started = false; }
        // Xiaomi может не пустить окно из фона — тогда пишем текстом
        h.postDelayed(() -> { if (!listenOk && !thinking && typingV == null) openTyping("Микрофон не включился — напиши мне 👇"); }, started ? 3000 : 100);
    }

    /* ---------- поле «напиши лису» (если голос не завёлся) ---------- */
    private LinearLayout typingV;

    void openTyping(String hint) {
        if (typingV != null || root == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pd = (int) (8 * dp);
        box.setPadding(pd, pd, pd, pd);
        GradientDrawable g = new GradientDrawable(); g.setColor(Color.WHITE); g.setCornerRadius(16 * dp); g.setStroke((int) (1.5f * dp), Color.parseColor("#2a3550"));
        box.setBackground(g);
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(hint);
        et.setTextColor(Color.parseColor("#1b2333"));
        et.setHintTextColor(Color.parseColor("#7a8399"));
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        et.setBackground(null);
        box.addView(et, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = new TextView(this); send.setText("➤"); send.setTextSize(22); send.setTextColor(Color.parseColor("#e8743b")); send.setPadding(pd, 0, pd, 0);
        TextView x = new TextView(this); x.setText("✕"); x.setTextSize(18); x.setTextColor(Color.parseColor("#7a8399")); x.setPadding(pd, 0, pd / 2, 0);
        box.addView(send); box.addView(x);
        Runnable go = () -> {
            String t = et.getText().toString().trim();
            closeTyping();
            if (!t.isEmpty()) heard("final", t);
        };
        send.setOnClickListener(v -> go.run());
        x.setOnClickListener(v -> { closeTyping(); pickNext(); });
        et.setOnEditorActionListener((v, a, e) -> { go.run(); return true; });
        WindowManager.LayoutParams tl = new WindowManager.LayoutParams(
                screenW - (int) (24 * dp), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        tl.gravity = Gravity.TOP | Gravity.START;
        tl.x = (int) (12 * dp);
        tl.y = Math.max((int) (60 * dp), lp.y - (int) (70 * dp));
        tl.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        try { wm.addView(box, tl); typingV = box; } catch (Exception e) { return; }
        et.requestFocus();
        h.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(et, 0);
        }, 200);
        h.postDelayed(() -> { if (typingV == box && et.getText().length() == 0) closeTyping(); }, 60000);
    }

    void closeTyping() {
        if (typingV == null) return;
        try { wm.removeView(typingV); } catch (Exception ignored) { }
        typingV = null;
    }

    /* что услышал лис и что ответил — только текстом в облачке */
    private void heard(String kind, String text) {
        if (root == null || kind == null) return;
        if (mood == 4) root.setVisibility(View.VISIBLE);
        targetX = -1; img.setScaleX(1);
        switch (kind) {
            case "ready": listenOk = true; showBub("🎤 Слушаю… говори", 9000); play(has("surprise") ? "surprise" : "sit", 999); break;
            case "partial": showBub("🎤 " + text, 9000); break;
            case "error": {
                listenOk = true;
                if ("mic".equals(text)) { showBub("Разреши микрофон в окне Словопада — или напиши мне", 6000); openTyping("Напиши лису…"); break; }
                if (!"6".equals(text) && !"7".equals(text)) openTyping("Напиши лису…");
                String m = "mic".equals(text) ? "Мне нужен доступ к микрофону 🙉" : "none".equals(text) ? "На телефоне нет распознавания речи" :
                        ("1".equals(text) || "2".equals(text)) ? "Без интернета не слышу. Скачай офлайн-языки в «Голосовом вводе Google»" : ("6".equals(text) || "7".equals(text)) ? "Ничего не услышал. Зажми меня и скажи ещё раз" : "Уши не включились (код " + text + ") — напиши мне";
                showBub(m, 5000); pickNext(); break;
            }
            case "final": {
                if (text == null || text.trim().isEmpty()) { showBub("Не расслышал. Зажми меня и скажи ещё раз", 5000); pickNext(); break; }
                if (hands == null) hands = new FoxHands(this);
                boolean cmd = false;
                try { cmd = hands.handle(text); } catch (Throwable t) { CrashLog.note(this, "команда «" + text + "»: " + t); }
                if (cmd) break;
                thinking = true;
                showBub("«" + text + "»\n…думаю", 40000);
                play("read", 999);
                JSONObject ai = data != null ? data.optJSONObject("ai") : null;
                brain().ask(text, ai, Math.max(0, mood), stage, reply -> {
                    thinking = false;
                    curEn = null;
                    long ms = Math.min(45000, 7000 + reply.length() * 70L);
                    showBub(reply, ms);
                    play(has("talk") ? "talk" : "sit", 3);
                    if (mood == 4) h.postDelayed(() -> { if (mood == 4) root.setVisibility(View.GONE); }, ms);
                }, part -> { if (thinking) { showBub(part + " …", 40000); if (!"talk".equals(anim)) play(has("talk") ? "talk" : "sit", 999); } });
                break;
            }
        }
    }

    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    private Bitmap[] framesOf(String name) {
        Bitmap[] f = cache.get(name);
        if (f != null) return f;
        JSONObject m = meta.optJSONObject(name);
        if (m == null) return null;
        try (InputStream in = getAssets().open("fox/" + name + ".png")) {
            Bitmap src = BitmapFactory.decodeStream(in);
            int w = m.getInt("w"), hh = m.getInt("h"), n = m.getInt("n");
            f = new Bitmap[n];
            for (int i = 0; i < n; i++) f[i] = Bitmap.createBitmap(src, Math.min(i * w, src.getWidth() - w), 0, w, Math.min(hh, src.getHeight()));
            cache.put(name, f);
            return f;
        } catch (Exception e) { return null; }
    }

    private void play(String name, int loops) {
        Bitmap[] f = framesOf(name);
        if (f == null) { f = framesOf("sit"); name = "sit"; if (f == null) return; }
        anim = name; frames = f; frame = 0; loopsLeft = loops;
        JSONObject m = meta.optJSONObject(name);
        fps = calmFps(name, m != null ? (float) m.optDouble("fps", 6) : 6);
        int wpx = (int) (foxH * (float) f[0].getWidth() / f[0].getHeight());
        img.setLayoutParams(new LinearLayout.LayoutParams(wpx, foxH));
        img.setImageBitmap(f[0]);
    }

    /* спокойная скорость кадров для жизни на экране */
    private float calmFps(String name, float f) {
        switch (name) {
            case "sleep": return 1.5f;
            case "sit": case "idle": case "sad": return 2.5f;
            case "read": return 2f;
            case "grumpy": return 2f;
            case "wag": return 4f;
            case "walk": return 6f;
            default: return f;
        }
    }

    /* играть анимацию примерно secs секунд */
    private void playFor(String name, int secMin, int secMax) {
        play(name, 1);
        if (frames == null) return;
        int secs = secMin + rnd.nextInt(Math.max(1, secMax - secMin + 1));
        loopsLeft = Math.max(1, Math.round(secs * fps / frames.length));
    }

    /* режим дня по часам телефона: 0 день, 1 вечер (сонный), 2 ночь */
    private int dayPhase() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return (h >= 23 || h < 7) ? 2 : (h >= 21) ? 1 : 0;
    }
    private boolean wasNight = false;

    private void pickNext() {
        if (busy) { loopsLeft = 999; return; }
        img.setScaleX(1);
        img.setClipBounds(null);
        targetX = -1;
        if (stage <= 0) { playFor("sleep", 120, 300); return; }
        int ph = dayPhase();
        if (ph == 2) {                                   // ночь: почти всё время спит, иногда ворочается
            wasNight = true;
            if (rnd.nextInt(100) < 92) playFor("sleep", 240, 480); else playFor("sit", 15, 30);
            return;
        }
        if (wasNight) {                                  // утро: потягивается и просыпается
            wasNight = false;
            if (has("wake")) { play("wake", 1); return; }
        }
        if (ph == 1 && rnd.nextInt(100) < 50) { playFor("sleep", 90, 240); return; } // вечером клюёт носом
        if (mood <= 1 && homePlay()) return;
        if ("read".equals(appKind)) { playFor(rnd.nextInt(4) == 0 ? "sleep" : "read", 60, 180); return; }
        if (mood == 3 || mood == 4) { playFor(rnd.nextBoolean() && has("grumpy") ? "grumpy" : "sad", 30, 70); return; }
        int r = rnd.nextInt(100);
        if (mood == 2 && r < 60) { playFor("sad", 30, 60); return; }
        if (mood == 1 && r < 25) { playFor("sad", 15, 30); return; }
        r = rnd.nextInt(100);
        if (r < 35) { playFor("read", 40, 90); return; }        // сидит с книжкой
        if (r < 60) { playFor("sit", 25, 60); return; }         // просто сидит, моргает
        if (r < 88) { playFor("sleep", 60, 180); return; }      // дремлет
        if (r < 96 && has("walk")) {                              // изредка прогуливается
            int margin = (int) (40 * dp);
            targetX = margin + rnd.nextInt(Math.max(1, screenW - foxH * 2 - margin));
            speed = 1.6f * dp;
            play("walk", 999);
            img.setScaleX(targetX < lp.x ? -1 : 1);
            return;
        }
        playFor("wag", 5, 9);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (root == null) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) { h.postDelayed(this, 3000); return; }
            if (frames != null) {
                frame++;
                if (frame >= frames.length) { frame = 0; loopsLeft--; }
                img.setImageBitmap(frames[frame]);
                if (travX >= 0) {
                    // движение — в mover (плавно, 60 к/с)
                } else if (targetX >= 0) {
                    int step = (int) Math.max(1, speed * (60f / fps) / 3f);
                    if (Math.abs(lp.x - targetX) <= step) { lp.x = targetX; targetX = -1; pickNext(); }
                    else lp.x += lp.x < targetX ? step : -step;
                    clampAndUpdate();
                } else if (loopsLeft <= 0) { if (inDrop || busy) loopsLeft = 999; else pickNext(); }
            }
            boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            if (land && root.getVisibility() == View.VISIBLE) root.setVisibility(View.GONE);
            else if (!land && root.getVisibility() == View.GONE && mood != 4 && !"camera".equals(appKind)) root.setVisibility(View.VISIBLE);
            long now = System.currentTimeMillis();
            if (now >= moodCheckAt) { moodCheckAt = now + 30000; checkMood(); }
            if (mood == 4) {
                if (peekUntil > 0 && now > peekUntil) { peekUntil = 0; root.setVisibility(View.GONE); nextPeekAt = now + 25 * 60000L; }
                else if (peekUntil == 0 && now >= nextPeekAt && dayPhase() != 2) {
                    peekUntil = now + 15000; root.setVisibility(View.VISIBLE);
                    curEn = GONE[0]; curRu = GONE[1]; showBub(GONE[0], 14000);
                    play(has("sad") ? "sad" : "sit", 3);
                }
                h.postDelayed(this, 1000); return;
            }
            if (dayPhase() == 0 && !busy && !inDrop && !thinking && bub.getVisibility() != View.VISIBLE && stage > 0 && mood == 0 && dropEveryMs > 0 && !words.isEmpty() && now >= nextDropAt
                    && targetX < 0 && ("read".equals(anim) || "sit".equals(anim))) { nextDropAt = now + dropEveryMs; startDrop(); }
            if (dayPhase() == 2 && now >= nextPhraseAt) nextPhraseAt = now + everyMs; // ночью молчит
            if (!inDrop && !busy && !thinking && !"chat".equals(appKind) && stage > 0 && now >= nextPhraseAt) { sayLine(); nextPhraseAt = now + (mood >= 1 ? Math.min(everyMs, 120000) : everyMs); }
            h.postDelayed(this, (long) (1000 / Math.max(1, fps)));
        }
    };

    private void clampAndUpdate() {
        int w = root.getWidth() > 0 ? root.getWidth() : foxH;
        int hh = root.getHeight() > 0 ? root.getHeight() : foxH;
        lp.x = Math.max(0, Math.min(lp.x, screenW - w));
        lp.y = Math.max((int) (24 * dp), Math.min(lp.y, screenH - hh));
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    private final Runnable hideBub = () -> { if (bub != null) bub.setVisibility(View.GONE); };

    private void sayLine() {
        String[] l;
        String[][] call = mood == 1 ? CALL_HUNGRY : mood == 2 ? CALL_SAD : mood == 3 ? CALL_GRUMPY : null;
        if (call != null && (lines.isEmpty() || rnd.nextInt(100) < 75)) { String[] c = call[rnd.nextInt(call.length)]; l = new String[]{c[0], c[1], ""}; }
        else if (lines.isEmpty()) return;
        else l = lines.get(rnd.nextInt(lines.size()));
        curEn = l[0]; curRu = l[1].isEmpty() ? l[2] : l[1]; showRu = false;
        bub.setText(curEn + (curRu.isEmpty() ? "" : "\n(тап — перевод)"));
        bub.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideBub);
        h.postDelayed(hideBub, 9000);
        if (targetX < 0 && "sleep".equals(anim)) playFor("sit", 20, 40); // проснулся сказать фразу
        h.postDelayed(this::clampAndUpdate, 50);
    }

    /* лис исчезает, появляется наверху и кидает вниз слово */
    private void startDrop() {
        inDrop = true; homeX = lp.x; homeY = lp.y;
        bub.setVisibility(View.GONE);
        root.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            lp.x = (int) (30 * dp) + rnd.nextInt(Math.max(1, screenW - foxH * 2));
            lp.y = (int) (60 * dp);
            clampAndUpdate();
            play(has("jump") ? "jump" : "sit", 1); loopsLeft = 999;
            root.animate().alpha(1f).setDuration(350).withEndAction(() -> {
                h.postDelayed(this::throwWord, 500);
            }).start();
        }).start();
    }

    private void throwWord() {
        if (root == null) return;
        String[] w = words.get(rnd.nextInt(words.size()));
        play(has("cheer") ? "cheer" : "sit", 1); loopsLeft = 999;
        wordV = new TextView(this);
        wordV.setText(w[0]);
        wordV.setTextColor(Color.WHITE);
        wordV.setTextSize(19);
        wordV.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int pd = (int) (12 * dp);
        wordV.setPadding(pd, (int) (7 * dp), pd, (int) (7 * dp));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#e8743b"));
        g.setCornerRadius(12 * dp);
        g.setStroke((int) (2 * dp), Color.parseColor("#2a1a10"));
        wordV.setBackground(g);
        wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = Math.max(0, lp.x);
        wlp.y = lp.y + foxH;
        final boolean[] caught = {false};
        wordV.setOnClickListener(v -> {
            if (caught[0]) return;
            caught[0] = true;
            wordV.setText(w[0] + " — " + w[1] + "   +1 🪙");
            GradientDrawable ok = new GradientDrawable();
            ok.setColor(Color.parseColor("#2f9e5b")); ok.setCornerRadius(12 * dp); ok.setStroke((int) (2 * dp), Color.parseColor("#123d22"));
            wordV.setBackground(ok);
            SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
            p.edit().putInt("catches", p.getInt("catches", 0) + 1).apply();
            h.postDelayed(this::endWord, 2600);
        });
        try { wm.addView(wordV, wlp); } catch (Exception e) { wordV = null; endDrop(); return; }
        final int startY = wlp.y, endY = screenH - (int) (190 * dp);
        final long t0 = System.currentTimeMillis(), dur = 7500;
        h.post(new Runnable() {
            @Override public void run() {
                if (wordV == null) return;
                if (caught[0]) return;
                float pr = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                wlp.y = (int) (startY + (endY - startY) * pr);
                int ww = wordV.getWidth() > 0 ? wordV.getWidth() : (int) (120 * dp);
                wlp.x = Math.max(0, Math.min(wlp.x, screenW - ww));
                try { wm.updateViewLayout(wordV, wlp); } catch (Exception ignored) { }
                if (pr >= 1f) { h.postDelayed(FoxService.this::endWord, 1500); return; }
                h.postDelayed(this, 33);
            }
        });
        h.postDelayed(this::endDrop, 1800);
    }

    private void endWord() {
        if (wordV == null) return;
        final TextView v = wordV; wordV = null;
        v.animate().alpha(0f).setDuration(400).withEndAction(() -> { try { wm.removeView(v); } catch (Exception ignored) { } }).start();
    }

    private void endDrop() {
        if (root == null) return;
        root.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            lp.x = homeX; lp.y = homeY; clampAndUpdate();
            inDrop = false; loopsLeft = 0;
            playFor("read", 30, 60);
            root.animate().alpha(1f).setDuration(400).start();
        }).start();
    }

    private void onTap() {
        if (stage <= 0) {
            bub.setText("Zzz… Выучи 10 слов — проснусь");
            bub.setVisibility(View.VISIBLE);
            h.removeCallbacks(hideBub); h.postDelayed(hideBub, 4000);
            h.postDelayed(this::clampAndUpdate, 50);
            return;
        }
        if (bub.getVisibility() == View.VISIBLE && curEn == null && !thinking) { bub.setVisibility(View.GONE); return; }
        if (mood >= 1) {
            String[] c = mood == 1 ? CALL_HUNGRY[1] : mood == 2 ? CALL_SAD[1] : mood == 3 ? CALL_GRUMPY[2] : GONE;
            curEn = c[0]; curRu = c[1];
            showBub(curEn + "\n" + curRu, 5000);
            play(has("jump") ? "jump" : "sit", 1);
            h.postDelayed(this::openApp, 900);
            return;
        }
        if (bub.getVisibility() == View.VISIBLE && curEn != null) {
            showRu = !showRu;
            bub.setText(showRu ? curEn + "\n" + curRu : curEn);
            h.removeCallbacks(hideBub); h.postDelayed(hideBub, 12000);
            h.postDelayed(this::clampAndUpdate, 50);
            return;
        }
        targetX = -1; img.setScaleX(1);
        sayLine();
        play(has("wave") ? "wave" : has("jump") ? "jump" : "sit", 1);
    }

    @Override
    public void onDestroy() {
        I = null;
        ticking = false;
        h.removeCallbacksAndMessages(null);
        closeTyping();
        if (wordV != null) { try { wm.removeView(wordV); } catch (Exception ignored) { } wordV = null; }
        if (root != null) { try { wm.removeView(root); } catch (Exception ignored) { } root = null; }
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
