package ru.movaowl.slovopad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/** Глаза и руки лиса: видит, какое приложение открыто и где значки; умеет листать и нажимать. */
public class FoxEyes extends AccessibilityService {
    static volatile FoxEyes I;
    static volatile String curPkg = "";
    private String homePkg = "";

    static final class Icon {
        final String label; final Rect r; final AccessibilityNodeInfo node;
        Icon(String l, Rect r, AccessibilityNodeInfo n) { label = l; this.r = r; node = n; }
    }

    @Override
    protected void onServiceConnected() {
        I = this;
        try {
            ResolveInfo ri = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (ri != null && ri.activityInfo != null) homePkg = ri.activityInfo.packageName;
        } catch (Exception ignored) { }
        FoxService s = FoxService.I;
        if (s != null) s.onEyes(true);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = e.getPackageName().toString();
        // клавиатура, шторка и сам лис — не «приложения»
        if (pkg.equals(getPackageName()) && e.getClassName() != null && !e.getClassName().toString().contains("MainActivity")) return;
        if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return;
        if (pkg.equals(curPkg)) return;
        curPkg = pkg;
        FoxService s = FoxService.I;
        if (s != null) s.onApp(pkg, isHome(pkg));
    }

    boolean isHome(String pkg) {
        return pkg.equals(homePkg) || pkg.contains("launcher") || pkg.equals("com.miui.home") || pkg.equals("com.mi.android.globallauncher");
    }

    boolean onHome() { return isHome(curPkg); }

    /** Значки на экране (рабочий стол): подпись + место. Только то, что реально видно на этой странице. */
    List<Icon> icons() {
        List<Icon> out = new ArrayList<>();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int min = (int) (24 * dm.density), max = (int) (180 * dm.density);
        try {
            boolean any = false;
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !isHome(root.getPackageName().toString())) continue;
                any = true;
                walk(root, out, min, max, dm.widthPixels, dm.heightPixels, 0);
            }
            if (!any) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && root.getPackageName() != null && isHome(root.getPackageName().toString())) walk(root, out, min, max, dm.widthPixels, dm.heightPixels, 0);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static boolean fits(Rect r, int min, int max, int w, int h) {
        return r.width() >= min && r.height() >= min && r.width() <= max * 2 && r.height() <= max * 2
                && r.left >= -2 && r.right <= w + 2 && r.top > h * 0.04 && r.bottom < h * 0.99;
    }

    private void walk(AccessibilityNodeInfo n, List<Icon> out, int min, int max, int screenW, int screenH, int depth) {
        if (n == null || depth > 30 || out.size() > 150) return;
        CharSequence t = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
        if (t != null && t.length() > 0 && t.length() < 40 && n.isVisibleToUser()) {
            String label = t.toString().trim();
            // значок = сама надпись или ближайший кликабельный родитель (у MIUI подпись — отдельный TextView)
            AccessibilityNodeInfo c = n; int up = 0;
            while (c != null && !c.isClickable() && !c.isLongClickable() && up < 3) { c = c.getParent(); up++; }
            Rect r = new Rect();
            if (c != null && (c.isClickable() || c.isLongClickable())) {
                c.getBoundsInScreen(r);
                if (fits(r, min, max, screenW, screenH)) { if (!has(out, label, r)) out.add(new Icon(label, r, c)); if (c == n) return; }
            } else {
                n.getBoundsInScreen(r);
                if (fits(r, min, max, screenW, screenH) && !has(out, label, r)) out.add(new Icon(label, r, n));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, min, max, screenW, screenH, depth + 1);
    }

    private static boolean has(List<Icon> out, String label, Rect r) {
        for (Icon i : out) if (i.label.equals(label) && Rect.intersects(i.r, r)) return true;
        return false;
    }

    /** «Отпечаток» страницы — чтобы понять, что листать дальше некуда. */
    String signature() {
        StringBuilder sb = new StringBuilder();
        for (Icon i : icons()) sb.append(i.label).append('@').append(i.r.centerX() / 40).append(',').append(i.r.centerY() / 40).append(';');
        return sb.toString();
    }

    /** Свайп по экрану (листать рабочие столы). */
    void swipe(boolean left, Runnable done) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        swipe(left ? dm.widthPixels * 0.82f : dm.widthPixels * 0.18f, left ? dm.widthPixels * 0.18f : dm.widthPixels * 0.82f, dm.heightPixels * 0.5f, 280, done);
    }

    /** Свайп из x0 в x1 на высоте y за ms миллисекунд. */
    void swipe(float x0, float x1, float y, long ms, Runnable done) {
        Path p = new Path();
        p.moveTo(x0, y);
        p.lineTo(x1, y);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(60, ms))).build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) { if (done != null) done.run(); }
            @Override public void onCancelled(GestureDescription gd) { if (done != null) done.run(); }
        }, null);
        if (!ok && done != null) done.run();
    }

    boolean click(Icon ic) {
        try { if (ic.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; } catch (Exception ignored) { }
        // запасной вариант — «тап» по центру значка
        Path p = new Path(); p.moveTo(ic.r.exactCenterX(), ic.r.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    boolean global(int action) { return performGlobalAction(action); }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        I = null;
        FoxService s = FoxService.I;
        if (s != null) s.onEyes(false);
        return super.onUnbind(intent);
    }
}
