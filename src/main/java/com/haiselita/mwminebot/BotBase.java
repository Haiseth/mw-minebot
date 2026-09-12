package com.haiselita.mwminebot;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Shared plumbing: key holding, look-at, digging one block, walking one block. */
public abstract class BotBase {

    protected static final int WORKING = 0;
    protected static final int DONE = 1;
    protected static final int FAILED = 2;
    /** Something else is between the crosshair and the target. */
    protected static final int BLOCKED = 3;

    private static final long DIG_TIMEOUT_MS = 5000L;

    /** Most extra time a slow block can add on top of DIG_TIMEOUT_MS. */
    private static final long MAX_EXTRA_DIG_MS = 30000L;
    private static final long MOVE_TIMEOUT_MS = 2000L;
    private static final float AIM_TOLERANCE_DEG = 2.0f;

    /**
     * Once the camera has settled, the crosshair should be on the block almost
     * immediately. If it is not, the block is out of reach or behind something
     * and there is no point waiting out the full dig timeout.
     */
    private static final long AIM_FAIL_MS = 500L;

    /** Judge what the crosshair is on by this point, settled or not. */
    private static final long AIM_JUDGE_MS = 350L;

    /**
     * How long the button keeps being held after the crosshair last sat on the
     * target. Without this, a single frame of drift releases and re-presses,
     * which reads as click spam and throws away the break progress every time.
     */
    private static final long CLICK_GRACE_MS = 220L;

    /** How close to the middle of the target column counts as arrived. */
    private static final double CENTER_TOLERANCE = 0.30;

    /**
     * Half-width of the random aim offset. 0.5 would be the exact edge of the
     * block, so this uses most of the face and then verifies the point with a
     * raytrace before committing to it.
     */
    private static final double AIM_JITTER = 0.38;

    /** Longest stall after the crosshair was last on the target. */
    private static final long OFF_TARGET_FAIL_MS = 1200L;

    /** Eye movement that invalidates the aim point and forces a recompute. */
    private static final double AIM_REFRESH_DIST = 0.6;

    /** Ticks of near-zero movement while holding W before calling it stuck. */
    private static final int STUCK_TICKS = 8;

    /** Blocks per tick below which the player counts as not moving. */
    private static final double STUCK_SPEED = 0.015;

    /** Axis component above which a movement key is pressed. */
    private static final double MOVE_AXIS_MIN = 0.35;

    /**
     * Only chests are announced. Ordinary protected blocks send nothing at all
     * -- the client shows them broken and the server silently puts them back --
     * so those are caught by revive counting in MiningBot instead.
     */
    private static final String[] CHEST_HINTS = {"chest", "protected", "チェスト"};

    protected final Minecraft mc = Minecraft.getMinecraft();
    protected final Rotator rotator = new Rotator();
    protected final Random rng = new Random();

    private boolean running = false;
    private final Set<KeyBinding> held = new HashSet<KeyBinding>();

    private BlockPos digTarget = null;
    private Vec3 digAimPoint = null;
    private long digStart = 0L;
    private long aimSettledAt = 0L;
    private boolean sawOnTarget = false;
    private BlockPos obstruction = null;
    private boolean failedOnUnbreakable = false;
    private String failReason = "failed";
    private Block digTargetBlock = null;

    /** The last block actually broken, so the next pick can stay with it. */
    private BlockPos lastMinedPos = null;
    private Block lastMinedBlock = null;
    private long lastOnTargetAt = 0L;
    private long aimTriedSince = 0L;
    private Vec3 aimFromEye = null;

    /** What the crosshair sat on last tick, to spot blocks broken by accident. */
    private BlockPos lastCrosshair = null;
    private Block lastCrosshairBlock = null;
    private long moveStart = 0L;
    private long nextActionAt = 0L;

    private int pendingSlot = -1;
    private long toolSwitchAt = 0L;

    private double lastX, lastZ;
    private int stuckTicks = 0;

    public abstract String displayName();

    protected abstract void onStart();

    protected abstract void onTickRunning();

    /** Called when the server refuses a break. {@code pos} may be null. */
    protected abstract void onMineBlocked(BlockPos pos);

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;
        if (!ServerGuard.isAllowed()) {
            MWMineBot.log("Will not run on this server (" + ServerGuard.statusText() + ")");
            // No chat any more, so say it with a sound rather than silently
            // doing nothing.
            try {
                mc.thePlayer.playSound("note.bass", 1.0F, 0.5F);
            } catch (Exception ignored) { }
            return;
        }
        if (isCreative()) {
            MWMineBot.info("§eCreative mode: one click destroys a block, so collateral breaks are likelier");
        }
        running = true;
        digTarget = null;
        digAimPoint = null;
        nextActionAt = 0L;
        rotator.cancel();
        ToolSelector.remember();
        onStart();
        MWMineBot.info("§a" + displayName() + " started");
    }

    public void stop(String reason) {
        if (!running) return;
        running = false;
        releaseAll();
        rotator.cancel();
        digTarget = null;
        digAimPoint = null;
        ToolSelector.restore();
        MWMineBot.info("§e" + displayName() + " stopped" + (reason == null ? "" : " (" + reason + ")"));
    }

    public void onClientTick() {
        if (!running) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            stop("left the world");
            return;
        }
        if (!ServerGuard.isAllowed()) {
            stop("blocked server");
            return;
        }
        updateStuck();
        noteBreakSinceLastTick();
        onTickRunning();
    }

    public void onRenderTick() {
        if (running) rotator.update();
    }

    /**
     * Drops every held key without stopping the bot. MovementInputFromOptions
     * reads keybind state even while a GUI is open, so a held W would walk the
     * player around behind the menu.
     */
    public void pauseInputs() {
        if (!held.isEmpty()) releaseAll();
        rotator.cancel();
        digTarget = null;
        digAimPoint = null;
    }

    /**
     * @param plain     message with formatting stripped
     * @param formatted same message with the colour codes still in it
     */
    public void onChat(String plain, String formatted) {
        if (!running || plain == null) return;
        String lower = plain.toLowerCase();

        for (String needle : BotConfig.blockedMineMessages) {
            String n = needle.toLowerCase();
            if (n.isEmpty()) continue;
            if (lower.contains(n)) {
                // Always logged, not gated behind debug: whatever the bot
                // happened to be digging is abandoned the instant this
                // fires, so the exact line that caused it has to be
                // recoverable afterwards, not only when debug log was
                // already on.
                MWMineBot.log("Chat matched \"" + needle + "\" -> treating current target as blocked: " + plain);
                fireBlocked(null);
                return;
            }
        }

        // Fallback for chest wording we have not seen yet, kept narrow so an
        // unrelated red line cannot blacklist a perfectly good block. Any red
        // line naming a chest anywhere on the server -- not necessarily one
        // we tried to break -- can trip this, so every match is logged, not
        // just the first of its kind, to make that distinguishable later.
        if (formatted != null && (formatted.contains("§c") || formatted.contains("§4"))) {
            for (String hint : CHEST_HINTS) {
                if (lower.contains(hint.toLowerCase())) {
                    MWMineBot.log("Red text matched \"" + hint + "\" -> treating current target as blocked: " + plain);
                    fireBlocked(null);
                    return;
                }
            }
        }
    }

    private void fireBlocked(BlockPos ignored) {
        BlockPos pos = digTarget;
        abortDig();
        onMineBlocked(pos);
    }

    // ---- key holding ----

    protected void hold(KeyBinding kb) {
        if (kb == null) return;
        if (held.add(kb)) Input.set(kb, true);
    }

    protected void release(KeyBinding kb) {
        if (kb == null) return;
        if (held.remove(kb)) Input.set(kb, false);
    }

    /**
     * Walks in a world-space direction regardless of where the camera points,
     * by resolving it into the player's own forward/strafe axes. That is what
     * lets the bot keep advancing on a block while still looking at it, and
     * still work while the camera is mid-turn.
     *
     * Forward is (-sin yaw, cos yaw) and +strafe (the left key) is
     * (cos yaw, sin yaw), matching Entity.moveFlying.
     */
    protected void walkToward(double wx, double wz) {
        double len = Math.sqrt(wx * wx + wz * wz);
        if (len < 1.0E-4) {
            releaseMovement();
            return;
        }
        double dx = wx / len;
        double dz = wz / len;

        double yaw = Math.toRadians(Rotator.normalizeYaw(mc.thePlayer.rotationYaw));
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double lx = Math.cos(yaw);
        double lz = Math.sin(yaw);

        double forward = dx * fx + dz * fz;
        double left = dx * lx + dz * lz;

        setKey(mc.gameSettings.keyBindForward, forward > MOVE_AXIS_MIN);
        setKey(mc.gameSettings.keyBindBack, forward < -MOVE_AXIS_MIN);
        setKey(mc.gameSettings.keyBindLeft, left > MOVE_AXIS_MIN);
        setKey(mc.gameSettings.keyBindRight, left < -MOVE_AXIS_MIN);
    }

    protected void releaseMovement() {
        release(mc.gameSettings.keyBindForward);
        release(mc.gameSettings.keyBindBack);
        release(mc.gameSettings.keyBindLeft);
        release(mc.gameSettings.keyBindRight);
    }

    private void setKey(KeyBinding kb, boolean on) {
        if (on) hold(kb); else release(kb);
    }

    protected boolean isMoving() {
        return isHolding(mc.gameSettings.keyBindForward)
                || isHolding(mc.gameSettings.keyBindBack)
                || isHolding(mc.gameSettings.keyBindLeft)
                || isHolding(mc.gameSettings.keyBindRight);
    }

    protected void releaseAll() {
        held.clear();
        Input.releaseAll();
    }

    // ---- randomisation ----

    protected long lookDuration() {
        long base = Math.max(1L, BotConfig.rotateDurationMs);
        if (!BotConfig.randomize) return base;
        double spread = Math.max(0, BotConfig.randomDurationPct) / 100.0;
        double factor = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * spread;
        return Math.max(10L, (long) (base * factor));
    }

    protected long actionPause() {
        if (!BotConfig.randomize) return 0L;
        int max = Math.max(0, BotConfig.randomPauseMs);
        return max == 0 ? 0L : (long) (rng.nextDouble() * max);
    }

    protected Vec3 aimPointFor(BlockPos p) {
        Vec3 found = findAimPoint(p);
        return found != null ? found : center(p);
    }

    /**
     * A point on the block that is genuinely hittable from where the player is
     * standing right now, or null if none is. Random offsets are tried first
     * so the aim is not always identical, then the point of the block closest
     * to the eye, then the centre. Nothing here assumes the centre works: a
     * block can easily be reachable at one corner and not at its middle.
     */
    protected Vec3 findAimPoint(BlockPos p) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
        double r = reach();

        if (BotConfig.randomize) {
            double scale = AIM_JITTER;
            for (int attempt = 0; attempt < 3; attempt++) {
                Vec3 c = new Vec3(
                        p.getX() + 0.5 + jitter(scale),
                        p.getY() + 0.5 + jitter(scale),
                        p.getZ() + 0.5 + jitter(scale));
                if (aimLandsOn(eyes, c, p, r)) return c;
                scale *= 0.6;
            }
        }

        Vec3 near = nearestPointIn(eyes, p);
        if (aimLandsOn(eyes, near, p, r)) return near;

        Vec3 mid = center(p);
        if (aimLandsOn(eyes, mid, p, r)) return mid;
        return null;
    }

    /** Point inside the block closest to the eye, kept off the exact edge. */
    private Vec3 nearestPointIn(Vec3 eyes, BlockPos p) {
        return new Vec3(
                clamp(eyes.xCoord, p.getX() + 0.15, p.getX() + 0.85),
                clamp(eyes.yCoord, p.getY() + 0.15, p.getY() + 0.85),
                clamp(eyes.zCoord, p.getZ() + 0.15, p.getZ() + 0.85));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private boolean aimLandsOn(Vec3 eyes, Vec3 point, BlockPos p, double reach) {
        if (eyes.distanceTo(point) > reach) return false;
        try {
            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, point, false, true, false);
            return mop != null
                    && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && p.equals(mop.getBlockPos());
        } catch (Exception e) {
            return false;
        }
    }

    private double jitter(double scale) {
        return (rng.nextDouble() * 2.0 - 1.0) * scale;
    }

    /** Human-ish delay before a hotbar swap lands. */
    protected long toolSwitchDelay() {
        long base = Math.max(0, BotConfig.toolSwitchMs);
        if (!BotConfig.randomize || base == 0) return base;
        double spread = Math.max(0, BotConfig.toolSwitchPct) / 100.0;
        return Math.max(0L, (long) (base * (1.0 + (rng.nextDouble() * 2.0 - 1.0) * spread)));
    }

    // ---- digging ----

    /** Forgets the target but leaves the attack key down. */
    private void clearDigTarget() {
        digTarget = null;
        digAimPoint = null;
    }

    /**
     * The game breaks a block inside its own tick, before any mod sees the
     * end of that tick. By the time the bots run, a block that just went is
     * already air, and every caller checks "is this still worth digging"
     * before calling tickDig -- so tickDig's completion step almost never ran
     * for a block that actually broke, and revival and chest-appearance
     * detection with it. Recorded here instead, before anything looks at it.
     *
     * Only the revival watch: the pause between blocks and the seam memory
     * that tickDig's own completion also sets are left as they have been in
     * practice, so the way the bot moves does not change.
     */
    private void noteBreakSinceLastTick() {
        if (digTarget == null) return;
        if (mc.theWorld.getBlockState(digTarget).getBlock() != Blocks.air) return;
        watchForRevival(digTarget, digTargetBlock);
        clearDigTarget();
    }

    protected void abortDig() {
        Input.setAttack(false);
        clearDigTarget();
    }

    /** Lets the caller drop the mouse button when it has nothing to mine. */
    protected void releaseAttack() {
        Input.setAttack(false);
    }

    /** "purple_wool 201,72,284 (3.4m)" for the logs. */
    protected String describe(BlockPos p) {
        String name;
        try {
            name = mc.theWorld.getBlockState(p).getBlock().getLocalizedName();
        } catch (Exception e) {
            name = "?";
        }
        double d = mc.thePlayer.getPositionEyes(1.0f).distanceTo(center(p));
        return name + " " + p.getX() + "," + p.getY() + "," + p.getZ()
                + " (" + String.format("%.1f", d) + "m)";
    }

    protected void debug(String msg) {
        if (BotConfig.debugLog) MWMineBot.info("\u00a78[dbg] " + msg);
    }

    /**
     * Drives one block to destruction. Call every tick with the same position
     * until it stops returning WORKING.
     *
     * The attack key goes down on the first tick and stays down across blocks:
     * letting go calls resetBlockRemoving() and throws away the progress, and
     * vanilla's continuous path (onPlayerDamageBlock) skips the 5-tick
     * blockHitDelay that a fresh click pays.
     */
    protected int tickDig(BlockPos pos) {
        if (pos == null) return FAILED;
        long now = System.currentTimeMillis();

        // Already gone: done. Checked before the aim code, which would
        // otherwise try to aim at the air and report it blocked or failed.
        if (mc.theWorld.getBlockState(pos).getBlock() == Blocks.air) {
            if (pos.equals(digTarget)) {
                lastMinedPos = pos;
                lastMinedBlock = digTargetBlock;
                watchForRevival(pos, digTargetBlock);
                clearDigTarget();
                nextActionAt = now + actionPause();
            }
            return DONE;
        }

        if (!pos.equals(digTarget)) {
            if (now < nextActionAt) return WORKING;

            digTarget = pos;
            digTargetBlock = mc.theWorld.getBlockState(pos).getBlock();
            digStart = now;
            aimTriedSince = now;
            aimSettledAt = 0L;
            sawOnTarget = false;
            obstruction = null;
            failedOnUnbreakable = false;
            failReason = "failed";
            lastOnTargetAt = now;
            // Swap tools before the first swing: changing slot mid-dig throws
            // away all accumulated break progress. The swap itself is delayed a
            // little rather than being instantaneous.
            scheduleToolSwap(mc.theWorld.getBlockState(pos).getBlock(), now);
            digAimPoint = aimPointFor(pos);
            aimFromEye = mc.thePlayer.getPositionEyes(1.0f);
            rotator.beginLookAt(digAimPoint, lookDuration());
        }

        // Walking changes what part of the block can be hit, so the aim point
        // is recomputed from where we are now instead of where we started.
        Vec3 eyeNow = mc.thePlayer.getPositionEyes(1.0f);
        boolean movedFar = aimFromEye == null || eyeNow.distanceTo(aimFromEye) > AIM_REFRESH_DIST;
        // The camera follows the point continuously, but the point itself can
        // stop being a valid one: moving puts a different block in the way, or
        // pushes it past reach. Re-pick as soon as it stops landing, not only
        // after a fixed amount of travel.
        boolean stale = digAimPoint == null || !aimLandsOn(eyeNow, digAimPoint, pos, reach());

        if (movedFar || stale) {
            Vec3 fresh = findAimPoint(pos);
            if (fresh != null) {
                digAimPoint = fresh;
                rotator.beginLookAt(digAimPoint, lookDuration());
            } else {
                // No part of the block can be hit from here. Waiting for the
                // camera to settle before noticing that wasted seconds: the
                // raytrace already says what is in the way, so hand that over
                // immediately.
                BlockPos blocker = firstBlockerToward(pos);
                if (blocker != null && isUnbreakable(blocker) && withinReachDistance(blocker)) {
                    failedOnUnbreakable = true;
                    debug("blocked by " + describe(blocker) + " -> " + describe(pos));
                    abortDig();
                    return FAILED;
                }
                if (blocker != null && isDiggable(blocker) && withinReachDistance(blocker)) {
                    obstruction = blocker;
                    debug("occluded by " + describe(blocker) + " -> " + describe(pos));
                    return BLOCKED;
                }
            }
            aimFromEye = eyeNow;
        }

        if (pendingSlot >= 0) {
            if (now < toolSwitchAt) {
                Input.setAttack(false);
                return WORKING;
            }
            if (pendingSlot < 9) selectSlot(pendingSlot);
            pendingSlot = -1;
        }

        if (isUnbreakable(pos)) {
            failedOnUnbreakable = true;
            abortDig();
            return FAILED;
        }
        if (now - digStart > digTimeoutMs(pos)) {
            failReason = "took longer than the block should (" + (now - digStart) / 1000 + "s)";
            abortDig();
            return FAILED;
        }

        MovingObjectPosition mop = mc.objectMouseOver;
        boolean isBlock = mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos() != null;
        boolean onTarget = isBlock && pos.equals(mop.getBlockPos());

        watchCollateral(pos);

        if (onTarget) {
            sawOnTarget = true;
            lastOnTargetAt = now;
            obstruction = null;
            Input.setAttack(true);
        } else if (sawOnTarget && now - lastOnTargetAt < CLICK_GRACE_MS) {
            // Momentary drift off the block: hold rather than let go, so one
            // block is one continuous press.
            Input.setAttack(true);
        } else {
            // strictClick releases during camera turns. Holding through a turn
            // makes the crosshair sweep other blocks and the game breaks those
            // too -- nothing is gained either way, because break progress is
            // discarded the moment the crosshair changes block.
            Input.setAttack(!BotConfig.strictClick);

            // Settled, or long enough that waiting for "settled" is the thing
            // holding us up. The tracking rotator keeps re-aiming while the
            // player moves, so it can stay unsettled indefinitely, and gating
            // purely on it meant the obstruction below was never noticed.
            boolean canJudge = rotator.isSettled() || (now - aimTriedSince) > AIM_JUDGE_MS;

            if (canJudge) {
                if (aimSettledAt == 0L) aimSettledAt = now;

                // Settled, and the crosshair is on something else that can
                // simply be removed. This is the leaves-in-front-of-a-log case.
                if (isBlock) {
                    BlockPos hit = mop.getBlockPos();
                    // Something between us and the target that cannot be
                    // removed: no point waiting out the aim timer, this target
                    // is simply not reachable from here.
                    if (!hit.equals(pos) && isUnbreakable(hit) && withinReachDistance(hit)) {
                        failedOnUnbreakable = true;
                        debug("blocked by " + describe(hit) + " -> " + describe(pos));
                        abortDig();
                        return FAILED;
                    }
                    if (!hit.equals(pos) && isDiggable(hit) && withinReach(hit)) {
                        obstruction = hit;
                        debug("crosshair on " + describe(hit) + " instead of " + describe(pos)
                                + " reach=" + String.format("%.2f", reach()));
                        return BLOCKED;
                    }
                }
                if (needsReaim()) rotator.beginLookAt(digAimPoint, lookDuration());
            }
        }

        // Checked outside the rotator branch: a constantly re-aiming camera
        // used to starve this and let the full dig timeout run instead.
        if (!sawOnTarget && aimSettledAt != 0L && now - aimSettledAt > AIM_FAIL_MS) {
            failReason = "crosshair never landed on it";
            abortDig();
            return FAILED;
        }
        // Was on it, then lost it and never got back. Without this the stall
        // ran on to the full dig timeout.
        if (sawOnTarget && now - lastOnTargetAt > OFF_TARGET_FAIL_MS) {
            failReason = "crosshair slid off it and never came back";
            abortDig();
            return FAILED;
        }
        return WORKING;
    }

    /** Taps the hotbar key rather than writing the slot straight into memory. */
    private void selectSlot(int slot) {
        try {
            if (Input.tap(mc.gameSettings.keyBindsHotbar[slot])) return;
        } catch (Exception ignored) {}
        mc.thePlayer.inventory.currentItem = slot;
    }

    private void scheduleToolSwap(Block block, long now) {
        pendingSlot = -1;
        int slot = ToolSelector.findBestSlot(mc.thePlayer, block);
        if (slot >= 0 && slot != mc.thePlayer.inventory.currentItem) {
            pendingSlot = slot;
            toolSwitchAt = now + toolSwitchDelay();
        }
    }

    /** Block the crosshair landed on instead of the target, if any. */
    /** Whether the last FAILED was caused by something that cannot be removed. */
    protected boolean failedOnUnbreakable() {
        return failedOnUnbreakable;
    }

    /** Why the last FAILED happened, for the log. */
    protected String failReason() {
        return failReason;
    }

    /**
     * Button down with the crosshair on the block right now: a dig that is
     * going somewhere, however long the block takes.
     */
    protected boolean isHittingTarget() {
        return digTarget != null && sawOnTarget
                && System.currentTimeMillis() - lastOnTargetAt < CLICK_GRACE_MS;
    }

    /**
     * How long a dig may run before it counts as stuck: the game's own
     * figure for this block with what is in hand, plus the usual margin for
     * aiming, the tool swap and lag. A flat limit cut off anything slow --
     * a chest by hand takes almost four seconds, stone by hand seven and a
     * half.
     */
    private long digTimeoutMs(BlockPos pos) {
        long expected = 0L;
        try {
            float perTick = mc.theWorld.getBlockState(pos).getBlock()
                    .getPlayerRelativeBlockHardness(mc.thePlayer, mc.theWorld, pos);
            if (perTick > 0f) expected = (long) Math.ceil(1.0f / perTick) * 50L;
        } catch (Exception ignored) { }
        return DIG_TIMEOUT_MS + Math.min(expected, MAX_EXTRA_DIG_MS);
    }

    protected BlockPos getObstruction() {
        return obstruction;
    }

    /** Block currently being mined, for the overlay. */
    public BlockPos getDigTarget() {
        return digTarget;
    }

    /**
     * Reports any block that turned to air under the crosshair while it was
     * not the block we were aiming at. If the bot ever removes something it
     * should not have been able to, this is what shows it.
     */
    private void watchCollateral(BlockPos intended) {
        if (lastCrosshair != null && lastCrosshairBlock != null
                && !lastCrosshair.equals(intended)
                && lastCrosshairBlock != Blocks.air
                && blockAt(lastCrosshair) == Blocks.air) {
            MWMineBot.info("§cCollateral break: " + lastCrosshairBlock.getLocalizedName()
                    + " " + lastCrosshair.getX() + "," + lastCrosshair.getY() + "," + lastCrosshair.getZ()
                    + " (aiming at: " + describe(intended) + ")");
        }

        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos() != null) {
            lastCrosshair = mop.getBlockPos();
            lastCrosshairBlock = blockAt(lastCrosshair);
        } else {
            lastCrosshair = null;
            lastCrosshairBlock = null;
        }
    }

    /** First block the eye-to-centre ray meets, when it is not the target. */
    protected BlockPos firstBlockerToward(BlockPos pos) {
        try {
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, center(pos), false, true, false);
            if (mop == null) return null;
            if (mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
            BlockPos hit = mop.getBlockPos();
            return (hit == null || hit.equals(pos)) ? null : hit;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean needsReaim() {
        if (digAimPoint == null) return false;
        float[] need = Rotator.getAnglesToTarget(mc.thePlayer.getPositionEyes(1.0f), digAimPoint);
        float dYaw = Math.abs(Rotator.angleDifference(Rotator.normalizeYaw(mc.thePlayer.rotationYaw), need[0]));
        float dPitch = Math.abs(mc.thePlayer.rotationPitch - need[1]);
        return dYaw > AIM_TOLERANCE_DEG || dPitch > AIM_TOLERANCE_DEG;
    }

    // ---- reach / aim cost ----

    protected boolean isCreative() {
        try {
            return mc.playerController.isInCreativeMode();
        } catch (Exception e) {
            return false;
        }
    }

    /** Whatever the game itself allows: 4.5 in survival, 5.0 in creative. */
    protected double reach() {
        try {
            return mc.playerController.getBlockReachDistance();
        } catch (Exception e) {
            return 4.5;
        }
    }

    /**
     * Eyes to the nearest point of the block, not to its centre. A block whose
     * centre sits at 4.9 can still have a face at 4.4, and the game measures
     * the face.
     */
    protected double distanceToBlock(BlockPos p) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        AxisAlignedBB bb = new AxisAlignedBB(p.getX(), p.getY(), p.getZ(),
                p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0);
        double dx = Math.max(bb.minX - eye.xCoord, Math.max(0.0, eye.xCoord - bb.maxX));
        double dy = Math.max(bb.minY - eye.yCoord, Math.max(0.0, eye.yCoord - bb.maxY));
        double dz = Math.max(bb.minZ - eye.zCoord, Math.max(0.0, eye.zCoord - bb.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Reachable in practice, not just on paper. The server measures to the
     * nearest face, but getMouseOver() traces along the look vector for only
     * reach() blocks and we aim at the centre, so the centre has to be inside
     * that too or the crosshair never lands on the block.
     */
    /**
     * Close enough to hit, ignoring what is in front. Used when picking what
     * to mine: a log behind a leaf is worth aiming at, because the crosshair
     * then lands on the leaf and that becomes the next thing to remove.
     */
    protected boolean withinReachDistance(BlockPos p) {
        double r = reach();
        if (distanceToBlock(p) > r) return false;
        return mc.thePlayer.getPositionEyes(1.0f).distanceTo(center(p)) <= r;
    }

    protected boolean withinReach(BlockPos p) {
        double r = reach();
        if (distanceToBlock(p) > r) return false;

        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
        // Two rays only, so this stays cheap enough for the scan: the point of
        // the block nearest the eye, then its centre.
        if (aimLandsOn(eyes, nearestPointIn(eyes, p), p, r)) return true;
        return aimLandsOn(eyes, center(p), p, r);
    }

    /** Weight on distance when ordering blocks to dig. */
    private static final double W_DIST = 3.0;

    /** Weight on camera travel. Only a tie-breaker between equally near ones. */
    private static final double W_AIM = 0.04;

    /** How many of the best get a line-of-sight check. */
    private static final int LOS_CHECKS = 8;

    /** Pull toward whatever touches the block just mined. */
    private static final double ADJACENT_BONUS = 4.0;

    /** Smaller pull toward more of the same material. */
    private static final double SAME_TYPE_BONUS = 2.0;

    /**
     * Cost of taking this block next. Distance dominates, so the near face of
     * a wall goes before the block buried behind it; camera travel only
     * separates blocks that are about equally close.
     */
    protected double digPriority(BlockPos p) {
        double score = mc.thePlayer.getPositionEyes(1.0f).distanceTo(center(p)) * W_DIST
                + aimCost(p) * W_AIM;

        // Finish the seam that is already open before starting another one.
        // Without this the bot takes whatever happens to be nearest and leaves
        // half-eaten trees and pockets of stone behind it.
        if (lastMinedPos != null) {
            if (center(p).distanceTo(center(lastMinedPos)) <= 1.9) score -= ADJACENT_BONUS;
            if (lastMinedBlock != null && blockAt(p) == lastMinedBlock) score -= SAME_TYPE_BONUS;
        }
        return score;
    }

    /**
     * Of a set of blocks that all need removing, the one to start on: the
     * cheapest that is actually exposed. Digging through something to reach a
     * buried one is a fallback, never the opening move.
     */
    protected BlockPos bestToDig(List<BlockPos> options) {
        if (options == null || options.isEmpty()) return null;

        List<BlockPos> ranked = new ArrayList<BlockPos>(options);
        Collections.sort(ranked, new Comparator<BlockPos>() {
            public int compare(BlockPos a, BlockPos b) {
                return Double.compare(digPriority(a), digPriority(b));
            }
        });

        int checks = Math.min(ranked.size(), LOS_CHECKS);
        for (int i = 0; i < checks; i++) {
            if (withinReach(ranked.get(i))) return ranked.get(i);
        }
        return ranked.get(0);
    }

    /** Degrees of yaw+pitch travel needed to look at this block from here. */
    protected double aimCost(BlockPos p) {
        float[] need = Rotator.getAnglesToTarget(mc.thePlayer.getPositionEyes(1.0f), center(p));
        double dYaw = Math.abs(Rotator.angleDifference(Rotator.normalizeYaw(mc.thePlayer.rotationYaw), need[0]));
        double dPitch = Math.abs(mc.thePlayer.rotationPitch - need[1]);
        return dYaw + dPitch;
    }

    // ---- walking ----

    protected void beginMove() {
        moveStart = System.currentTimeMillis();
    }

    /**
     * Faces {@code dir} and walks into the middle of {@code want}.
     *
     * Block coordinates alone are not enough: the player is 0.6 wide, so their
     * block position flips the moment their centre crosses the boundary while
     * most of the hitbox -- and all of the support -- is still on the previous
     * block. Stopping there leaves them standing on the old floor.
     */
    protected int tickMove(EnumFacing dir, BlockPos want) {
        BlockPos here = playerPos();
        double dx = (want.getX() + 0.5) - mc.thePlayer.posX;
        double dz = (want.getZ() + 0.5) - mc.thePlayer.posZ;
        boolean centered = (dx * dx + dz * dz) <= CENTER_TOLERANCE * CENTER_TOLERANCE;

        if (centered && here.getX() == want.getX() && here.getZ() == want.getZ()) {
            release(mc.gameSettings.keyBindForward);
            return DONE;
        }
        if (System.currentTimeMillis() - moveStart > MOVE_TIMEOUT_MS) {
            release(mc.gameSettings.keyBindForward);
            return FAILED;
        }

        float wantYaw = yawOf(dir);
        if (Math.abs(Rotator.angleDifference(Rotator.normalizeYaw(mc.thePlayer.rotationYaw), wantYaw)) > AIM_TOLERANCE_DEG) {
            if (!rotator.isActive()) {
                rotator.begin(wantYaw, 0f, lookDuration());
            }
            release(mc.gameSettings.keyBindForward);
            return WORKING;
        }

        hold(mc.gameSettings.keyBindForward);
        return WORKING;
    }

    /**
     * Walks forward until the player has actually dropped below {@code fromY}.
     * A stair step is only finished once the fall has happened; finishing on
     * the block coordinate instead is what let the staircase creep two blocks
     * forward per block down.
     */
    protected int tickMoveDown(EnumFacing dir, int fromY) {
        if (playerPos().getY() < fromY) {
            release(mc.gameSettings.keyBindForward);
            return DONE;
        }
        if (System.currentTimeMillis() - moveStart > MOVE_TIMEOUT_MS) {
            release(mc.gameSettings.keyBindForward);
            return FAILED;
        }

        float wantYaw = yawOf(dir);
        if (Math.abs(Rotator.angleDifference(Rotator.normalizeYaw(mc.thePlayer.rotationYaw), wantYaw)) > AIM_TOLERANCE_DEG) {
            if (!rotator.isActive()) rotator.begin(wantYaw, 0f, lookDuration());
            release(mc.gameSettings.keyBindForward);
            return WORKING;
        }

        hold(mc.gameSettings.keyBindForward);
        return WORKING;
    }

    // ---- stuck detection ----

    private void updateStuck() {
        double dx = mc.thePlayer.posX - lastX;
        double dz = mc.thePlayer.posZ - lastZ;
        lastX = mc.thePlayer.posX;
        lastZ = mc.thePlayer.posZ;

        if (isMoving() && Math.sqrt(dx * dx + dz * dz) < STUCK_SPEED) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
    }

    /** Holding W but going nowhere. Far quicker than a wall-clock timeout. */
    protected boolean isStuck() {
        return stuckTicks >= STUCK_TICKS;
    }

    protected void clearStuck() {
        stuckTicks = 0;
    }

    protected boolean isHolding(KeyBinding kb) {
        return kb != null && held.contains(kb);
    }

    // ---- world helpers ----

    /**
     * Hotbar slot to build with, or -1. Anything on the mine list is refused:
     * scaffolding made of stone while stone is a target means the bot pillars
     * up and then mines its own pillar out from under itself.
     */
    protected int findBlockSlot() {
        try {
            for (int i = 0; i < 9; i++) {
                ItemStack st = mc.thePlayer.inventory.mainInventory[i];
                if (st == null || st.stackSize <= 0) continue;
                if (!(st.getItem() instanceof ItemBlock)) continue;
                Block b = ((ItemBlock) st.getItem()).getBlock();
                if (MiningBot.isTargetBlock(b)) continue;
                if (!isSafeToPlace(b)) continue;
                return i;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    protected void selectHotbar(int slot) {
        try {
            if (Input.tap(mc.gameSettings.keyBindsHotbar[slot])) return;
        } catch (Exception ignored) {}
        mc.thePlayer.inventory.currentItem = slot;
    }

    /** Somewhere the player fits and has a floor. */
    protected boolean canStandAt(BlockPos p) {
        Block a = blockAt(p);
        Block b = blockAt(p.up());
        if (solidFor(a) || solidFor(b)) return false;
        return solidFor(blockAt(p.down()));
    }

    private boolean solidFor(Block b) {
        if (b == Blocks.air) return false;
        try {
            return b.getMaterial().blocksMovement();
        } catch (Exception e) {
            return true;
        }
    }

    protected BlockPos playerPos() {
        return new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ));
    }

    protected static Vec3 center(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    protected Block blockAt(BlockPos p) {
        return mc.theWorld.getBlockState(p).getBlock();
    }

    protected boolean isBedrock(BlockPos p) {
        return blockAt(p) == Blocks.bedrock;
    }

    /**
     * Anything in the way may be removed regardless of the target toggles --
     * those only decide what is worth going out of the way for. Bedrock and
     * obsidian are refused, and liquids are not dug because they are walked
     * or swum through instead.
     */
    /**
     * Blocks no amount of effort will remove. Barrier is the awkward one: it
     * is invisible, so aiming past it looks perfectly reasonable right up
     * until nothing ever breaks.
     */
    protected boolean isUnbreakable(BlockPos p) {
        // A block that keeps coming back is unbreakable in every way that
        // matters here, and saying so once means every caller -- isDiggable,
        // the obstruction checks, the tunnel turn logic -- gets it for free.
        if (isCursed(p)) return true;
        Block b = blockAt(p);
        return b == Blocks.bedrock
                || b == Blocks.obsidian
                || b == Blocks.barrier
                || b == Blocks.command_block
                || b == Blocks.ender_chest;
    }

    protected boolean isDiggable(BlockPos p) {
        Block b = blockAt(p);
        if (b == Blocks.air) return false;
        if (isUnbreakable(p)) return false;
        if (b == Blocks.water || b == Blocks.flowing_water
                || b == Blocks.lava || b == Blocks.flowing_lava) return false;
        try {
            return b.getMaterial().blocksMovement();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Blocks that must never be used as a step. Some would be destroyed or
     * opened by the click, some are not a full cube to stand on, and TNT
     * speaks for itself.
     */
    protected boolean isSafeToPlace(Block b) {
        if (b == Blocks.tnt
                || b == Blocks.ladder
                || b == Blocks.chest
                || b == Blocks.trapped_chest
                || b == Blocks.ender_chest
                || b == Blocks.crafting_table
                || b == Blocks.furnace
                || b == Blocks.lit_furnace) {
            return false;
        }
        try {
            return b.isFullCube();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- blocks that come back after being dug ----

    private final Map<BlockPos, Block> revivalWatch = new HashMap<BlockPos, Block>();
    private final Map<BlockPos, Long> revivalUntil = new HashMap<BlockPos, Long>();

    /**
     * Revivals per position. Deliberately outside the watch entry: the watch
     * expires after a few seconds, and a wall block dug again a minute later
     * has to carry the earlier count with it or the tally restarts at zero
     * every time and the same block is dug forever.
     */
    private final Map<BlockPos, Integer> revivalCount = new HashMap<BlockPos, Integer>();
    private final Set<BlockPos> cursed = new HashSet<BlockPos>();

    /** How long after a dig a block reappearing still counts as a revival. */
    private static final long REVIVAL_WATCH_MS = 4000L;

    /** Lag can put a block back once. Twice means it is never going away. */
    private static final int REVIVAL_GIVE_UP = 2;

    protected void resetRevivals() {
        revivalWatch.clear();
        revivalUntil.clear();
        revivalCount.clear();
        cursed.clear();
    }

    /**
     * Called from tickDig the moment a block turns to air, with the block that
     * was standing there. Doing it here rather than at each call site is what
     * makes it reliable: callers used to read the block back out of the world
     * after the dig finished, by which point it was already air and nothing
     * was ever watched.
     */
    private void watchForRevival(BlockPos p, Block was) {
        if (p == null || was == null || was == Blocks.air || cursed.contains(p)) return;
        revivalWatch.put(p, was);
        revivalUntil.put(p, System.currentTimeMillis() + REVIVAL_WATCH_MS);
    }

    /**
     * A block that reappears after being dug is a block the server never let us
     * break -- the Mega Walls map boundary does exactly this. Without this the
     * same two blocks can be dug forever.
     */
    protected void checkRevivals() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Block>> it = revivalWatch.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Block> e = it.next();
            BlockPos p = e.getKey();
            Long until = revivalUntil.get(p);
            if (until == null || now > until) {
                it.remove();
                revivalUntil.remove(p);
                continue;
            }

            Block back = blockAt(p);
            if (back == Blocks.air) continue;

            // A chest appearing where stone was is the break having worked:
            // the server swapped in loot, it did not put the wall back. Any
            // count this spot picked up from earlier lag is wrong too.
            if (back == Blocks.chest || back == Blocks.trapped_chest) {
                it.remove();
                revivalUntil.remove(p);
                revivalCount.remove(p);
                onChestAppeared(p);
                continue;
            }

            // Something else entirely: gravel dropped in, or a player built
            // there. Not the server refusing us, so stop watching and say
            // nothing.
            if (back != e.getValue()) {
                it.remove();
                revivalUntil.remove(p);
                continue;
            }

            it.remove();
            revivalUntil.remove(p);
            int n = revivalCount.containsKey(p) ? revivalCount.get(p) + 1 : 1;
            revivalCount.put(p, n);
            debug("came back (" + n + "x): " + describe(p));

            // Next to a spot already proved unbreakable, one return is
            // enough: these things come in walls, not one block at a time,
            // and giving every block of a wall its own two attempts is how
            // the bot ends up chewing along the whole thing.
            int need = touchesCursed(p) ? 1 : REVIVAL_GIVE_UP;
            if (n >= need) {
                cursed.add(p);
                MWMineBot.log("Cannot break " + p.getX() + "," + p.getY() + "," + p.getZ()
                        + " — it keeps coming back, going around");
                onCursed(p);
            }
        }
    }

    /**
     * A position has proved unbreakable. Subclasses drop whatever they were
     * doing with it here; the block itself is already excluded from every
     * isDiggable/isUnbreakable test from this point on.
     */
    protected void onCursed(BlockPos p) {
    }

    /**
     * A chest turned up exactly where we had just dug. This hangs off the
     * same watch as revival detection, so it covers every dig that goes
     * through tickDig -- seams, walls in the way, digging down -- rather than
     * only the one path that used to look for it. Ignored unless overridden.
     */
    protected void onChestAppeared(BlockPos p) {
    }

    /** Whether any of the 26 blocks around this one is already cursed. */
    private boolean touchesCursed(BlockPos p) {
        if (cursed.isEmpty()) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (cursed.contains(p.add(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    /** True once a block has proved it cannot actually be broken. */
    protected boolean isCursed(BlockPos p) {
        return cursed.contains(p);
    }

    /** True when nothing solid sits between the eyes and this block. */
    protected boolean hasLineOfSight(BlockPos pos) {
        try {
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, center(pos), false, true, false);
            return mop != null
                    && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && pos.equals(mop.getBlockPos());
        } catch (Exception e) {
            return false;
        }
    }

    // ---- facing helpers (written out rather than relying on EnumFacing helpers) ----

    protected static final EnumFacing[] CARDINAL = {
            EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.NORTH, EnumFacing.EAST
    };

    protected static float yawOf(EnumFacing f) {
        switch (f) {
            case WEST: return 90f;
            case NORTH: return 180f;
            case EAST: return 270f;
            case SOUTH:
            default: return 0f;
        }
    }

    protected static int indexOf(EnumFacing f) {
        for (int i = 0; i < CARDINAL.length; i++) {
            if (CARDINAL[i] == f) return i;
        }
        return 0;
    }

    protected static EnumFacing turnRight(EnumFacing f) {
        return CARDINAL[(indexOf(f) + 1) % 4];
    }

    protected static EnumFacing turnLeft(EnumFacing f) {
        return CARDINAL[(indexOf(f) + 3) % 4];
    }

    protected EnumFacing facingFromYaw() {
        int i = MathHelper.floor_double(Rotator.normalizeYaw(mc.thePlayer.rotationYaw) / 90.0 + 0.5) & 3;
        return CARDINAL[i];
    }
}
