package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemTool;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

@Mod(modid = MWMineBot.MODID, name = "useful MW", version = "1.0", clientSideOnly = true)
public class MWMineBot {

    public static final String MODID = "mwminebot";

    public static MWMineBot instance;

    private final Minecraft mc = Minecraft.getMinecraft();

    public final MiningBot miningBot = new MiningBot();
    public final TunnelBot tunnelBot = new TunnelBot();
    public final WallTimer wallTimer = new WallTimer();

    private KeyBinding menuKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        instance = this;
        BotConfig.load();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new TargetRenderer());

        menuKey = new KeyBinding("Open MineBot menu", Keyboard.KEY_RCONTROL, "useful MW");
        ClientRegistry.registerKeyBinding(menuKey);
    }

    private static final Logger LOG = LogManager.getLogger("MWMineBot");

    /**
     * Running commentary, only with debug logging on. Like everything else
     * this mod says it goes to the game log (latest.log), never to chat.
     */
    public static void info(String msg) {
        if (BotConfig.debugLog) log(msg);
    }

    /**
     * Anything worth a record. Game log only: nothing from this mod is ever
     * put in the chat, where it only got in the way. Alerts that matter are
     * the sounds played alongside these.
     */
    public static void log(String msg) {
        if (msg == null) return;
        LOG.info(msg.replaceAll("§.", ""));
    }

    public void stopAll(String reason) {
        miningBot.stop(reason);
        tunnelBot.stop(reason);
    }

    /**
     * A pickaxe or axe wearing out mid-run is easy to miss, and everything
     * after it is slower without saying why.
     *
     * Only a digging tool, and only while a bot is running. The game fires
     * this event whenever a stack runs out on use -- placing the last block
     * of a stack, throwing the last ender pearl, armour breaking -- and all
     * of those used to set this sound off out of nowhere.
     */
    @SubscribeEvent
    public void onItemBroken(PlayerDestroyItemEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.entityPlayer != mc.thePlayer || event.original == null) return;
        if (!miningBot.isRunning() && !tunnelBot.isRunning()) return;
        Item item = event.original.getItem();
        if (!(item instanceof ItemTool) && !(item instanceof ItemShears)) return;
        try {
            log("Your " + event.original.getDisplayName() + " broke");
            if (mc.thePlayer != null) mc.thePlayer.playSound("note.pling", 1.0F, 0.5F);
        } catch (Exception ignored) { }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;

        // A real mouse button must never be left held, whatever else happens.
        Input.tick();

        if (mc.theWorld == null || mc.thePlayer == null) {
            Input.releaseAll();
            stopAll("left the world");
            wallTimer.reset();
            return;
        }

        // The menu is the only way in, so a single keypress can never start a bot.
        if (menuKey.isPressed()) {
            mc.displayGuiScreen(new GuiBotMenu());
        }

        // Chat, inventory, anything that is not our own menu: hands off.
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiBotMenu)) {
            stopAll("a screen was opened");
        }

        // Our own menu only suspends the bots; closing it resumes them.
        if (mc.currentScreen instanceof GuiBotMenu) {
            miningBot.pauseInputs();
            tunnelBot.pauseInputs();
            wallTimer.onClientTick();
            return;
        }

        // The bots refuse to run here; the Walls Fall alert below still does,
        // since it only reads the scoreboard and plays a sound.
        if (ServerGuard.isDenied()) {
            stopAll("blocked server");
        }

        miningBot.onClientTick();
        tunnelBot.onClientTick();

        // One invariant covering every stop path there is: nothing running,
        // nothing held.
        if (!miningBot.isRunning() && !tunnelBot.isRunning() && Input.anyHeld()) {
            Input.releaseAll();
        }
        wallTimer.onClientTick();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (ev.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null) return;
        // Sampled per frame rather than per tick, otherwise a 40-150ms curve
        // only gets one or two steps and looks like a snap.
        miningBot.onRenderTick();
        tunnelBot.onRenderTick();
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent ev) {
        if (ev.message == null) return;
        String plain = ev.message.getUnformattedText();
        if (plain == null) return;
        String formatted = null;
        try {
            formatted = ev.message.getFormattedText();
        } catch (Exception ignored) {}
        miningBot.onChat(plain, formatted);
        tunnelBot.onChat(plain, formatted);
    }
}
