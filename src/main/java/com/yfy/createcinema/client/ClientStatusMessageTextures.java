package com.yfy.createcinema.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yfy.createcinema.CreateCinema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

final class ClientStatusMessageTextures {
    private static final int WIDTH = 512;
    private static final int HEIGHT = 96;
    private static final int MAX_TEXTURES = 16;
    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private ClientStatusMessageTextures() {
    }

    static ResourceLocation texture(Component message, boolean error) {
        String text = message.getString();
        String key = (error ? "error:" : "info:") + text;
        Entry cached = CACHE.get(key);
        if (cached != null) return cached.location;
        NativeImage image = createImage(text, error);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID,
                "status_message/" + Integer.toUnsignedString(key.hashCode()));
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        CACHE.put(key, new Entry(location, texture));
        trim();
        return location;
    }

    private static void trim() {
        while (CACHE.size() > MAX_TEXTURES) {
            Map.Entry<String, Entry> oldest = CACHE.entrySet().iterator().next();
            Minecraft.getInstance().getTextureManager().release(oldest.getValue().location);
            CACHE.remove(oldest.getKey());
        }
    }

    private static NativeImage createImage(String text, boolean error) {
        BufferedImage buffered = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(new Color(10, 14, 20, 215));
        graphics.fillRoundRect(0, 0, WIDTH, HEIGHT, 18, 18);
        graphics.setColor(error ? new Color(255, 76, 76, 230) : new Color(66, 205, 255, 230));
        graphics.fillRoundRect(14, 14, 10, HEIGHT - 28, 8, 8);
        graphics.setColor(error ? new Color(255, 122, 122, 42) : new Color(80, 215, 255, 36));
        graphics.fillRoundRect(34, 12, WIDTH - 48, HEIGHT - 24, 14, 14);

        int size = 31;
        Font font;
        FontMetrics metrics;
        do {
            font = new Font("SansSerif", Font.BOLD, size--);
            graphics.setFont(font);
            metrics = graphics.getFontMetrics();
        } while (size > 17 && metrics.stringWidth(text) > WIDTH - 80);
        int x = Math.max(40, (WIDTH - metrics.stringWidth(text)) / 2);
        int y = (HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.setColor(new Color(0, 0, 0, 115));
        graphics.drawString(text, x + 2, y + 2);
        graphics.setColor(Color.WHITE);
        graphics.drawString(text, x, y);
        graphics.dispose();

        NativeImage image = new NativeImage(WIDTH, HEIGHT, false);
        for (int yPos = 0; yPos < HEIGHT; yPos++) {
            for (int xPos = 0; xPos < WIDTH; xPos++) {
                int argb = buffered.getRGB(xPos, yPos);
                int alpha = argb >>> 24;
                int red = argb >> 16 & 0xFF;
                int green = argb >> 8 & 0xFF;
                int blue = argb & 0xFF;
                image.setPixelRGBA(xPos, yPos, FastColor.ABGR32.color(alpha, blue, green, red));
            }
        }
        return image;
    }

    private record Entry(ResourceLocation location, DynamicTexture texture) {
    }
}
