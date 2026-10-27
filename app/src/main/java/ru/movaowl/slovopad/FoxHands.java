package ru.movaowl.slovopad;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Руки лиса: голосовые команды без нейросети — открой, найди, иди сюда, спрячься, домой, назад… */
final class FoxHands {
    private final FoxService s;
    private final Handler h = new Handler(Looper.getMainLooper());
    private List<String[]> apps; // {label, pkg}
    private long appsAt = 0;

    FoxHands(FoxService s) { this.s = s; }

    private static final Map<String, String[]> ALIAS = new HashMap<>();
    static {
        String[][] a = {
            {"camera", "камера"}, {"settings", "настройки"}, {"gallery", "галерея", "фото"}, {"photos", "галерея", "фото"},
            {"calculator", "калькулятор"}, {"clock", "часы"}, {"alarm", "часы", "будильник"}, {"messages", "сообщения"}, {"sms", "сообщения"},
            {"phone", "телефон"}, {"calls", "телефон"}, {"contacts", "контакты"}, {"calendar", "календарь"}, {"notes", "заметки"},
            {"music", "музыка"}, {"weather", "погода"}, {"browser", "браузер", "chrome"}, {"internet", "браузер", "chrome"},
            {"files", "проводник", "файлы"}, {"mail", "почта", "gmail"}, {"email", "почта", "gmail"}, {"maps", "карты", "maps"},
            {"store", "play маркет", "google play"}, {"market", "play маркет"}, {"recorder", "диктофон"}, {"compass", "компас"},
            {"flashlight", "фонарик"}, {"video", "видео"}, {"books", "книги", "литрес"}, {"bank", "сбербанк", "сбер"},
            {"vk", "вконтакте", "vk"}, {"вэка", "vk", "вконтакте"}, {"викей", "vk", "вконтакте"}, {"вкашка", "vk"}, {"слово", "словопад"}, {"словопад", "словопад"}, {"телега", "telegram"}, {"ютуб", "youtube"}, {"вк", "vk"}, {"вконтакте", "vk"},
            {"сбер", "сбербанк"}, {"авито", "avito"}, {"ватсап", "whatsapp"}, {"вотсап", "whatsapp"}, {"гугл", "google"}, {"яндекс", "яндекс"}};
        for (String[] x : a) { String[] v = new String[x.length - 1]; System.arraycopy(x, 1, v, 0, v.length); ALIAS.put(x[0], v); }
    }

    /** Попробовать выполнить как команду. true — команда понята. */
    boolean handle(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[.,!?«»\"]", " ").replaceAll("\\b(please|пожалуйста|fox|foxy|лис|лисенок|лисёнок|hey|эй)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        Matcher m;
        if ((m = Pattern.compile("^(?:open up|open|launch|start|run|find|show me|show|go to|опен|оупен|оупэн|апен|открой|откройте|запусти|включи|найди|покажи|зайди в)\\s+(?:the\\s+|app\\s+|приложение\\s+)?(.+)$").matcher(t)).find()) {
            open(m.group(1).trim()); return true;
        }
        if (t.matches(".*(come here|come to me|иди сюда|ко мне|подойди|сюда).*")) { s.cmdCome(); return true; }
        if (t.matches(".*(hide|спрячься|прячься|спрятаться).*")) { s.cmdHide(); return true; }
        if ((m = Pattern.compile("(?:sit on|сядь на)\\s+(.+)").matcher(t)).find()) { sitOn(m.group(1).trim()); return true; }
        if (t.matches(".*(go home|home screen|на главн|домой|рабочий стол).*")) {
            global(AccessibilityService.GLOBAL_ACTION_HOME, "Going home! — Домой!"); return true;
        }
        if (t.matches("^(go back|back|назад|вернись)$")) { global(AccessibilityService.GLOBAL_ACTION_BACK, "Back! — Назад!"); return true; }
        if (t.matches(".*(notifications|шторк|уведомлени).*")) { global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Here are your notifications."); return true; }
        if (t.matches(".*(go to sleep|^sleep|спать|^спи|ложись).*")) { s.cmdSleep(); return true; }
        if (t.matches(".*(wake up|проснись|просыпайся).*")) { s.cmdWake(); return true; }
        if (t.matches(".*(dance|jump|танцуй|прыгай|попрыгай).*")) { s.cmdDance(); return true; }
        if ((m = Pattern.compile("(scroll|swipe|листай|полистай|пролистай)\\s*(left|right|влево|вправо|назад)?").matcher(t)).find()) {
            boolean right = m.group(2) != null && m.group(2).matches("right|вправо|назад");
            FoxEyes e = FoxEyes.I;
            if (e == null) { s.say(needEyes(), 7000); return true; }
            s.say(right ? "Swipe! — Листаю назад" : "Swipe! — Листаю дальше", 3000);
            e.swipe(!right, null); return true;
        }
        return false;
    }

    private String needEyes() {
        return "Включи мне глаза: Настройки → Спец. возможности → «Глаза лиса». Тогда смогу листать и искать значки.";
    }

    private void global(int a, String say) {
        FoxEyes e = FoxEyes.I;
        if (e == null) { s.say(needEyes(), 7000); return; }
        s.say(say, 2500); s.anim("jump", 1);
        e.global(a);
    }

    /* ---------- поиск приложения ---------- */
    private List<String[]> apps() {
        if (apps != null && System.currentTimeMillis() - appsAt < 5 * 60_000L) return apps;
        List<String[]> out = new ArrayList<>();
        PackageManager pm = s.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) out.add(new String[]{r.loadLabel(pm).toString(), r.activityInfo.packageName});
        apps = out; appsAt = System.currentTimeMillis();
        return out;
    }

    static String norm(String x) { return x.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^a-zа-я0-9]", ""); }

    static String translit(String x) {
        String[] f = {"а","a","б","b","в","v","г","g","д","d","е","e","ж","zh","з","z","и","i","й","y","к","k","л","l","м","m","н","n","о","o","п","p","р","r","с","s","т","t","у","u","ф","f","х","h","ц","ts","ч","ch","ш","sh","щ","sch","ъ","","ы","y","ь","","э","e","ю","yu","я","ya"};
        StringBuilder sb = new StringBuilder();
        for (char ch : x.toCharArray()) {
            String c = String.valueOf(ch), out = c;
            for (int k = 0; k < f.length; k += 2) if (f[k].equals(c)) { out = f[k + 1]; break; }
            sb.append(out);
        }
        return sb.toString();
    }

    private static int score(String label, String q) {
        String L = norm(label), Q = norm(q);
        if (L.isEmpty() || Q.isEmpty()) return 0;
        if (L.equals(Q)) return 100;
        String Lt = translit(L), Qt = translit(Q);
        if (Lt.equals(Qt)) return 92;
        if (L.startsWith(Q) || Q.startsWith(L)) return 80;
        if (Lt.startsWith(Qt) || Qt.startsWith(Lt)) return 75;
        if (L.contains(Q) || Lt.contains(Qt)) return 62;
        if (Q.length() >= 4 && QuizReceiver.lev(Lt, Qt) <= 2) return 55;
        return 0;
    }

    /** Лучшее приложение под название (с учётом английских слов → русских подписей). */
    String[] find(String q) {
        List<String> names = new ArrayList<>();
        names.add(q);
        String n = norm(q);
        if (ALIAS.containsKey(n)) for (String a : ALIAS.get(n)) names.add(a);
        String ru = s.brain().enRu(q);
        if (ru != null) for (String part : ru.split("[,;]")) names.add(part.trim());
        String[] best = null; int bs = 0;
        for (String[] app : apps()) for (String nm : names) {
            int sc = score(app[0], nm);
            if (sc > bs) { bs = sc; best = app; }
        }
        return bs >= 55 ? best : null;
    }

    private FoxEyes.Icon iconFor(String label) {
        FoxEyes e = FoxEyes.I;
        if (e == null) return null;
        FoxEyes.Icon best = null; int bs = 0;
        for (FoxEyes.Icon ic : e.icons()) { int sc = score(ic.label, label); if (sc > bs) { bs = sc; best = ic; } }
        return bs >= 62 ? best : null;
    }

    private int job = 0;

    void open(String q) {
        String[] app = find(q);
        if (app == null) { s.say("Не нашёл приложение «" + q + "». I can't find it.", 6000); s.anim("sad", 1); return; }
        String label = app[0];
        FoxEyes e = FoxEyes.I;
        if (e == null) {
            s.say("Open " + label + "! Открываю. Включи мне «Глаза лиса» в Спец. возможностях — буду сам искать значок и жать лапой.", 7000);
            s.anim("jump", 1); launch(app[1], 900); return;
        }
        final int my = ++job;
        s.startJob();
        s.say("Let me find " + label + "… — Ищу «" + label + "»", 2500);
        // страховка: что бы ни случилось — через 45 с отпускаем лиса
        h.postDelayed(() -> { if (job == my) { job++; s.cmdDone(); } }, 45000);
        // «Домой» два раза: первый — на рабочий стол, второй — на главную страницу
        e.global(AccessibilityService.GLOBAL_ACTION_HOME);
        h.postDelayed(() -> {
            if (job != my) return;
            if (FoxEyes.I != null) FoxEyes.I.global(AccessibilityService.GLOBAL_ACTION_HOME);
            h.postDelayed(() -> scan(app, my, true, 0, null, true), 900);
        }, 800);
    }

    /** Ищем значок на текущей странице; нет — лис листает. Сначала вперёд до упора, потом назад. */
    private void scan(String[] app, int my, boolean forward, int steps, String prevSig, boolean first) {
        if (job != my) return;
        FoxEyes e = FoxEyes.I;
        if (e == null) { job++; s.cmdDone(); launch(app[1], 300); return; }
        FoxEyes.Icon ic = iconFor(app[0]);
        if (ic != null) {
            s.say("Found it! — Вот «" + app[0] + "»", 2500);
            h.postDelayed(() -> {
                if (job != my) return;
                s.tapIcon(ic.r, () -> {
                    if (job != my) return;
                    job++;
                    boolean ok = false;
                    try { ok = e.click(ic); } catch (Exception ignored) { }
                    if (!ok) launch(app[1], 0);
                    s.cmdDone();
                });
            }, 400);
            return;
        }
        String sig = e.signature();
        boolean edge = prevSig != null && sig.equals(prevSig);   // страница не сменилась — край
        if (edge || steps >= 8) {
            if (forward) {                                       // справа нет — проверим страницы левее
                s.say("Тут нет… а если слева? ←", 2000);
                h.postDelayed(() -> s.pushPage(false, false, () -> scan(app, my, false, 1, sig, false)), 600);
                return;
            }
            job++;
            s.say("На столах не нашёл, открываю из списка. Here it is!", 4000);
            s.cmdDone();
            launch(app[1], 300);
            return;
        }
        s.pushPage(forward, first, () -> scan(app, my, forward, steps + 1, sig, false));
    }

    private void sitOn(String q) {
        String[] app = find(q);
        FoxEyes.Icon ic = app != null ? iconFor(app[0]) : iconFor(q);
        if (ic == null) { s.say(FoxEyes.I == null ? needEyes() : "Не вижу такого значка на экране.", 5000); return; }
        s.sitOnIcon(ic.r);
    }

    private void launch(String pkg, long delay) {
        h.postDelayed(() -> {
            Intent i = s.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) try { s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }, delay);
    }
}
