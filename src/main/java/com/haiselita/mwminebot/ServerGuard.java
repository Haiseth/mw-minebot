package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Runs anywhere except the denied hosts. Nothing to configure, so a copy of
 * the jar works out of the box for everyone it is handed to.
 */
public class ServerGuard {

    private static final String[] DENY = {"minemen.club"};

    public static String currentHost() {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData data = mc.getCurrentServerData();
        if (data == null || data.serverIP == null) return "";
        String ip = data.serverIP.trim().toLowerCase();
        int colon = ip.indexOf(':');
        if (colon >= 0) ip = ip.substring(0, colon);
        return ip;
    }

    public static boolean isDenied() {
        String host = currentHost();
        if (host.isEmpty()) return false;
        for (String d : DENY) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    public static boolean isAllowed() {
        return !isDenied();
    }

    public static String statusText() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isSingleplayer()) return "singleplayer";
        String host = currentHost();
        if (host.isEmpty()) return "unknown";
        if (isDenied()) return host + " §c(blocked)";
        return host;
    }
}
