package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Plain properties file so settings survive without rebuilding the jar. */
public class BotConfig {

    /**
     * Bumped whenever a default changes in a way a saved file would otherwise
     * keep overriding. On an older file the affected keys are reset.
     */
    private static final int CONFIG_VERSION = 2;

    public static boolean mineStone = true;
    public static boolean mineLog = true;
    public static boolean mineDirt = true;
    public static boolean mineIron = true;
    /** Walk up terrain toward something higher instead of giving up on it. */
    public static boolean climbTerrain = true;

    /** Place blocks under our own feet to get up to something out of reach. */
    public static boolean pillarUp = true;

    public static boolean mineCoal = true;
    public static boolean mineIronBlock = true;
    public static boolean mineChest = true;

    /**
     * Restrict destinations to blocks the player can actually see. Off: a
     * search that cannot look past the first wall finds nothing worth walking
     * to, and a tree wrapped in leaves could never be reached at all.
     */
    public static boolean searchVisibleOnly = false;

    /**
     * Only press the button while the crosshair is actually on the block being
     * mined. With this off the button stays down through camera turns, which
     * is faster to watch but breaks whatever the crosshair sweeps across.
     */
    public static boolean strictClick = true;

    /** Hold the real left mouse button via java.awt.Robot. */
    public static boolean useRobotClick = true;

    /** Log every block the bot decides to break, with distance and reason. */
    public static boolean debugLog = false;

    /** Draw a glowing cube on the block currently being mined. */
    public static boolean showTargetBox = true;

    /** Mine only at foot and head height, never up or down. */
    public static boolean limitHeight2 = false;

    /** Time for one look-at, in ms. */
    public static long rotateDurationMs = 80L;

    /** Interpolation curve used for every look-at. */
    public static Rotator.Curve curve = Rotator.Curve.SMOOTHERSTEP;

    /** Vary the duration, the aim point inside the block, and the pause between blocks. */
    public static boolean randomize = true;

    /** +/- percentage applied to rotateDurationMs when randomize is on. */
    public static int randomDurationPct = 5;

    /** Delay before a hotbar swap actually happens, in ms. */
    public static int toolSwitchMs = 60;

    /** +/- percentage applied to toolSwitchMs when randomize is on. */
    public static int toolSwitchPct = 10;

    /** Longest pause between finishing one block and aiming at the next (ms). */
    public static int randomPauseMs = 90;

    /** How far around the player the mining bot looks for blocks to dig now. */
    public static int scanRadius = 4;

    /**
     * How far it looks when nothing is left in reach and it has to pick
     * somewhere to walk to. 20 x 10 is 41*21*41 = about 35k blocks per pass,
     * rate limited to twice a second.
     */
    public static int searchRadius = 20;

    /** Vertical half-height of that same search. */
    public static int searchRadiusY = 10;

    /** How many undiggable spots inside undigWindowSec triggers the warning. */
    public static int undigAlertCount = 5;

    /** Length of that window, in seconds. */
    public static int undigWindowSec = 20;

    public static boolean wallTimerAlert = true;

    /** Stop the bots when the wall countdown reaches wallTimerStopSec. */
    public static boolean wallTimerStopBots = true;

    /** Seconds left on the Walls Fall timer at which the bots are stopped. */
    public static int wallTimerStopSec = 1;

    /**
     * Substring the sidebar line must carry. The Mega Walls board reads
     * "Walls Fall: 01:35", and the Wither HP lines below it have no m:ss,
     * so this is enough to pin the right row. Empty = first m:ss line found.
     */
    public static String wallTimerKeyword = "Walls Fall";

    /**
     * Chat lines that mean "you may not break that". Only chests produce one:
     * "This chest is protected. You can only use your friends' chests."
     * Ordinary protected blocks say nothing and simply reappear.
     */
    public static final List<String> blockedMineMessages = new ArrayList<String>(Arrays.asList(
            "This chest is protected",
            "You can only use your friends",
            "このチェストは保護"
    ));

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/mwminebot.properties");
    }

    public static void load() {
        Properties p = new Properties();
        File f = file();
        if (f.exists()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                p.load(in);
            } catch (Exception ignored) {
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
            }
        }

        mineStone = bool(p, "mineStone", mineStone);
        mineLog = bool(p, "mineLog", mineLog);
        mineDirt = bool(p, "mineDirt", mineDirt);
        mineIron = bool(p, "mineIron", mineIron);
        climbTerrain = bool(p, "climbTerrain", climbTerrain);
        pillarUp = bool(p, "pillarUp", pillarUp);
        mineCoal = bool(p, "mineCoal", mineCoal);
        mineIronBlock = bool(p, "mineIronBlock", mineIronBlock);
        mineChest = bool(p, "mineChest", mineChest);
        limitHeight2 = bool(p, "limitHeight2", limitHeight2);
        showTargetBox = bool(p, "showTargetBox", showTargetBox);
        useRobotClick = bool(p, "useRobotClick", useRobotClick);
        strictClick = bool(p, "strictClick", strictClick);
        searchVisibleOnly = bool(p, "searchVisibleOnly", searchVisibleOnly);
        debugLog = bool(p, "debugLog", debugLog);
        randomize = bool(p, "randomize", randomize);
        undigAlertCount = (int) num(p, "undigAlertCount", undigAlertCount);
        undigWindowSec = (int) num(p, "undigWindowSec", undigWindowSec);
        wallTimerAlert = bool(p, "wallTimerAlert", wallTimerAlert);
        wallTimerStopBots = bool(p, "wallTimerStopBots", wallTimerStopBots);
        wallTimerStopSec = (int) num(p, "wallTimerStopSec", wallTimerStopSec);
        wallTimerKeyword = p.getProperty("wallTimerKeyword", wallTimerKeyword);

        rotateDurationMs = num(p, "rotateDurationMs", rotateDurationMs);
        randomDurationPct = (int) num(p, "randomDurationPct", randomDurationPct);
        toolSwitchMs = (int) num(p, "toolSwitchMs", toolSwitchMs);
        toolSwitchPct = (int) num(p, "toolSwitchPct", toolSwitchPct);
        randomPauseMs = (int) num(p, "randomPauseMs", randomPauseMs);
        scanRadius = (int) num(p, "scanRadius", scanRadius);
        searchRadius = (int) num(p, "searchRadius", searchRadius);
        searchRadiusY = (int) num(p, "searchRadiusY", searchRadiusY);

        try {
            String c = p.getProperty("curve");
            if (c != null) curve = Rotator.Curve.valueOf(c.trim().toUpperCase());
        } catch (Exception ignored) {}

        int fileVersion = (int) num(p, "configVersion", 0);
        if (fileVersion < 2) {
            // Was briefly defaulted to true; the search is meant to look
            // through terrain.
            searchVisibleOnly = false;
        }

        String custom = p.getProperty("blockedMineMessages", "");
        if (!custom.trim().isEmpty()) {
            blockedMineMessages.clear();
            for (String s : custom.split("\\|")) {
                String t = s.trim();
                if (!t.isEmpty()) blockedMineMessages.add(t);
            }
        }

        if (fileVersion < CONFIG_VERSION) save();
    }

    public static void save() {
        Properties p = new Properties();

        p.setProperty("mineStone", String.valueOf(mineStone));
        p.setProperty("mineLog", String.valueOf(mineLog));
        p.setProperty("mineDirt", String.valueOf(mineDirt));
        p.setProperty("mineIron", String.valueOf(mineIron));
        p.setProperty("climbTerrain", String.valueOf(climbTerrain));
        p.setProperty("pillarUp", String.valueOf(pillarUp));
        p.setProperty("mineCoal", String.valueOf(mineCoal));
        p.setProperty("mineIronBlock", String.valueOf(mineIronBlock));
        p.setProperty("mineChest", String.valueOf(mineChest));
        p.setProperty("limitHeight2", String.valueOf(limitHeight2));
        p.setProperty("showTargetBox", String.valueOf(showTargetBox));
        p.setProperty("useRobotClick", String.valueOf(useRobotClick));
        p.setProperty("strictClick", String.valueOf(strictClick));
        p.setProperty("searchVisibleOnly", String.valueOf(searchVisibleOnly));
        p.setProperty("debugLog", String.valueOf(debugLog));
        p.setProperty("randomize", String.valueOf(randomize));
        p.setProperty("undigAlertCount", String.valueOf(undigAlertCount));
        p.setProperty("undigWindowSec", String.valueOf(undigWindowSec));
        p.setProperty("wallTimerAlert", String.valueOf(wallTimerAlert));
        p.setProperty("wallTimerStopBots", String.valueOf(wallTimerStopBots));
        p.setProperty("wallTimerStopSec", String.valueOf(wallTimerStopSec));
        p.setProperty("wallTimerKeyword", wallTimerKeyword);
        p.setProperty("rotateDurationMs", String.valueOf(rotateDurationMs));
        p.setProperty("randomDurationPct", String.valueOf(randomDurationPct));
        p.setProperty("toolSwitchMs", String.valueOf(toolSwitchMs));
        p.setProperty("toolSwitchPct", String.valueOf(toolSwitchPct));
        p.setProperty("randomPauseMs", String.valueOf(randomPauseMs));
        p.setProperty("scanRadius", String.valueOf(scanRadius));
        p.setProperty("searchRadius", String.valueOf(searchRadius));
        p.setProperty("searchRadiusY", String.valueOf(searchRadiusY));
        p.setProperty("curve", curve.name());

        p.setProperty("configVersion", String.valueOf(CONFIG_VERSION));

        StringBuilder msgs = new StringBuilder();
        for (String s : blockedMineMessages) {
            if (msgs.length() > 0) msgs.append("|");
            msgs.append(s);
        }
        p.setProperty("blockedMineMessages", msgs.toString());

        FileOutputStream out = null;
        try {
            File f = file();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            out = new FileOutputStream(f);
            p.store(out, "useful MW");
        } catch (Exception ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static long num(Properties p, String k, long def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
