package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Presses real keys and mouse buttons through java.awt.Robot.
 *
 * Everything the bot does to the game goes through here, so the game receives
 * ordinary input events: the same ones a hand on the keyboard produces. The
 * internal keybind flag is only used as a fallback when Robot is unavailable
 * or the key is bound to something with no AWT equivalent.
 */
public class Input {

    /** LWJGL keycode -> AWT virtual key. */
    private static final Map<Integer, Integer> VK = new HashMap<Integer, Integer>();

    static {
        VK.put(Keyboard.KEY_A, KeyEvent.VK_A);
        VK.put(Keyboard.KEY_B, KeyEvent.VK_B);
        VK.put(Keyboard.KEY_C, KeyEvent.VK_C);
        VK.put(Keyboard.KEY_D, KeyEvent.VK_D);
        VK.put(Keyboard.KEY_E, KeyEvent.VK_E);
        VK.put(Keyboard.KEY_F, KeyEvent.VK_F);
        VK.put(Keyboard.KEY_G, KeyEvent.VK_G);
        VK.put(Keyboard.KEY_H, KeyEvent.VK_H);
        VK.put(Keyboard.KEY_I, KeyEvent.VK_I);
        VK.put(Keyboard.KEY_J, KeyEvent.VK_J);
        VK.put(Keyboard.KEY_K, KeyEvent.VK_K);
        VK.put(Keyboard.KEY_L, KeyEvent.VK_L);
        VK.put(Keyboard.KEY_M, KeyEvent.VK_M);
        VK.put(Keyboard.KEY_N, KeyEvent.VK_N);
        VK.put(Keyboard.KEY_O, KeyEvent.VK_O);
        VK.put(Keyboard.KEY_P, KeyEvent.VK_P);
        VK.put(Keyboard.KEY_Q, KeyEvent.VK_Q);
        VK.put(Keyboard.KEY_R, KeyEvent.VK_R);
        VK.put(Keyboard.KEY_S, KeyEvent.VK_S);
        VK.put(Keyboard.KEY_T, KeyEvent.VK_T);
        VK.put(Keyboard.KEY_U, KeyEvent.VK_U);
        VK.put(Keyboard.KEY_V, KeyEvent.VK_V);
        VK.put(Keyboard.KEY_W, KeyEvent.VK_W);
        VK.put(Keyboard.KEY_X, KeyEvent.VK_X);
        VK.put(Keyboard.KEY_Y, KeyEvent.VK_Y);
        VK.put(Keyboard.KEY_Z, KeyEvent.VK_Z);

        VK.put(Keyboard.KEY_SPACE, KeyEvent.VK_SPACE);
        VK.put(Keyboard.KEY_TAB, KeyEvent.VK_TAB);
        VK.put(Keyboard.KEY_LSHIFT, KeyEvent.VK_SHIFT);
        VK.put(Keyboard.KEY_RSHIFT, KeyEvent.VK_SHIFT);
        VK.put(Keyboard.KEY_LCONTROL, KeyEvent.VK_CONTROL);
        VK.put(Keyboard.KEY_RCONTROL, KeyEvent.VK_CONTROL);
        VK.put(Keyboard.KEY_LMENU, KeyEvent.VK_ALT);
        VK.put(Keyboard.KEY_RMENU, KeyEvent.VK_ALT);

        VK.put(Keyboard.KEY_UP, KeyEvent.VK_UP);
        VK.put(Keyboard.KEY_DOWN, KeyEvent.VK_DOWN);
        VK.put(Keyboard.KEY_LEFT, KeyEvent.VK_LEFT);
        VK.put(Keyboard.KEY_RIGHT, KeyEvent.VK_RIGHT);
    }

    private static Robot robot;
    private static boolean robotUnavailable = false;

    /** Keybinds we are currently holding, and whether Robot did the pressing. */
    private static final Map<KeyBinding, Boolean> held = new LinkedHashMap<KeyBinding, Boolean>();

    private static long lastResync = 0L;

    /**
     * Longest anything may stay down. Right click in particular has to be a
     * bounded press: if some path ever fails to release it, the player is left
     * placing blocks with no way to stop it.
     */
    private static final long MAX_HOLD_MS = 4000L;

    /** When each held key went down. */
    private static final Map<KeyBinding, Long> heldSince = new LinkedHashMap<KeyBinding, Long>();

    private static Robot robot() {
        if (robot == null && !robotUnavailable) {
            try {
                robot = new Robot();
            } catch (Throwable t) {
                robotUnavailable = true;
                MWMineBot.log("§cRobot could not start. Falling back to internal key events");
            }
        }
        return robot;
    }

    public static boolean isHeld(KeyBinding kb) {
        return kb != null && held.containsKey(kb);
    }

    public static void set(KeyBinding kb, boolean down) {
        if (kb == null) return;
        if (down == held.containsKey(kb)) return;
        if (down) press(kb); else release(kb);
    }

    private static void press(KeyBinding kb) {
        int code = kb.getKeyCode();

        if (BotConfig.useRobotClick && Display.isActive() && robot() != null) {
            Integer mask = mouseMask(code);
            if (mask != null) {
                try {
                    robot().mousePress(mask);
                    held.put(kb, Boolean.TRUE);
                    stamp(kb);
                    return;
                } catch (Throwable ignored) {}
            } else {
                Integer vk = VK.get(code);
                if (vk != null) {
                    try {
                        robot().keyPress(vk);
                        held.put(kb, Boolean.TRUE);
                        stamp(kb);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }

        KeyBinding.setKeyBindState(code, true);
        held.put(kb, Boolean.FALSE);
    }

    private static void stamp(KeyBinding kb) {
        if (!heldSince.containsKey(kb)) heldSince.put(kb, Long.valueOf(System.currentTimeMillis()));
    }

    private static void release(KeyBinding kb) {
        Boolean viaRobot = held.remove(kb);
        heldSince.remove(kb);
        int code = kb.getKeyCode();

        if (viaRobot != null && viaRobot.booleanValue() && robot != null) {
            Integer mask = mouseMask(code);
            try {
                if (mask != null) robot.mouseRelease(mask);
                else {
                    Integer vk = VK.get(code);
                    if (vk != null) robot.keyRelease(vk);
                }
            } catch (Throwable ignored) {}
        }

        // Clear the internal flag too: a dropped key-up event must never leave
        // the game believing something is still held.
        KeyBinding.setKeyBindState(code, false);
    }

    public static void releaseAll() {
        for (KeyBinding kb : new LinkedHashMap<KeyBinding, Boolean>(held).keySet()) {
            release(kb);
        }
        held.clear();
        heldSince.clear();
    }

    /** True if we are holding anything at all. */
    public static boolean anyHeld() {
        return !held.isEmpty();
    }

    /**
     * Safety and resync. Real input is shared with the player's own hands, so
     * if they press and release the same key the game clears a state we still
     * believe we hold; re-press it. And nothing may stay held once the window
     * loses focus.
     */
    public static void tick() {
        if (held.isEmpty()) return;

        if (!Display.isActive()) {
            releaseAll();
            return;
        }

        long now = System.currentTimeMillis();

        // Hard cap, independent of every other code path.
        for (Map.Entry<KeyBinding, Long> e : new LinkedHashMap<KeyBinding, Long>(heldSince).entrySet()) {
            if (now - e.getValue().longValue() > MAX_HOLD_MS) release(e.getKey());
        }

        if (now - lastResync < 250L) return;
        lastResync = now;

        for (Map.Entry<KeyBinding, Boolean> e : new LinkedHashMap<KeyBinding, Boolean>(held).entrySet()) {
            KeyBinding kb = e.getKey();
            if (kb.isKeyDown()) continue;
            held.remove(kb);
            press(kb);
        }
    }

    /** MC stores mouse buttons as (button - 100). */
    private static Integer mouseMask(int keyCode) {
        switch (keyCode) {
            case -100: return InputEvent.BUTTON1_DOWN_MASK;
            case -99: return InputEvent.BUTTON3_DOWN_MASK;
            case -98: return InputEvent.BUTTON2_DOWN_MASK;
            default: return null;
        }
    }

    /**
     * A single press-and-release, for things like the hotbar keys. Falls back
     * to setting the slot directly when Robot cannot be used.
     */
    public static boolean tap(KeyBinding kb) {
        if (kb == null) return false;
        int code = kb.getKeyCode();

        if (BotConfig.useRobotClick && Display.isActive() && robot() != null) {
            Integer mask = mouseMask(code);
            if (mask != null) {
                try {
                    robot().mousePress(mask);
                    robot().mouseRelease(mask);
                    return true;
                } catch (Throwable ignored) {}
            }
            Integer vk = VK.get(code);
            if (vk != null) {
                try {
                    robot().keyPress(vk);
                    robot().keyRelease(vk);
                    return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    /** One right click, for placing a block. */
    public static void tapUse() {
        KeyBinding kb = Minecraft.getMinecraft().gameSettings.keyBindUseItem;
        if (tap(kb)) return;
        KeyBinding.onTick(kb.getKeyCode());
    }

    /** Convenience for the attack button, whatever it is bound to. */
    public static void setAttack(boolean down) {
        set(Minecraft.getMinecraft().gameSettings.keyBindAttack, down);
    }
}
