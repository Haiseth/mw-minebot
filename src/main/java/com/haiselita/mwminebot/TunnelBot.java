package com.haiselita.mwminebot;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.List;

/**
 * Digs the Mega Walls "tunnel".
 *
 * Stairs go straight down at one block per block: each step clears the column
 * in front and the block under it, so stepping forward drops exactly one
 * level. The column is dug STAIR_HEIGHT tall so there is headroom.
 *
 * Bedrock is the marker throughout: bedrock ahead means turn to whichever side
 * is not bedrock and keep descending; bedrock under our own feet means the
 * floor is reached and the 2-high horizontal corridor starts. In the corridor,
 * bedrock ahead is a corner, so turn and carry on.
 */
public class TunnelBot extends BotBase {

    private enum Phase { STAIR, TUNNEL }

    private static final int MAX_CONSECUTIVE_TURNS = 4;

    /** Distinct spots that refuse to break, with no step gained in between. */
    private static final int CURSED_UNTIL_STOP = 2;

    /** Vertical blocks cleared per stair step, counting the step-down block. */
    private static final int STAIR_HEIGHT = 4;

    /** Corridor height once the bedrock floor is reached. */
    private static final int TUNNEL_HEIGHT = 2;

    /** Time on the ground before the next step is planned. */
    private static final long SETTLE_MS = 150L;

    private Phase phase = Phase.STAIR;
    private EnumFacing dir = EnumFacing.NORTH;

    private final List<BlockPos> queue = new ArrayList<BlockPos>();
    private BlockPos moveTarget = null;
    private boolean moving = false;
    private boolean movingDown = false;
    private int walkFromY = 0;
    private boolean settling = false;
    private long settleSince = 0L;
    private int turnsInARow = 0;
    private BlockPos failedOn = null;
    private int failStreak = 0;
    private int cursedSinceMove = 0;

    @Override
    public String displayName() {
        return "Tunnel bot";
    }

    @Override
    protected void onStart() {
        dir = facingFromYaw();
        queue.clear();
        moveTarget = null;
        moving = false;
        movingDown = false;
        settling = false;
        turnsInARow = 0;
        cursedSinceMove = 0;
        resetRevivals();
        phase = isBedrock(playerPos().down()) ? Phase.TUNNEL : Phase.STAIR;
        MWMineBot.info("§7" + (phase == Phase.STAIR ? "Stairs" : "Corridor") + " started (" + dir + ")");
    }

    @Override
    protected void onMineBlocked(BlockPos pos) {
        MWMineBot.info("§7Cannot mine that, turning");
        turn();
    }

    @Override
    protected void onTickRunning() {
        long now = System.currentTimeMillis();
        checkRevivals();
        // checkRevivals can decide the run is over. Nothing below should run
        // for another tick once it has.
        if (!isRunning()) return;

        if (moving) {
            int r = movingDown ? tickMoveDown(dir, walkFromY) : tickMove(dir, moveTarget);
            if (r == DONE || r == FAILED) {
                // Getting a step further along is the only thing that counts
                // as progress. Resetting the streak on a finished dig instead
                // is what made the map wall an endless loop: every wall block
                // "breaks" once, so the turn limit was cleared each time and
                // the bot just walked the wall digging it over and over.
                if (r == DONE) {
                    turnsInARow = 0;
                    cursedSinceMove = 0;
                }
                moving = false;
                queue.clear();
                settling = true;
                settleSince = now;
            }
            return;
        }

        // Walking forward off a stair means falling, and the fall takes several
        // ticks. Planning before it lands reads the pre-fall height and digs
        // another step at the old level, which is what turned the staircase
        // into two blocks forward per block down.
        if (settling) {
            releaseAttack();
            if (!mc.thePlayer.onGround) {
                settleSince = now;
                return;
            }
            if (now - settleSince < SETTLE_MS) return;
            settling = false;
        }

        if (queue.isEmpty()) {
            planStep();
            return;
        }

        // Of what is left to dig, take whatever needs the least camera travel
        // from where we are looking right now.
        BlockPos p = cheapestByAim();
        if (p == null) {
            queue.clear();
            beginWalk();
            return;
        }

        if (isUnbreakable(p)) {
            turn();
            return;
        }
        if (!isDiggable(p)) {
            queue.remove(p);
            if (queue.isEmpty()) beginWalk();
            return;
        }

        int r = tickDig(p);
        if (r == DONE) {
            failedOn = null;
            failStreak = 0;
            queue.remove(p);
            if (queue.isEmpty()) beginWalk();
        } else if (r == BLOCKED) {
            BlockPos obs = getObstruction();
            if (obs != null && isDiggable(obs)) {
                // The crosshair is already on it, so the next tick picks it up
                // as the cheapest aim. Turning here was wrong: inside a tunnel
                // the "obstruction" is usually just the other block of the same
                // step, so every step triggered a turn and the tunnel wandered
                // until it hit the turn limit and stopped.
                if (!queue.contains(obs)) queue.add(0, obs);
            } else {
                turn();
            }
        } else if (r == FAILED) {
            // Falling down a stair step moves the eye a lot, so one failed aim
            // is normal. Only give up on the block after it fails twice.
            if (p.equals(failedOn)) {
                failStreak++;
            } else {
                failedOn = p;
                failStreak = 1;
            }
            if (failStreak >= 2) {
                failedOn = null;
                failStreak = 0;
                turn();
            }
        }
    }

    /**
     * Two separate spots refusing to break without a single step gained
     * between them is the map wall, not bad luck. Turning again would only
     * walk the bot along it chewing the same blocks over and over.
     */
    @Override
    protected void onCursed(BlockPos p) {
        abortDig();
        queue.remove(p);
        if (++cursedSinceMove >= CURSED_UNTIL_STOP) {
            MWMineBot.log("§cThe blocks here keep coming back — this is the map wall, stopping");
            stop("map wall");
        }
    }

    private BlockPos cheapestByAim() {
        return bestToDig(queue);
    }

    /** Fills the dig queue for one step forward, or turns if blocked. */
    private void planStep() {
        BlockPos feet = playerPos();
        BlockPos front = feet.offset(dir);

        if (phase == Phase.STAIR) {
            // Bedrock directly under us is the floor: stop descending.
            if (isBedrock(feet.down())) {
                phase = Phase.TUNNEL;
                turnsInARow = 0;
                MWMineBot.info("§aBedrock underfoot → switching to the 2 high corridor");
                return;
            }
            if (isUnbreakable(front) || isUnbreakable(front.up())) {
                MWMineBot.info("§7Cannot get through ahead → turning");
                turn();
                return;
            }
            // One block forward, one block down: the step is front.down(), and
            // the column above it is cleared STAIR_HEIGHT tall for headroom.
            BlockPos step = front.down();
            for (int i = 0; i < STAIR_HEIGHT; i++) {
                addIfDiggable(step.up(i));
            }
        } else {
            if (isUnbreakable(front) || isUnbreakable(front.up())) {
                MWMineBot.info("§7Corner → turning");
                turn();
                return;
            }
            for (int i = 0; i < TUNNEL_HEIGHT; i++) {
                addIfDiggable(front.up(i));
            }
        }

        if (queue.isEmpty()) beginWalk();
    }

    private void addIfDiggable(BlockPos p) {
        if (isDiggable(p) && !queue.contains(p)) queue.add(p);
    }

    private void beginWalk() {
        BlockPos feet = playerPos();
        moveTarget = feet.offset(dir);
        walkFromY = feet.getY();
        // On a stair the step is only done once we have fallen a level.
        movingDown = (phase == Phase.STAIR);
        beginMove();
        moving = true;
    }

    /** Turns to whichever side is not bedrock. Stops when both are. */
    private void turn() {
        abortDig();
        queue.clear();
        moving = false;
        movingDown = false;
        settling = false;

        if (++turnsInARow > MAX_CONSECUTIVE_TURNS) {
            stop("dead end");
            return;
        }

        EnumFacing[] options = {turnLeft(dir), turnRight(dir)};
        BlockPos feet = playerPos();
        for (EnumFacing o : options) {
            BlockPos f = feet.offset(o);
            if (!isUnbreakable(f) && !isUnbreakable(f.up())) {
                dir = o;
                MWMineBot.info("§7Turned: " + dir);
                return;
            }
        }
        stop("bedrock on both sides");
    }
}
