package com.haiselita.mwminebot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;

/**
 * Picks the right hotbar slot for a block.
 *
 * The block -> tool-class table is taken from the autrotool project. What is
 * added here is picking the *best* matching tool rather than the first one in
 * the hotbar: a diamond pickaxe beats a wooden one, and Efficiency counts.
 */
public class ToolSelector {

    /** Slot the bot found the player on, so it can be handed back. */
    private static int savedSlot = -1;

    public static void remember() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) savedSlot = mc.thePlayer.inventory.currentItem;
    }

    public static void restore() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && savedSlot >= 0 && savedSlot < 9) {
            mc.thePlayer.inventory.currentItem = savedSlot;
        }
        savedSlot = -1;
    }

    /**
     * Switches to the best tool for this block. Must be called before the dig
     * starts: changing slot mid-dig throws away the break progress.
     */
    public static void equipFor(Block block) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || block == null) return;
        int slot = findBestSlot(mc.thePlayer, block);
        if (slot >= 0 && mc.thePlayer.inventory.currentItem != slot) {
            mc.thePlayer.inventory.currentItem = slot;
        }
    }

    /** Best hotbar slot, or -1 when the bare hand is as good as anything held. */
    public static int findBestSlot(EntityPlayer player, Block block) {
        if (player == null || player.inventory == null || block == null) return -1;

        Class<? extends Item> required = requiredTool(block);
        boolean toolRequired = !block.getMaterial().isToolNotRequired();

        int best = -1;
        double bestScore = 1.0; // an empty hand digs at 1.0

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack == null) continue;

            double score;
            try {
                score = stack.getStrVsBlock(block);
            } catch (Exception e) {
                score = 1.0;
            }

            // Nudge the class autrotool would have picked, so ties break sensibly.
            if (required != null && required.isInstance(stack.getItem())) score += 0.5;

            // Wrong tool on a block that needs one: it breaks but drops nothing.
            if (toolRequired) {
                try {
                    if (!stack.canHarvestBlock(block)) score *= 0.1;
                } catch (Exception ignored) {}
            }

            try {
                int eff = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, stack);
                if (eff > 0) score += eff * 0.25;
            } catch (Exception ignored) {}

            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Block -> tool class. Lifted from autrotool's getRequiredToolType. */
    public static Class<? extends Item> requiredTool(Block block) {
        if (block == Blocks.chest
                || block == Blocks.trapped_chest
                || block == Blocks.ladder
                || block == Blocks.crafting_table) {
            return ItemAxe.class;
        }

        if (block == Blocks.iron_bars
                || block == Blocks.iron_block
                || block == Blocks.anvil
                || block == Blocks.iron_ore) {
            return ItemPickaxe.class;
        }

        if (block == Blocks.stone
                || block == Blocks.cobblestone
                || block == Blocks.stonebrick
                || block == Blocks.sandstone
                || block == Blocks.end_stone
                || block == Blocks.netherrack
                || block == Blocks.obsidian
                || block == Blocks.gold_ore
                || block == Blocks.diamond_ore
                || block == Blocks.emerald_ore
                || block == Blocks.redstone_ore
                || block == Blocks.lapis_ore
                || block == Blocks.quartz_block
                || block == Blocks.quartz_ore
                || block == Blocks.ice
                || block == Blocks.packed_ice
                || block == Blocks.hardened_clay
                || block == Blocks.stained_hardened_clay
                || block == Blocks.glowstone) {
            return ItemPickaxe.class;
        }

        if (block == Blocks.dirt
                || block == Blocks.grass
                || block == Blocks.sand
                || block == Blocks.gravel
                || block == Blocks.clay
                || block == Blocks.soul_sand) {
            return ItemSpade.class;
        }

        if (block == Blocks.wool || block == Blocks.web || block == Blocks.leaves) {
            return ItemShears.class;
        }

        if (block instanceof BlockStairs || block instanceof BlockSlab) {
            return block.getMaterial() == Material.wood ? ItemAxe.class : ItemPickaxe.class;
        }

        Material mat = block.getMaterial();
        if (mat == Material.wood) return ItemAxe.class;
        if (mat == Material.rock || mat == Material.iron || mat == Material.anvil) return ItemPickaxe.class;
        if (mat == Material.ground || mat == Material.sand) return ItemSpade.class;
        if (mat == Material.cloth) return ItemShears.class;

        return null;
    }
}
