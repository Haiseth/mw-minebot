package com.haiselita.mwminebot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockStone;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks one destination, commits to it, walks at it, and removes anything in
 * the way whatever it happens to be.
 *
 * The previous version re-chose a destination twice a second and split walking
 * from digging into two disjoint modes, which is why it paced between two
 * trees and never touched the leaves in front of either. Here there is a
 * single committed target and one rule for movement: face it, walk, and dig
 * whatever the next step runs into.
 */
public class MiningBot extends BotBase {

    private static final long BLACKLIST_MS = 30000L;
    private static final long RETRY_MS = 2500L;
    /** How long a chest we uncovered stays worth going back for. */
    private static final long OWN_CHEST_KEEP_MS = 60000L;
    private static final long UNDIG_QUIET_MS = 30000L;

    /** No closing on the destination for this long: try digging, then give up. */
    private static final long PROGRESS_TIMEOUT_MS = 1500L;

    /** Hard cap on one destination, however well it seems to be going. */
    private static final long COMMIT_TIMEOUT_MS = 30000L;

    /** Distance that counts as real progress toward the destination. */
    private static final double PROGRESS_EPS = 0.05;

    /** Standing over the destination: only digging down gets closer. */
    private static final double OVERHEAD_DIST = 1.5;

    private static final int CLUSTER_MAX_DOWN = 12;

    /** Clusters that get a standability check, nearest first. */
    private static final int REACHABLE_CHECKS = 40;

    /**
     * Keep walking into the block being mined until this close. Pressing
     * against it costs nothing, and being right up against it means the next
     * block is already in reach the moment this one goes.
     */
    private static final double MINE_APPROACH_DIST = 0.9;

    /** How far around to look for a higher place to stand. */
    /** How far the way-up search may wander to find a route. */
    /** One wider sweep is tried before deciding the area is finished. */
    private static final int WIDE_SCAN_RADIUS = 36;
    private static final int WIDE_SCAN_VERT = 16;

    private static final int CLIMB_RADIUS = 8;
    private static final int CLIMB_VERT = 5;
    private static final int CLIMB_NODES = 1200;

    /** Longest to spend getting onto one step of the route. */
    private static final long CLIMB_HOLD_MS = 1500L;

    /** Blocks placed under our own feet before calling it hopeless. */
    private static final int PILLAR_MAX = 24;
    private static final long PILLAR_GAP_MS = 50L;

    /**
     * Clicks allowed per jump. The window where the block can go down is only
     * a few ticks wide, so the click is retried across it rather than staked
     * on one attempt.
     */
    private static final int PILLAR_TAPS = 5;

    /** How centred in the block we must be before jumping to build. */
    private static final double PILLAR_CENTER_TOL = 0.15;

    /** Random offset from dead centre, kept inside the hitbox margin. */
    private static final double PILLAR_JITTER = 0.12;

    /** Consecutive jumps that gained nothing before giving up on building. */
    private static final int PILLAR_FAIL_MAX = 4;

    /** Improvement a climb spot must give, so it cannot hop back and forth. */
    private static final double CLIMB_MIN_GAIN = 0.75;

    private BlockPos target = null;
    private boolean targetIsObstruction = false;
    private BlockPos pendingTarget = null;
    private BlockPos lastObstruction = null;

    /** The one thing being pursued. Not reconsidered until it resolves. */
    private BlockPos destination = null;
    private long destPickedAt = 0L;
    private long lastProgressAt = 0L;
    private double bestHoriz = Double.MAX_VALUE;

    /**
     * Whether the previous tick was spent approaching. Mining a block does not
     * close the distance to the destination, so the no-progress clock has to
     * be restarted when approaching resumes -- otherwise every pause to dig
     * made the very next approach tick declare failure and pick a new tree.
     */
    private boolean approaching = false;

    private List<BlockPos> climbPath = null;
    private int climbIndex = 0;
    private long climbUntil = 0L;
    private int pillarCount = 0;
    private long lastPillarAt = 0L;
    private int pillarFails = 0;
    private boolean pillaring = false;
    private double jumpFloorY = 0.0;
    private int placedThisJump = 0;
    private boolean pillarLocked = false;
    private double pillarJitterX = 0.0;
    private double pillarJitterZ = 0.0;
    private boolean airborne = false;
    private double jumpStartY = 0.0;
    private double jumpPeakY = 0.0;

    /** Nothing happened for this long: say what the state is and start over. */
    private static final long WATCHDOG_MS = 3000L;

    private long lastActionAt = 0L;
    private double lastX, lastZ, lastY;
    private BlockPos watchTarget = null;
    private BlockPos watchDest = null;

    private boolean pickedFirst = false;
    private int emptyScans = 0;

    /**
     * Chests that turned up where we had just dug, with when to stop caring.
     * The old single slot was overwritten by the very next block mined and
     * only looked at in the one tick nothing else was going on, so a chest
     * appearing mid-seam was never gone back for.
     */
    private final Map<BlockPos, Long> ownChests = new LinkedHashMap<BlockPos, Long>();

    private final Map<BlockPos, Long> blacklist = new HashMap<BlockPos, Long>();
    private final List<Long> undiggableAt = new ArrayList<Long>();
    private long undiggableQuietUntil = 0L;
    private boolean wideScan = false;
    private boolean warnedNoBlocks = false;

    @Override
    public String displayName() {
        return "Mining bot";
    }

    public BlockPos getDestination() {
        return destination;
    }

    @Override
    protected void onStart() {
        target = null;
        targetIsObstruction = false;
        pendingTarget = null;
        lastObstruction = null;
        dropDestination();
        pickedFirst = false;
        emptyScans = 0;
        lastActionAt = 0L;
        watchTarget = null;
        watchDest = null;
        ownChests.clear();
        blacklist.clear();
        resetRevivals();
        wideScan = false;
        warnedNoBlocks = false;

        // Said once at the start rather than at the moment it matters, so it is
        // still possible to go and fetch some. Never a reason to refuse to run.
        if (BotConfig.pillarUp && findBlockSlot() < 0) {
            MWMineBot.log("§eNo blocks in the hotbar — stacking up will not work");
            alertSound();
        }
    }

    private void alertSound() {
        try {
            mc.thePlayer.playSound("note.pling", 1.0F, 0.5F);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onMineBlocked(BlockPos pos) {
        BlockPos bad = (pos != null) ? pos : target;
        if (bad != null) {
            giveUpOn(bad, "protected chest");
            ownChests.remove(bad);
        }
        clearTarget();
    }

    @Override
    protected void onTickRunning() {
        pillaring = false;
        tickBody();
        // Right click is only ever held while building.
        if (!pillaring) release(mc.gameSettings.keyBindUseItem);
    }

    private void tickBody() {
        long now = System.currentTimeMillis();
        expireBlacklist(now);
        checkRevivals();
        if (watchdog(now)) return;

        // A build jump has to finish before anything else gets a turn. The
        // reach scan used to grab a target mid-air, which turned the camera
        // away and dropped the jump, so the click never happened at all.
        if (pillarLocked && BotConfig.pillarUp) {
            if (pillarStep(now)) return;
            pillarLocked = false;
        }

        if (target != null) {
            approaching = false;
            mineTarget(now);
            return;
        }

        // A chest out of our own dig beats everything below. In reach it is
        // the next thing mined; if we had already moved on by the time it
        // showed up, it becomes the destination and we walk back for it.
        BlockPos chest = nextOwnChest(now);
        if (chest != null) {
            if (withinReachDistance(chest)) {
                approaching = false;
                stopWalking();
                setTarget(chest, false);
                return;
            }
            if (!chest.equals(destination)) {
                dropDestination();
                destination = chest;
                destPickedAt = now;
                lastProgressAt = now;
                approaching = false;
            }
            approach(now);
            return;
        }

        // Anything in range gets mined now, line of sight or not: if a leaf is
        // in front of it the crosshair lands on the leaf and that becomes the
        // next thing to remove.
        // What we climbed for wins. Otherwise, having gone to the trouble of
        // building up to one tree, anything else that happens to be at this
        // new height gets taken instead and the pillar was wasted.
        if (destination != null && isTarget(destination) && !blacklist.containsKey(destination)
                && withinReachDistance(destination)) {
            pillarLocked = false;
            stopWalking();
            setTarget(destination, false);
            return;
        }

        BlockPos here = findInRange();
        if (here != null) {
            approaching = false;
            stopWalking();
            setTarget(here, false);
            return;
        }

        if (destination == null || !isTarget(destination) || blacklist.containsKey(destination)) {
            dropDestination();
            boolean found = chooseDestination(now);
            if (!found) {
                // Usually the vein is just outside the normal box rather than
                // the area being finished, so sweep once at a wider radius
                // before giving up. Only ever one attempt: it is expensive.
                wideScan = true;
                found = chooseDestination(now);
                wideScan = false;
                if (found) MWMineBot.log("§eNothing close by — widened the search");
            }
            if (found) {
                emptyScans = 0;
            } else {
                stopWalking();
                releaseAttack();
                if (++emptyScans >= 2) alertNothingFound();
            }
            return;
        }

        approach(now);
    }

    // ---- mining ----

    private void mineTarget(long now) {
        // Gone to air means it broke -- the game breaks blocks inside its own
        // tick, before this runs -- so it takes the DONE path below like any
        // other finished block, instead of being dropped as "not a target".
        boolean broke = blockAt(target) == Blocks.air;
        if (!broke) {
            boolean stillValid = targetIsObstruction
                    ? isDiggable(target)
                    : isTarget(target);

            if (!stillValid) {
                // Always logged: dropped without ever having been broken,
                // which is exactly the case worth being able to check later.
                MWMineBot.log("No longer a valid target, dropping"
                        + (isCursed(target) ? " (marked unbreakable)" : "") + ": " + describe(target));
                clearTarget();
                return;
            }
        }

        int r = tickDig(target);
        if (r == DONE) {
            if (target.equals(destination)) dropDestination();
            clearTarget();
            lastObstruction = null;
            // A chest already waiting goes ahead of the block we set aside.
            // The set-aside one stays pending and is picked up after it.
            BlockPos chest = nextOwnChest(now);
            if (chest != null && withinReachDistance(chest)) {
                setTarget(chest, false);
            } else {
                resumePending();
            }
        } else if (r == BLOCKED) {
            BlockPos obs = getObstruction();
            if (obs != null && !blacklist.containsKey(obs)) {
                if (!targetIsObstruction && isTarget(target)) pendingTarget = target;
                if (!obs.equals(lastObstruction)) {
                    lastObstruction = obs;
                    MWMineBot.info("§7Obstruction: " + describe(obs));
                }
                setTarget(obs, true);
            } else {
                parkTarget(target, "blocked, nothing diggable to switch to");
                clearTarget();
            }
        } else if (r == FAILED) {
            if (failedOnUnbreakable()) {
                giveUpOn(target, "blocked by something unbreakable");
            } else {
                // Aim never landed, the block was lost, or the dig ran past
                // what the block should take. Retried shortly (RETRY_MS),
                // not given up on for good.
                parkTarget(target, "dig failed (" + failReason() + "), retrying shortly");
            }
            clearTarget();
        }

        if (target != null) approachWhileMining(target);
        else stopWalking();
    }

    /**
     * Anything at all counts as progress: moving, or changing what is being
     * worked on. If none of that happens for a few seconds the bot is wedged
     * in a state nobody predicted, so it reports what that state was and makes
     * a fresh decision instead of standing there.
     */
    private boolean watchdog(long now) {
        double dx = mc.thePlayer.posX - lastX;
        double dy = mc.thePlayer.posY - lastY;
        double dz = mc.thePlayer.posZ - lastZ;
        lastX = mc.thePlayer.posX;
        lastY = mc.thePlayer.posY;
        lastZ = mc.thePlayer.posZ;

        boolean moved = Math.sqrt(dx * dx + dy * dy + dz * dz) > 0.02;
        // Standing still hitting a block is not being stuck. A chest by hand
        // takes almost four seconds, and this used to call that a stall at
        // three and walk off with it nearly broken. tickDig's own timeout,
        // sized to the block, bounds a dig that really is going nowhere.
        boolean hitting = isHittingTarget();
        boolean switched = (target != null && !target.equals(watchTarget))
                || (destination != null && !destination.equals(watchDest));
        watchTarget = target;
        watchDest = destination;

        if (lastActionAt == 0L || moved || switched || hitting) {
            lastActionAt = now;
            return false;
        }
        if (now - lastActionAt < WATCHDOG_MS) return false;

        // Always logged, not debug-gated: this is the bot giving up on
        // whatever it was doing without ever finishing it, which is exactly
        // what needs to be traceable after the fact.
        MWMineBot.log("Stalled: digging=" + (target == null ? "none" : describe(target))
                + " destination=" + (destination == null ? "none"
                        : destination.getX() + "," + destination.getY() + "," + destination.getZ())
                + " -> starting over");

        if (target != null) {
            blacklist.put(target, now + RETRY_MS);
            abortDig();
            clearTarget();
        }
        if (destination != null) {
            blacklist.put(destination, now + RETRY_MS);
            dropDestination();
        }
        pendingTarget = null;
        lastObstruction = null;
        pickedFirst = false;
        stopWalking();
        releaseAttack();
        lastActionAt = now;
        return true;
    }

    private void approachWhileMining(BlockPos p) {
        release(mc.gameSettings.keyBindJump);
        double dx = (p.getX() + 0.5) - mc.thePlayer.posX;
        double dz = (p.getZ() + 0.5) - mc.thePlayer.posZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < MINE_APPROACH_DIST) {
            releaseMovement();
            return;
        }

        // Standing on a pillar we just built, a block four metres away looks
        // reachable because reach is measured from the eyes -- but there is no
        // floor between here and it. Shuffling closer is only worth doing when
        // there is something to shuffle onto.
        BlockPos ahead = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX + dx / d * 0.6),
                playerPos().getY(),
                MathHelper.floor_double(mc.thePlayer.posZ + dz / d * 0.6));
        if (!isSolid(ahead.down()) && !isSolid(ahead.down().down())) {
            releaseMovement();
            return;
        }

        walkToward(dx, dz);
    }

    private void setTarget(BlockPos p, boolean obstruction) {
        target = p;
        targetIsObstruction = obstruction;
    }

    private void clearTarget() {
        target = null;
        targetIsObstruction = false;
    }

    private void resumePending() {
        if (pendingTarget == null) return;
        BlockPos p = pendingTarget;
        pendingTarget = null;
        if (blacklist.containsKey(p)) return;
        if (!isTarget(p)) return;
        if (!withinReachDistance(p)) return;
        setTarget(p, false);
    }

    private void parkTarget(BlockPos p, String why) {
        blacklist.put(p, System.currentTimeMillis() + RETRY_MS);
        if (p.equals(pendingTarget)) pendingTarget = null;
        // Temporary, not a give-up -- but still a block left un-broken, so
        // it goes in the log the same way.
        MWMineBot.log(why + ": " + describe(p));
    }

    private void giveUpOn(BlockPos p, String why) {
        long now = System.currentTimeMillis();
        blacklist.put(p, now + BLACKLIST_MS);
        pickedFirst = false;
        // Always logged: this is a block abandoned before it broke, which is
        // exactly the case worth being able to check afterwards.
        MWMineBot.log(why + ": " + describe(p));
        noteUndiggable(now);
    }

    // ---- committing to a destination ----

    private void dropDestination() {
        destination = null;
        climbPath = null;
        climbIndex = 0;
        pillarCount = 0;
        pillarLocked = false;
        release(mc.gameSettings.keyBindUseItem);
        pillarFails = 0;
        airborne = false;
        bestHoriz = Double.MAX_VALUE;
    }

    private void abandonDestination(long now, String why) {
        if (destination != null) {
            blacklist.put(destination, now + BLACKLIST_MS);
            MWMineBot.info("§7" + why + ": " + destination.getX() + "," + destination.getY()
                    + "," + destination.getZ() + " dropped");
        }
        dropDestination();
        stopWalking();
        releaseAttack();
    }

    private boolean chooseDestination(long now) {
        List<BlockPos> clusters = findClusters();
        if (clusters.isEmpty()) return false;

        // Rank first, then take the nearest one there is actually somewhere to
        // stand next to. Checking that only on arrival meant walking to every
        // block of a cliff face in turn and excluding each one after the walk.
        final double px = mc.thePlayer.posX;
        final double pz = mc.thePlayer.posZ;
        Collections.sort(clusters, new Comparator<BlockPos>() {
            public int compare(BlockPos a, BlockPos b) {
                return Double.compare(flat(a, px, pz), flat(b, px, pz));
            }
        });

        BlockPos best = null;
        int examined = 0;
        for (BlockPos c : clusters) {
            if (examined++ > REACHABLE_CHECKS) break;
            if (!standableNear(c)) continue;
            best = c;
            break;
        }
        if (best == null) {
            if (BotConfig.debugLog) {
                debug("no standable cluster among " + Math.min(clusters.size(), REACHABLE_CHECKS));
            }
            return false;
        }

        if (BotConfig.debugLog) {
            StringBuilder sb = new StringBuilder("cands ").append(clusters.size()).append(":");
            int n = 0;
            for (BlockPos c : clusters) {
                if (n++ >= 3) break;
                sb.append(" ").append(c.getX()).append(",").append(c.getY()).append(",").append(c.getZ())
                        .append("=").append((int) flat(c, mc.thePlayer.posX, mc.thePlayer.posZ)).append("m");
            }
            debug(sb.toString());
        }

        destination = best;
        destPickedAt = now;
        approaching = false;
        lastProgressAt = now;
        bestHoriz = Double.MAX_VALUE;
        MWMineBot.info("§7Destination: " + best.getX() + "," + best.getY() + "," + best.getZ()
                + " (" + (int) flat(best, mc.thePlayer.posX, mc.thePlayer.posZ) + "m)");
        return true;
    }

    /** Straight at it, removing whatever the next step runs into. */
    private void approach(long now) {
        if (!approaching) {
            approaching = true;
            lastProgressAt = now;
            bestHoriz = Double.MAX_VALUE;
            clearStuck();
        }

        if (now - destPickedAt > COMMIT_TIMEOUT_MS) {
            abandonDestination(now, "timed out");
            return;
        }

        double dx = (destination.getX() + 0.5) - mc.thePlayer.posX;
        double dz = (destination.getZ() + 0.5) - mc.thePlayer.posZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        if (horiz < bestHoriz - PROGRESS_EPS) {
            bestHoriz = horiz;
            lastProgressAt = now;
            clearStuck();
        }

        BlockPos feet = playerPos();

        // Standing over or under it.
        if (horiz < OVERHEAD_DIST) {
            if (destination.getY() < feet.getY()) {
                if (digBelow(now)) return;
                abandonDestination(now, "cannot dig straight down");
                return;
            }
            if (destination.getY() > feet.getY() + 1) {
                if (BotConfig.climbTerrain && climbStep(now)) return;
                if (BotConfig.pillarUp && pillarStep(now)) return;
                abandonDestination(now, "straight overhead and out of reach");
                return;
            }
        }

        // Something above needs going up, not tunnelling toward. Checking this
        // only while standing directly underneath meant the bot spent the
        // whole approach boring horizontally through a hillside instead.
        if (BotConfig.climbTerrain && destination.getY() > feet.getY() + 1 && climbStep(now)) {
            return;
        }

        // Whatever the next step runs into, of any kind.
        boolean stalling = (now - lastProgressAt) > 600L || isStuck();
        BlockPos wall = frontObstacle(dx, dz, horiz, stalling);
        if (wall != null) {
            BlockPos ceiling = blockedCeiling();
            if (ceiling != null && wall.getY() == playerPos().getY()
                    && !isSolid(wall.up()) && !isTarget(wall)) {
                // A step we could hop, except something is over our head.
                stopWalking();
                if (tickDig(ceiling) == DONE) lastProgressAt = now;
                return;
            }

            if (isStepUp(wall)) {
                releaseAttack();
                walkToward(dx, dz);
                faceTowards(dx, dz);
                hold(mc.gameSettings.keyBindJump);
                return;
            }
            stopWalking();
            int r = tickDig(wall);
            if (r == DONE) {
                lastProgressAt = now;
            } else if (r == FAILED) {
                blacklist.put(wall, now + (failedOnUnbreakable() ? BLACKLIST_MS : RETRY_MS));
            } else if (r == BLOCKED) {
                BlockPos obs = getObstruction();
                if (obs != null && !blacklist.containsKey(obs)) {
                    setTarget(obs, true);
                } else {
                    blacklist.put(wall, now + RETRY_MS);
                }
            }
            return;
        }

        if (now - lastProgressAt > PROGRESS_TIMEOUT_MS || isStuck()) {
            abandonDestination(now, "cannot get through");
            return;
        }

        releaseAttack();
        release(mc.gameSettings.keyBindJump);
        walkToward(dx, dz);
        faceTowards(dx, dz);
    }

    /**
     * The first solid thing between here and the next step. Diagonal travel is
     * checked on both axes, otherwise the bot presses into a corner forever
     * with nothing "ahead" of it.
     */
    private BlockPos frontObstacle(double dx, double dz, double horiz, boolean includeSides) {
        if (horiz < 0.0001) return null;
        double nx = dx / horiz;
        double nz = dz / horiz;

        int y = playerPos().getY();
        int px = MathHelper.floor_double(mc.thePlayer.posX);
        int pz = MathHelper.floor_double(mc.thePlayer.posZ);
        int ax = MathHelper.floor_double(mc.thePlayer.posX + nx * 0.9);
        int az = MathHelper.floor_double(mc.thePlayer.posZ + nz * 0.9);

        List<BlockPos> cells = new ArrayList<BlockPos>();
        cells.add(new BlockPos(ax, y, az));
        if (includeSides) {
            cells.add(new BlockPos(ax, y, pz));
            cells.add(new BlockPos(px, y, az));
        }

        for (BlockPos c : cells) {
            if (c.getX() == px && c.getZ() == pz) continue;
            // No blacklist filter here: this is the way through, and a
            // block parked after one failed attempt must not wall us in.
            if (isDiggable(c) && withinReachDistance(c)) return c;
            BlockPos head = c.up();
            if (isDiggable(head) && withinReachDistance(head)) return head;
        }
        return null;
    }

    /** A plain one-block rise that can be hopped rather than mined. */
    private boolean isStepUp(BlockPos wall) {
        BlockPos feet = playerPos();
        if (wall.getY() != feet.getY()) return false;
        if (isTarget(wall)) return false;
        // Leaves over our own head stop the jump. Without this the bot hops
        // into the canopy forever and never advances.
        if (isSolid(feet.up().up())) return false;
        return !isSolid(wall.up()) && !isSolid(wall.up().up());
    }

    /** Ceiling directly over our head, when it is what blocks a hop. */
    private BlockPos blockedCeiling() {
        BlockPos c = playerPos().up().up();
        return isDiggable(c) && withinReachDistance(c) ? c : null;
    }

    private boolean digBelow(long now) {
        BlockPos below = playerPos().down();
        if (!isDiggable(below) || blacklist.containsKey(below) || !withinReachDistance(below)) return false;

        stopWalking();
        int r = tickDig(below);
        if (r == DONE) {
            lastProgressAt = now;
        } else if (r == FAILED || r == BLOCKED) {
            blacklist.put(below, now + RETRY_MS);
        }
        return true;
    }

    private void faceTowards(double dx, double dz) {
        float wantYaw = Rotator.normalizeYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        if (Math.abs(Rotator.angleDifference(Rotator.normalizeYaw(mc.thePlayer.rotationYaw), wantYaw)) > 2.0f
                && !rotator.isActive()) {
            rotator.begin(wantYaw, 0f, lookDuration());
        }
    }

    private void stopWalking() {
        releaseMovement();
        release(mc.gameSettings.keyBindJump);
    }

    // ---- candidates ----

    /**
     * Blocks close enough to hit. Line of sight is deliberately NOT required:
     * something in front simply becomes the next thing to remove.
     */
    private BlockPos findInRange() {
        List<BlockPos> candidates = new ArrayList<BlockPos>();
        BlockPos me = playerPos();
        int r = Math.max(1, BotConfig.scanRadius);

        int loY = BotConfig.limitHeight2 ? 0 : -r;
        int hiY = BotConfig.limitHeight2 ? 1 : r;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = loY; dy <= hiY; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = me.add(dx, dy, dz);
                    if (blacklist.containsKey(p)) continue;
                    if (!isTarget(p)) continue;
                    if (!withinReachDistance(p)) continue;
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) {
            pickedFirst = false;
            return null;
        }
        if (!pickedFirst) {
            pickedFirst = true;
            return candidates.get(rng.nextInt(candidates.size()));
        }

        return bestToDig(candidates);
    }

    private List<BlockPos> findClusters() {
        BlockPos me = playerPos();
        int r = Math.max(4, BotConfig.searchRadius);
        int ry = Math.max(1, BotConfig.searchRadiusY);
        if (wideScan) {
            r = Math.max(r, WIDE_SCAN_RADIUS);
            ry = Math.max(ry, WIDE_SCAN_VERT);
        }

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        Set<BlockPos> roots = new HashSet<BlockPos>();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -ry; dy <= ry; dy++) {
                    cur.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (!isTarget(cur)) continue;
                    BlockPos root = rootOf(cur.getImmutable());
                    if (blacklist.containsKey(root)) continue;
                    roots.add(root);
                }
            }
        }
        return new ArrayList<BlockPos>(roots);
    }

    /**
     * Is there anywhere next to this block the player could stand and still
     * reach it? A cliff face is full of blocks with nothing to stand on within
     * arm's length, and walking to each one to find that out is the loop that
     * kept printing "straight overhead and out of reach".
     */
    private boolean standableNear(BlockPos c) {
        // With pillaring available, height is no longer a reason to rule
        // something out: we can build up to it.
        if (BotConfig.pillarUp && findBlockSlot() >= 0) return true;

        // At or below our own level we can always work toward it by digging
        // forward or down, so standing room is not the question there. It only
        // arises for things above us.
        if (c.getY() <= playerPos().getY() + 1) return true;

        BlockPos[] cols = {c.north(), c.south(), c.east(), c.west(), c};
        double blockY = c.getY() + 0.5;

        for (BlockPos col : cols) {
            for (int dy = 1; dy >= -4; dy--) {
                BlockPos feet = col.up(dy);
                if (feet.equals(c) || feet.up().equals(c)) continue;
                if (isSolid(feet) || isSolid(feet.up())) continue;
                if (!isSolid(feet.down())) continue;
                if (Math.abs(blockY - (feet.getY() + 1.62)) <= 4.0) return true;
            }
        }
        return false;
    }

    /** How much closer, in gap units, a climb spot has to be to be worth it. */
    private double gapFrom(BlockPos from) {
        double flatD = flat(destination, from.getX() + 0.5, from.getZ() + 0.5);
        int rise = Math.max(0, destination.getY() - from.getY());
        return flatD + rise * 1.5;
    }

    /**
     * Walks a route up. A single greedy step can only take the one ledge
     * directly in front, so a way up that needs going round a corner first was
     * invisible and the bot just jumped at a wall. This searches the walkable
     * cells nearby -- steps up of one, level moves, drops of up to three --
     * and follows whichever route ends up meaningfully closer to the
     * destination.
     */
    private boolean climbStep(long now) {
        if (climbPath != null) {
            if (climbIndex >= climbPath.size()) {
                climbPath = null;
            } else if (now > climbUntil) {
                blacklist.put(climbPath.get(climbIndex), now + RETRY_MS);
                climbPath = null;
            } else {
                BlockPos wp = climbPath.get(climbIndex);
                BlockPos feet = playerPos();
                if (feet.getX() == wp.getX() && feet.getZ() == wp.getZ() && feet.getY() >= wp.getY()) {
                    climbIndex++;
                    climbUntil = now + CLIMB_HOLD_MS;
                    return true;
                }
                walkTo(wp);
                return true;
            }
        }

        List<BlockPos> path = findClimbPath();
        if (path == null || path.isEmpty()) return false;

        climbPath = path;
        climbIndex = 0;
        climbUntil = now + CLIMB_HOLD_MS;
        BlockPos goal = path.get(path.size() - 1);
        MWMineBot.info("\u00a77\u767b\u308b: " + goal.getX() + "," + goal.getY() + "," + goal.getZ()
                + " (" + path.size() + "\u6b69)");
        walkTo(path.get(0));
        return true;
    }

    /** Cells reachable on foot from here, best one for closing the gap. */
    private List<BlockPos> findClimbPath() {
        BlockPos start = playerPos();
        double startGap = gapFrom(start);

        Map<BlockPos, BlockPos> parent = new HashMap<BlockPos, BlockPos>();
        Set<BlockPos> seen = new HashSet<BlockPos>();
        ArrayDeque<BlockPos> open = new ArrayDeque<BlockPos>();
        seen.add(start);
        open.add(start);

        BlockPos best = null;
        double bestGap = startGap - CLIMB_MIN_GAIN;
        int expanded = 0;

        while (!open.isEmpty() && expanded < CLIMB_NODES) {
            BlockPos cur = open.poll();
            expanded++;

            double g = gapFrom(cur);
            if (g < bestGap) {
                bestGap = g;
                best = cur;
            }

            for (int i = 0; i < CARDINAL.length; i++) {
                BlockPos side = cur.offset(CARDINAL[i]);
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos c = side.up(dy);
                    if (Math.abs(c.getX() - start.getX()) > CLIMB_RADIUS) continue;
                    if (Math.abs(c.getZ() - start.getZ()) > CLIMB_RADIUS) continue;
                    if (Math.abs(c.getY() - start.getY()) > CLIMB_VERT) continue;
                    if (seen.contains(c)) continue;
                    if (blacklist.containsKey(c)) continue;
                    if (!canStandAt(c)) continue;
                    // A step up also needs headroom to climb into.
                    if (dy == 1 && isSolid(cur.up().up())) continue;

                    seen.add(c);
                    parent.put(c, cur);
                    open.add(c);
                    break;
                }
            }
        }

        if (best == null) return null;

        List<BlockPos> path = new ArrayList<BlockPos>();
        BlockPos cur = best;
        while (cur != null && !cur.equals(start)) {
            path.add(cur);
            cur = parent.get(cur);
        }
        Collections.reverse(path);
        return path;
    }

    private void walkTo(BlockPos p) {
        releaseAttack();
        double dx = (p.getX() + 0.5) - mc.thePlayer.posX;
        double dz = (p.getZ() + 0.5) - mc.thePlayer.posZ;
        walkToward(dx, dz);
        faceTowards(dx, dz);
        if (p.getY() > playerPos().getY()) hold(mc.gameSettings.keyBindJump);
        else release(mc.gameSettings.keyBindJump);
    }

    /**
     * Jump, place a block underneath at the top of the arc, repeat.
     *
     * Each jump is measured. Never getting a block of air above means the
     * ceiling is in the way; getting the height but landing back where we
     * started means the block did not go down. Both used to just loop.
     */
    private boolean pillarStep(long now) {
        int slot = findBlockSlot();
        if (slot < 0) {
            if (!warnedNoBlocks) {
                warnedNoBlocks = true;
                MWMineBot.log("§eOut of blocks to stack — cannot climb");
                alertSound();
            }
            pillarLocked = false;
            return false;
        }
        warnedNoBlocks = false;
        if (pillarCount >= PILLAR_MAX || pillarFails >= PILLAR_FAIL_MAX) {
            pillarLocked = false;
            return false;
        }

        releaseAttack();
        pillaring = true;

        if (mc.thePlayer.inventory.currentItem != slot) {
            stopWalking();
            selectHotbar(slot);
            return true;
        }

        boolean onGround = mc.thePlayer.onGround;

        if (onGround && !airborne) {
            // Straddling two blocks means the pillar goes up beside us and we
            // clip our head on it. Stand properly inside one first, at a
            // slightly different spot each time.
            BlockPos feet = playerPos();
            double ox = (feet.getX() + 0.5 + pillarJitterX) - mc.thePlayer.posX;
            double oz = (feet.getZ() + 0.5 + pillarJitterZ) - mc.thePlayer.posZ;
            if (Math.sqrt(ox * ox + oz * oz) > PILLAR_CENTER_TOL) {
                release(mc.gameSettings.keyBindJump);
                walkToward(ox, oz);
                return true;
            }
            releaseMovement();

            jumpStartY = mc.thePlayer.posY;
            jumpPeakY = jumpStartY;
            jumpFloorY = Math.floor(jumpStartY);
            placedThisJump = 0;
            lastPillarAt = 0L;
        }

        if (Math.abs(mc.thePlayer.rotationPitch - 90.0f) > 3.0f) {
            if (!rotator.isActive()) {
                rotator.begin(Rotator.normalizeYaw(mc.thePlayer.rotationYaw), 90.0f, lookDuration());
            }
            release(mc.gameSettings.keyBindJump);
            return true;
        }

        if (!onGround) {
            airborne = true;
            jumpPeakY = Math.max(jumpPeakY, mc.thePlayer.posY);
        }

        if (airborne && onGround) {
            airborne = false;
            double gained = mc.thePlayer.posY - jumpStartY;
            double clearance = jumpPeakY - jumpStartY;

            if (clearance < 0.9) {
                BlockPos ceiling = playerPos().up(2);
                if (isDiggable(ceiling) && withinReachDistance(ceiling)) {
                    MWMineBot.info("§7Ceiling is in the way, digging it out");
                    setTarget(ceiling, true);
                    release(mc.gameSettings.keyBindJump);
                    pillarLocked = false;
                    return true;
                }
                MWMineBot.info("§7Ceiling is blocked, cannot pillar up");
                pillarFails = PILLAR_FAIL_MAX;
                release(mc.gameSettings.keyBindJump);
                pillarLocked = false;
                return false;
            }

            if (gained < 0.5) {
                pillarFails++;
            } else {
                pillarFails = 0;
                pillarCount++;
                lastProgressAt = now;
            }

            // New spot for the next one.
            pillarJitterX = (rng.nextDouble() * 2.0 - 1.0) * PILLAR_JITTER;
            pillarJitterZ = (rng.nextDouble() * 2.0 - 1.0) * PILLAR_JITTER;
            jumpStartY = mc.thePlayer.posY;
            jumpPeakY = jumpStartY;
            jumpFloorY = Math.floor(jumpStartY);
            placedThisJump = 0;
            lastPillarAt = 0L;
        }

        hold(mc.gameSettings.keyBindJump);
        pillarLocked = !onGround;

        // Fired on facts, not on a guess at the apex: airborne, a full block
        // above the floor we left, nothing under our feet.
        boolean straightDown = Math.abs(mc.thePlayer.rotationPitch - 90.0f) <= 2.0f;
        if (straightDown && !onGround && placedThisJump < PILLAR_TAPS
                && mc.thePlayer.posY >= jumpFloorY + 1.0
                && !isSolid(playerPos().down())
                && now - lastPillarAt > PILLAR_GAP_MS) {
            Input.tapUse();
            placedThisJump++;
            lastPillarAt = now;
        }
        return true;
    }

    private BlockPos rootOf(BlockPos p) {
        Block type = blockAt(p);
        BlockPos cur = p;
        for (int i = 0; i < CLUSTER_MAX_DOWN; i++) {
            BlockPos below = cur.down();
            if (blockAt(below) != type) break;
            if (!isExposed(below)) break;
            cur = below;
        }
        return cur;
    }

    private boolean isExposed(BlockPos p) {
        return !isSolid(p.north()) || !isSolid(p.south())
                || !isSolid(p.east()) || !isSolid(p.west()) || !isSolid(p.up());
    }

    private boolean isSolid(BlockPos p) {
        Block b = blockAt(p);
        if (b == Blocks.air) return false;
        try {
            return b.getMaterial().blocksMovement();
        } catch (Exception e) {
            return true;
        }
    }

    private static double flat(BlockPos p, double px, double pz) {
        double dx = (p.getX() + 0.5) - px;
        double dz = (p.getZ() + 0.5) - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void alertNothingFound() {
        MWMineBot.log("§cNothing left to mine");
        try {
            mc.thePlayer.playSound("note.pling", 1.0F, 0.5F);
            mc.thePlayer.playSound("random.orb", 1.0F, 0.6F);
        } catch (Exception ignored) {}
        stop("nothing to mine");
    }

    private void noteUndiggable(long now) {
        undiggableAt.add(now);
        long window = Math.max(1, BotConfig.undigWindowSec) * 1000L;
        while (!undiggableAt.isEmpty() && now - undiggableAt.get(0) > window) {
            undiggableAt.remove(0);
        }
        if (undiggableAt.size() < Math.max(1, BotConfig.undigAlertCount) || now < undiggableQuietUntil) return;

        undiggableQuietUntil = now + UNDIG_QUIET_MS;
        undiggableAt.clear();
        MWMineBot.log("§cFound "
                + Math.max(1, BotConfig.undigAlertCount) + " spots that would not break");
        try {
            mc.thePlayer.playSound("note.pling", 1.0F, 0.5F);
            mc.thePlayer.playSound("note.bass", 1.0F, 0.8F);
        } catch (Exception ignored) {}
    }

    // ---- blocks the server puts straight back ----

    /**
     * The map boundary in Mega Walls reads as ordinary stone and breaks
     * client-side, then the server hands it straight back. BotBase counts
     * those and calls this once a spot has proved it will never go; from here
     * on it is unbreakable as far as every scan and dig is concerned, so all
     * that is left is to stop working on it.
     */
    @Override
    protected void onCursed(BlockPos p) {
        long now = System.currentTimeMillis();
        if (p.equals(target)) {
            abortDig();
            clearTarget();
        }
        if (p.equals(pendingTarget)) pendingTarget = null;
        if (p.equals(destination)) dropDestination();
        ownChests.remove(p);
        blacklist.put(p, now + BLACKLIST_MS);
        pickedFirst = false;
        noteUndiggable(now);
    }

    // ---- chests out of our own digging ----

    @Override
    protected void onChestAppeared(BlockPos p) {
        // Not gated on the position's own blacklist: that entry, if any, is a
        // leftover RETRY_MS from whatever used to be at p before it became a
        // chest, and has nothing to do with whether the chest itself is
        // worth going for.
        if (!BotConfig.mineChest) return;
        ownChests.put(p, System.currentTimeMillis() + OWN_CHEST_KEEP_MS);
        MWMineBot.info("§6Chest appeared at " + p.getX() + "," + p.getY() + "," + p.getZ() + " -> taking it next");
    }

    /** True when an actual chest block is sitting at p right now. */
    private boolean isChestHere(BlockPos p) {
        Block b = blockAt(p);
        return b == Blocks.chest || b == Blocks.trapped_chest;
    }

    /**
     * Nearest chest from our own digging that is still there and still
     * wanted. Anything taken, protected, blacklisted or stale is dropped.
     *
     * Deliberately checks isChestHere rather than isTarget: isTarget also
     * fails on isCursed or BotConfig.mineChest going false for reasons that
     * have nothing to do with whether this exact chest is still standing, and
     * that used to drop a perfectly good chest from tracking -- which looks
     * exactly like giving up on something never actually taken.
     */
    private BlockPos nextOwnChest(long now) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        Iterator<Map.Entry<BlockPos, Long>> it = ownChests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> e = it.next();
            BlockPos p = e.getKey();

            if (now > e.getValue()) {
                MWMineBot.log("Gave up chasing chest at " + p.getX() + "," + p.getY() + "," + p.getZ()
                        + " (never got there in time)");
                it.remove();
                continue;
            }
            if (!BotConfig.mineChest) {
                it.remove();
                continue;
            }
            if (!isChestHere(p)) {
                MWMineBot.log("Chest at " + p.getX() + "," + p.getY() + "," + p.getZ()
                        + " is gone (someone else took it, or it was never really one) -> dropping");
                it.remove();
                continue;
            }
            if (blacklist.containsKey(p)) {
                it.remove();
                continue;
            }

            double d = distanceToBlock(p);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void expireBlacklist(long now) {
        Iterator<Map.Entry<BlockPos, Long>> it = blacklist.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) it.remove();
        }
    }

    /** Target test that can see block metadata. Use this, not the Block one. */
    public static boolean isTargetState(IBlockState st) {
        if (st == null) return false;
        Block b = st.getBlock();
        if (b == Blocks.stone) {
            if (!BotConfig.mineStone) return false;
            try {
                // Plain stone only. Granite, diorite and andesite are the
                // same block with different metadata and are not worth it.
                return st.getValue(BlockStone.VARIANT) == BlockStone.EnumType.STONE;
            } catch (Exception e) {
                return true;
            }
        }
        return isTargetBlock(b);
    }

    private boolean isTarget(BlockPos p) {
        if (isCursed(p)) return false;
        try {
            return isTargetState(mc.theWorld.getBlockState(p));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isTargetBlock(Block b) {
        if (b == null || b == Blocks.air || b == Blocks.bedrock) return false;
        if (BotConfig.mineStone && b == Blocks.stone) return true;
        if (BotConfig.mineLog && (b == Blocks.log || b == Blocks.log2)) return true;
        if (BotConfig.mineDirt && (b == Blocks.dirt || b == Blocks.grass)) return true;
        if (BotConfig.mineIron && b == Blocks.iron_ore) return true;
        if (BotConfig.mineCoal && b == Blocks.coal_ore) return true;
        if (BotConfig.mineIronBlock && b == Blocks.iron_block) return true;
        if (BotConfig.mineChest && (b == Blocks.chest || b == Blocks.trapped_chest)) return true;
        return false;
    }
}
