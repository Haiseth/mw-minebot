package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/** Draws a glowing cube over the block the bot is currently mining. */
public class TargetRenderer {

    private final Minecraft mc = Minecraft.getMinecraft();

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent ev) {
        if (!BotConfig.showTargetBox) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        MWMineBot bot = MWMineBot.instance;
        if (bot == null) return;

        BlockPos pos = null;
        if (bot.miningBot.isRunning()) pos = bot.miningBot.getDigTarget();
        if (pos == null && bot.tunnelBot.isRunning()) pos = bot.tunnelBot.getDigTarget();

        BlockPos dest = bot.miningBot.isRunning() ? bot.miningBot.getDestination() : null;
        if (pos == null && dest == null) return;

        double vx = mc.getRenderManager().viewerPosX;
        double vy = mc.getRenderManager().viewerPosY;
        double vz = mc.getRenderManager().viewerPosZ;

        // Slow pulse so it reads as lit rather than as a flat overlay.
        float phase = (float) ((System.currentTimeMillis() % 1400L) / 1400.0);
        float glow = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);

        double grow = 0.004;
        if (dest != null && !dest.equals(pos)) {
            // Amber: where it is heading, as opposed to what it is breaking.
            drawBox(dest, vx, vy, vz, grow, 1.0f, 0.65f, 0.1f, glow);
        }
        if (pos == null) {
            return;
        }
        drawBox(pos, vx, vy, vz, grow, 0.25f, 1.0f, 0.85f, glow);
    }

    private void drawBox(BlockPos pos, double vx, double vy, double vz, double grow,
                         float r, float g, float bl, float glow) {
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - vx - grow,
                pos.getY() - vy - grow,
                pos.getZ() - vz - grow,
                pos.getX() + 1 - vx + grow,
                pos.getY() + 1 - vy + grow,
                pos.getZ() + 1 - vz + grow);

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        // Always through terrain: this is a debug overlay and the whole point
        // is seeing which block was picked even when it is behind something.
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();

        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        faces(wr, box, r, g, bl, 0.28f + 0.16f * glow);
        tess.draw();

        GL11.glLineWidth(2.5f);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        edges(wr, box, r, g, bl, 0.8f + 0.2f * glow);
        tess.draw();

        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static void faces(WorldRenderer wr, AxisAlignedBB b, float r, float g, float bl, float a) {
        // bottom
        quad(wr, b.minX, b.minY, b.minZ, b.maxX, b.minY, b.minZ, b.maxX, b.minY, b.maxZ, b.minX, b.minY, b.maxZ, r, g, bl, a);
        // top
        quad(wr, b.minX, b.maxY, b.minZ, b.minX, b.maxY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.maxY, b.minZ, r, g, bl, a);
        // north
        quad(wr, b.minX, b.minY, b.minZ, b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.minY, b.minZ, r, g, bl, a);
        // south
        quad(wr, b.minX, b.minY, b.maxZ, b.maxX, b.minY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.minX, b.maxY, b.maxZ, r, g, bl, a);
        // west
        quad(wr, b.minX, b.minY, b.minZ, b.minX, b.minY, b.maxZ, b.minX, b.maxY, b.maxZ, b.minX, b.maxY, b.minZ, r, g, bl, a);
        // east
        quad(wr, b.maxX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.minY, b.maxZ, r, g, bl, a);
    }

    private static void quad(WorldRenderer wr,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x3, y3, z3).color(r, g, b, a).endVertex();
        wr.pos(x4, y4, z4).color(r, g, b, a).endVertex();
    }

    private static void edges(WorldRenderer wr, AxisAlignedBB b, float r, float g, float bl, float a) {
        line(wr, b.minX, b.minY, b.minZ, b.maxX, b.minY, b.minZ, r, g, bl, a);
        line(wr, b.maxX, b.minY, b.minZ, b.maxX, b.minY, b.maxZ, r, g, bl, a);
        line(wr, b.maxX, b.minY, b.maxZ, b.minX, b.minY, b.maxZ, r, g, bl, a);
        line(wr, b.minX, b.minY, b.maxZ, b.minX, b.minY, b.minZ, r, g, bl, a);

        line(wr, b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.minZ, r, g, bl, a);
        line(wr, b.maxX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ, r, g, bl, a);
        line(wr, b.maxX, b.maxY, b.maxZ, b.minX, b.maxY, b.maxZ, r, g, bl, a);
        line(wr, b.minX, b.maxY, b.maxZ, b.minX, b.maxY, b.minZ, r, g, bl, a);

        line(wr, b.minX, b.minY, b.minZ, b.minX, b.maxY, b.minZ, r, g, bl, a);
        line(wr, b.maxX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ, r, g, bl, a);
        line(wr, b.maxX, b.minY, b.maxZ, b.maxX, b.maxY, b.maxZ, r, g, bl, a);
        line(wr, b.minX, b.minY, b.maxZ, b.minX, b.maxY, b.maxZ, r, g, bl, a);
    }

    private static void line(WorldRenderer wr,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
    }
}
