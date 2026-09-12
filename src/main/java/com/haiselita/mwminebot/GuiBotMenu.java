package com.haiselita.mwminebot;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;

/**
 * The bots are only startable from here, never from a bare keypress, so a
 * stray key cannot set anything running.
 */
public class GuiBotMenu extends GuiScreen {

    private static final int ID_MINE = 1;
    private static final int ID_TUNNEL = 2;
    private static final int ID_STONE = 3;
    private static final int ID_LOG = 4;
    private static final int ID_DIRT = 5;
    private static final int ID_IRON = 6;
    private static final int ID_CHEST = 7;
    private static final int ID_IRONBLOCK = 16;
    private static final int ID_CLIMB = 17;
    private static final int ID_PILLAR = 18;
    private static final int ID_COAL = 19;
    private static final int ID_RANDOM = 8;
    private static final int ID_SPEED = 9;
    private static final int ID_HEIGHT = 10;
    private static final int ID_TIMER = 11;
    private static final int ID_BOX = 12;
    private static final int ID_DEBUG = 13;
    private static final int ID_STRICT = 14;
    private static final int ID_CLOSE = 15;

    @Override
    public void initGui() {
        buttonList.clear();

        int cx = width / 2;
        int y = height / 6 - 8;
        int w = 200;
        int hw = 98;
        int step = 22;

        buttonList.add(new GuiButton(ID_MINE, cx - w / 2, y, w, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_TUNNEL, cx - w / 2, y, w, 20, ""));
        y += step + 6;

        buttonList.add(new GuiButton(ID_STONE, cx - w / 2, y, hw, 20, ""));
        buttonList.add(new GuiButton(ID_LOG, cx + 2, y, hw, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_DIRT, cx - w / 2, y, hw, 20, ""));
        buttonList.add(new GuiButton(ID_IRON, cx + 2, y, hw, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_CHEST, cx - w / 2, y, hw, 20, ""));
        buttonList.add(new GuiButton(ID_IRONBLOCK, cx + 2, y, hw, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_COAL, cx - w / 2, y, w, 20, ""));
        y += step + 8;

        buttonList.add(new GuiButton(ID_SPEED, cx - w / 2, y, hw, 20, ""));
        buttonList.add(new GuiButton(ID_RANDOM, cx + 2, y, hw, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_HEIGHT, cx - w / 2, y, hw, 20, ""));
        buttonList.add(new GuiButton(ID_STRICT, cx + 2, y, hw, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_TIMER, cx - w / 2, y, hw, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_CLIMB, cx - w / 2, y, hw, 20, ""));
        buttonList.add(new GuiButton(ID_PILLAR, cx + 2, y, hw, 20, ""));
        y += step;
        buttonList.add(new GuiButton(ID_BOX, cx - w / 2, y, hw, 20, ""));
        buttonList.add(new GuiButton(ID_DEBUG, cx + 2, y, hw, 20, ""));
        y += step + 8;

        buttonList.add(new GuiButton(ID_CLOSE, cx - w / 2, y, w, 20, "Close"));

        refresh();
    }

    private void refresh() {
        MWMineBot bot = MWMineBot.instance;
        boolean allowed = ServerGuard.isAllowed();

        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            switch (b.id) {
                case ID_MINE:
                    b.displayString = bot.miningBot.isRunning() ? "Mining bot: running (stop)" : "Mining bot: stopped (start)";
                    b.enabled = allowed;
                    break;
                case ID_TUNNEL:
                    b.displayString = bot.tunnelBot.isRunning() ? "Tunnel bot: running (stop)" : "Tunnel bot: stopped (start)";
                    b.enabled = allowed;
                    break;
                case ID_STONE:  b.displayString = "Stone: " + onOff(BotConfig.mineStone); break;
                case ID_LOG:    b.displayString = "Logs: " + onOff(BotConfig.mineLog); break;
                case ID_DIRT:   b.displayString = "Dirt: " + onOff(BotConfig.mineDirt); break;
                case ID_IRON:   b.displayString = "Iron ore: " + onOff(BotConfig.mineIron); break;
                case ID_CHEST:  b.displayString = "Chests: " + onOff(BotConfig.mineChest); break;
                case ID_IRONBLOCK: b.displayString = "Iron blocks: " + onOff(BotConfig.mineIronBlock); break;
                case ID_CLIMB:  b.displayString = "Climb ledges: " + onOff(BotConfig.climbTerrain); break;
                case ID_PILLAR: b.displayString = "Pillar up: " + onOff(BotConfig.pillarUp); break;
                case ID_COAL:   b.displayString = "Coal: " + onOff(BotConfig.mineCoal); break;
                case ID_RANDOM: b.displayString = "Randomise: " + onOff(BotConfig.randomize); break;
                case ID_SPEED:  b.displayString = "Turn speed: " + BotConfig.rotateDurationMs + "ms"; break;
                case ID_HEIGHT: b.displayString = "2 block height: " + onOff(BotConfig.limitHeight2); break;
                case ID_TIMER:  b.displayString = "Walls Fall: " + onOff(BotConfig.wallTimerAlert); break;
                case ID_BOX:    b.displayString = "Show target: " + onOff(BotConfig.showTargetBox); break;
                case ID_DEBUG:  b.displayString = "Debug log: " + onOff(BotConfig.debugLog); break;
                case ID_STRICT: b.displayString = BotConfig.strictClick ? "Click: per block" : "Click: hold"; break;
                default: break;
            }
        }
    }

    private static String onOff(boolean v) {
        return v ? "§aON" : "§7OFF";
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        MWMineBot bot = MWMineBot.instance;

        switch (button.id) {
            case ID_MINE:
                if (bot.miningBot.isRunning()) {
                    bot.miningBot.stop("menu");
                } else {
                    bot.tunnelBot.stop("switched to the mining bot");
                    bot.miningBot.start();
                }
                break;
            case ID_TUNNEL:
                if (bot.tunnelBot.isRunning()) {
                    bot.tunnelBot.stop("menu");
                } else {
                    bot.miningBot.stop("switched to the tunnel bot");
                    bot.tunnelBot.start();
                }
                break;
            case ID_STONE:  BotConfig.mineStone = !BotConfig.mineStone; break;
            case ID_LOG:    BotConfig.mineLog = !BotConfig.mineLog; break;
            case ID_DIRT:   BotConfig.mineDirt = !BotConfig.mineDirt; break;
            case ID_IRON:   BotConfig.mineIron = !BotConfig.mineIron; break;
            case ID_CHEST:  BotConfig.mineChest = !BotConfig.mineChest; break;
            case ID_IRONBLOCK: BotConfig.mineIronBlock = !BotConfig.mineIronBlock; break;
            case ID_CLIMB: BotConfig.climbTerrain = !BotConfig.climbTerrain; break;
            case ID_PILLAR: BotConfig.pillarUp = !BotConfig.pillarUp; break;
            case ID_COAL: BotConfig.mineCoal = !BotConfig.mineCoal; break;
            case ID_RANDOM: BotConfig.randomize = !BotConfig.randomize; break;
            case ID_SPEED: {
                long[] steps = {40L, 60L, 80L, 120L, 160L, 220L};
                int idx = -1;
                for (int i = 0; i < steps.length; i++) {
                    if (steps[i] == BotConfig.rotateDurationMs) { idx = i; break; }
                }
                BotConfig.rotateDurationMs = steps[(idx + 1) % steps.length];
                break;
            }
            case ID_HEIGHT: BotConfig.limitHeight2 = !BotConfig.limitHeight2; break;
            case ID_TIMER: BotConfig.wallTimerAlert = !BotConfig.wallTimerAlert; break;
            case ID_BOX: BotConfig.showTargetBox = !BotConfig.showTargetBox; break;
            case ID_DEBUG: BotConfig.debugLog = !BotConfig.debugLog; break;
            case ID_STRICT: BotConfig.strictClick = !BotConfig.strictClick; break;
            case ID_CLOSE:
                mc.displayGuiScreen(null);
                return;
            default:
                break;
        }

        BotConfig.save();
        refresh();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "useful MW", width / 2, height / 6 - 34, 0xFFFFFF);
        drawCenteredString(fontRendererObj, "§7" + ServerGuard.statusText(), width / 2, height / 6 - 23, 0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
