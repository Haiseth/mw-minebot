package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Vec3;

/**
 * Eased camera rotation.
 *
 * The angle maths and the CUBIC curve are lifted from the existing ab-mod /
 * admod projects so the motion matches what is already in use there (both
 * carry the same algorithm, 40ms per look-at).
 *
 * Two things were changed because 40ms of easeInOutCubic reads as a snap
 * rather than a movement:
 *
 *  1. update() is driven from RenderTickEvent, so the curve is sampled once
 *     per frame instead of once per 50ms game tick. It is wall-clock based,
 *     so the motion is identical at any framerate.
 *  2. prevRotationYaw/Pitch are written alongside rotationYaw/Pitch. The 1.8
 *     camera renders prevRotation + (rotation - prevRotation) * partialTicks;
 *     leaving prev alone makes the camera lag the value we set and wobble.
 */
public class Rotator {

    /**
     * Interpolation curves, ordered by how hard they push in the middle.
     * "Peak" is the top angular speed as a multiple of the average, so a
     * higher number means more of the turn is crammed into the middle.
     */
    public enum Curve {
        /** 3t^2-2t^3. The GLSL smoothstep. Gentlest middle (peak 1.50), but
         *  acceleration jumps from 0 to 6 the instant it starts moving. */
        SMOOTHSTEP,
        /** 0.5(1-cos(pi*t)). One continuous function, no seam (peak 1.57),
         *  same small kick at the two ends as SMOOTHSTEP. */
        SINE,
        /** 6t^5-15t^4+10t^3, Perlin's smootherstep. Velocity AND acceleration
         *  are zero at both ends and continuous all the way through: no kick
         *  starting, none stopping, no seam in the middle (peak 1.88). */
        SMOOTHERSTEP,
        /** 4t^3 / 1-(-2t+2)^3/2. What ab-mod and admod use. Two halves glued
         *  at t=0.5, where acceleration jumps +12 -> -12 (peak 3.00). */
        CUBIC,
        /** 16t^5 / 1-(-2t+2)^5/2. Longest hold at each end, hardest snap
         *  through the middle: acceleration jumps +40 -> -40 (peak 5.00). */
        QUINT;

        public Curve next() {
            Curve[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private final Minecraft mc = Minecraft.getMinecraft();

    /** Angle error below which the aim counts as settled. */
    private static final float SETTLED_DEG = 2.0f;

    private boolean active = false;
    private float startYaw, targetYaw, startPitch, targetPitch;
    private long startTime;
    private long durationMs = 40L;

    /**
     * When set, the required angle is recomputed from this world point every
     * frame. Walking or falling changes the angle to a block continuously, so
     * easing toward an angle captured once always lags behind and the
     * crosshair never lands.
     */
    private Vec3 trackPoint = null;

    public void begin(float toYaw, float toPitch, long duration) {
        if (mc.thePlayer == null) return;
        trackPoint = null;
        this.startYaw = mc.thePlayer.rotationYaw;
        this.startPitch = mc.thePlayer.rotationPitch;
        this.targetYaw = toYaw;
        this.targetPitch = toPitch;
        this.durationMs = Math.max(1L, duration);
        this.startTime = System.currentTimeMillis();
        this.active = true;
    }

    public void beginLookAt(Vec3 target, long duration) {
        if (mc.thePlayer == null) return;
        float[] a = getAnglesToTarget(mc.thePlayer.getPositionEyes(1.0f), target);
        begin(a[0], a[1], duration);
        trackPoint = target;
    }

    /** Crosshair is on the required angle, whether or not it is still easing. */
    public boolean isSettled() {
        if (mc.thePlayer == null) return true;
        if (trackPoint == null) return !active;

        float[] need = getAnglesToTarget(mc.thePlayer.getPositionEyes(1.0f), trackPoint);
        float dYaw = Math.abs(angleDifference(normalizeYaw(mc.thePlayer.rotationYaw), need[0]));
        float dPitch = Math.abs(mc.thePlayer.rotationPitch - need[1]);
        return dYaw <= SETTLED_DEG && dPitch <= SETTLED_DEG;
    }

    public void cancel() {
        active = false;
        trackPoint = null;
    }

    public boolean isActive() {
        return active;
    }

    /** One step of the interpolation. Call once per rendered frame. */
    public void update() {
        if (!active || mc.thePlayer == null) return;

        // Re-read where the point is from here, every frame.
        if (trackPoint != null) {
            float[] need = getAnglesToTarget(mc.thePlayer.getPositionEyes(1.0f), trackPoint);
            targetYaw = need[0];
            targetPitch = need[1];
        }

        long now = System.currentTimeMillis();
        long elapsed = now - startTime;
        float t = Math.min(elapsed / (float) durationMs, 1.0f);
        float eased = ease(BotConfig.curve, t);

        float yaw = interpolateAngle(startYaw, targetYaw, eased);
        float pitch = startPitch + (targetPitch - startPitch) * eased;

        mc.thePlayer.rotationYaw = yaw;
        mc.thePlayer.rotationPitch = pitch;
        mc.thePlayer.prevRotationYaw = yaw;
        mc.thePlayer.prevRotationPitch = pitch;

        if (t < 1.0f) return;

        if (trackPoint == null) {
            active = false;
            return;
        }

        // Still tracking: if the point has drifted, ease onto it again from
        // here rather than stopping short of it.
        float dYaw = Math.abs(angleDifference(normalizeYaw(yaw), targetYaw));
        float dPitch = Math.abs(pitch - targetPitch);
        if (dYaw <= 0.5f && dPitch <= 0.5f) {
            active = false;
            return;
        }
        startYaw = yaw;
        startPitch = pitch;
        startTime = now;
        durationMs = Math.max(30L, Math.min(durationMs, 120L));
    }

    // ---- curves ----

    public static float ease(Curve c, float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        switch (c) {
            case SMOOTHSTEP:
                return t * t * (3f - 2f * t);
            case SINE:
                return (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
            case QUINT:
                return t < 0.5f
                        ? 16f * t * t * t * t * t
                        : 1f - (float) Math.pow(-2f * t + 2f, 5) / 2f;
            case CUBIC:
                return easeInOutCubic(t);
            case SMOOTHERSTEP:
            default:
                return t * t * t * (t * (t * 6f - 15f) + 10f);
        }
    }

    // ---- maths (identical to ab-mod / admod) ----

    public static float normalizeYaw(float yaw) {
        yaw %= 360f;
        return yaw < 0f ? yaw + 360f : yaw;
    }

    public static float angleDifference(float from, float to) {
        return (to - from + 540f) % 360f - 180f;
    }

    public static float interpolateAngle(float from, float to, float t) {
        return normalizeYaw(from + angleDifference(from, to) * t);
    }

    public static float easeInOutCubic(float t) {
        return t < 0.5F ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    public static float[] getAnglesToTarget(Vec3 eyes, Vec3 target) {
        double dx = target.xCoord - eyes.xCoord;
        double dy = target.yCoord - eyes.yCoord;
        double dz = target.zCoord - eyes.zCoord;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new float[]{normalizeYaw(yaw), pitch};
    }
}
