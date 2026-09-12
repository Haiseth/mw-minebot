package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the sidebar, finds the wall-fall countdown and plays a sound at the
 * configured thresholds.
 *
 * The Mega Walls sidebar reads:
 *
 *   MEGA WALLS
 *   07/25/25  M40E
 *   Walls Fall: 01:35     <- this line
 *   [R] Wither HP: 1,000
 *   ...
 *
 * so the row is pinned by the "Walls Fall" keyword (BotConfig.wallTimerKeyword)
 * and the m:ss is read off it. Nothing else on the board carries a m:ss, but
 * the keyword keeps it unambiguous if the recreation adds another timer.
 */
public class WallTimer {

    private static final int[] THRESHOLDS_SEC = {90, 60, 30};
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Set<Integer> fired = new HashSet<Integer>();
    private int lastSeconds = -1;
    private boolean stoppedBots = false;

    public void reset() {
        fired.clear();
        lastSeconds = -1;
        stoppedBots = false;
    }

    public void onClientTick() {
        if (!BotConfig.wallTimerAlert) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        int seconds = readCountdown();
        if (seconds < 0) {
            // The line can vanish on the very last tick; treat that as zero.
            if (BotConfig.wallTimerStopBots && !stoppedBots && lastSeconds >= 0 && lastSeconds <= 3) {
                stoppedBots = true;
                if (MWMineBot.instance != null) MWMineBot.instance.stopAll("Walls Fall");
            }
            return;
        }

        // Counting up, or a new round: forget what already fired.
        if (lastSeconds >= 0 && seconds > lastSeconds) {
            fired.clear();
            stoppedBots = false;
        }
        lastSeconds = seconds;

        // The walls coming down means it is time to fight, not to keep mining.
        if (BotConfig.wallTimerStopBots && !stoppedBots && seconds <= BotConfig.wallTimerStopSec) {
            stoppedBots = true;
            if (MWMineBot.instance != null) {
                MWMineBot.instance.stopAll("Walls Fall " + format(seconds));
            }
        }

        for (int t : THRESHOLDS_SEC) {
            if (seconds <= t && !fired.contains(t)) {
                fired.add(t);
                alert(t);
                break;
            }
        }
    }

    private static String format(int seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    private void alert(int threshold) {
        MWMineBot.log("§cWalls fall in " + format(threshold));
        try {
            mc.thePlayer.playSound("note.pling", 1.0F, 0.8F);
            mc.thePlayer.playSound("random.orb", 1.0F, 1.2F);
        } catch (Exception ignored) {}
    }

    /** Seconds remaining, or -1 when no countdown line was found. */
    private int readCountdown() {
        try {
            Scoreboard sb = mc.theWorld.getScoreboard();
            if (sb == null) return -1;
            ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
            if (obj == null) return -1;

            Collection<Score> scores = sb.getSortedScores(obj);
            if (scores == null) return -1;

            String keyword = BotConfig.wallTimerKeyword.trim();

            for (Score score : scores) {
                String name = score.getPlayerName();
                ScorePlayerTeam team = sb.getPlayersTeam(name);
                String line = ScorePlayerTeam.formatPlayerName(team, name);
                line = EnumChatFormatting.getTextWithoutFormattingCodes(line);
                if (line == null) continue;

                if (!keyword.isEmpty() && !line.contains(keyword)) continue;

                Matcher m = TIME.matcher(line);
                if (m.find()) {
                    return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
