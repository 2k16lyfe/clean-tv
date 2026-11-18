package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Lets Home change what chosen remote buttons open (for example the Netflix button).
 * Android lets an accessibility service see each key press before anything else, which is the only
 * supported way to remap a remote. Only the buttons set up on the "Remote buttons" screen are touched;
 * every other key is passed straight through untouched, and nothing is read or stored besides those buttons.
 */
public class ButtonService extends AccessibilityService {

    static final String PREFS = "buttons";
    static final int SLOTS = 4;

    /** Called with the key code and scan code of the next button pressed, or -1 if it was cancelled. */
    interface Learn {
        void onKey(int keyCode, int scanCode);
    }

    static volatile ButtonService instance;
    static volatile Learn learn;

    private final int[] kc = new int[SLOTS];
    private final int[] sc = new int[SLOTS];
    private final String[] pkg = new String[SLOTS];
    private final Set<Integer> held = new HashSet<>();

    /** Keys that must keep working so you can always get around, so they can never be remapped. */
    static boolean reserved(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
                return true;
            default:
                return false;
        }
    }

    /** Re-reads the saved buttons after the screen changed them. */
    static void reload() {
        ButtonService s = instance;
        if (s != null) s.load();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        load();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used. This service only listens for remote buttons.
    }

    @Override
    public void onInterrupt() {
    }

    private void load() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (int i = 0; i < SLOTS; i++) {
            kc[i] = p.getInt("k" + i, -1);
            sc[i] = p.getInt("s" + i, -1);
            pkg[i] = p.getString("p" + i, "");
        }
    }

    private int find(int k, int s) {
        for (int i = 0; i < SLOTS; i++) {
            if (kc[i] == k && sc[i] == s && pkg[i] != null && !pkg[i].isEmpty()) return i;
        }
        return -1;
    }

    private boolean open(String p) {
        try {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(p);
            if (i == null) i = pm.getLaunchIntentForPackage(p);
            if (i == null) return false; // app was uninstalled: let the original button do its thing
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Must be quick: return false at once for any key that is not one of yours. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        final int k = e.getKeyCode(), s = e.getScanCode();
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        Learn l = learn;
        if (l != null) {
            if (!down || e.getRepeatCount() > 0) return true; // swallow releases and repeats while learning
            learn = null;
            l.onKey(reserved(k) ? -1 : k, s);
            return true;
        }

        final int id = k * 1000 + s;
        if (!down) return held.remove(id); // true only if we used up its press, so the release is hidden too
        final int slot = find(k, s);
        if (slot < 0) return false;
        if (e.getRepeatCount() == 0) {
            if (!open(pkg[slot])) return false;
            held.add(id);
            return true;
        }
        return held.contains(id);
    }
}
