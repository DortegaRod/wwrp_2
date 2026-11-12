package com.wwrp2.feature.killfeed.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.wwrp2.Wwrp2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Homenaje holográfico a los caídos, independiente de los títulos de otras features. */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class KillfeedAnnouncement {
    private static final int DURATION_TICKS = 80;
    @SuppressWarnings("removal")
    private static final ResourceLocation INTERFACE_FONT = new ResourceLocation(Wwrp2.MOD_ID, "killfeed/interface");
    private static final Deque<Announcement> WAITING = new ArrayDeque<>();
    private static Announcement current;
    private static int remainingTicks;

    private KillfeedAnnouncement() {
    }

    public static void show(String victim, UUID victimId, String killer) {
        // Se conserva la skin conocida al recibir la baja, aunque el jugador se desconecte después.
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null || victimId == null ? null : connection.getPlayerInfo(victimId);
        ResourceLocation skin = info != null ? info.getSkinLocation()
                : victimId == null ? DefaultPlayerSkin.getDefaultSkin() : DefaultPlayerSkin.getDefaultSkin(victimId);
        var announcement = new Announcement(victim, skin, killer);
        if (current == null) {
            current = announcement;
            remainingTicks = DURATION_TICKS;
        } else {
            // El chat conserva todas las bajas; el aviso no acumula una cola interminable.
            if (WAITING.size() >= 3) {
                WAITING.removeFirst();
            }
            WAITING.addLast(announcement);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().isPaused()) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            clear();
        } else if (current != null && --remainingTicks <= 0) {
            current = WAITING.pollFirst();
            remainingTicks = current == null ? 0 : DURATION_TICKS;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        WAITING.clear();
        current = null;
        remainingTicks = 0;
    }

    private static void render(GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!KillfeedChat.isCombatActive() || current == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        float time = remainingTicks - partialTick;
        float entrance = Math.min(1.0F, (DURATION_TICKS - time) / 10.0F);
        float opacity = Math.min(1.0F, Math.min(entrance, time / 16.0F));
        int alpha = Math.max(0, Math.min(255, (int) (opacity * 255)));
        if (alpha < 8) {
            return;
        }

        // Como máximo ocupa el 22 % de la altura, también con la GUI automática en pantalla completa.
        float availableHeight = Math.min(height * 0.22F, height / 2.0F - 14);
        float scale = Math.min(1.0F, Math.min((width - 16) / 240.0F, availableHeight / 116.0F));
        if (scale <= 0) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate((width - 240 * scale) / 2, 6 - 4 * (1 - entrance), 0);
        graphics.pose().scale(scale, scale, 1);

        // Fondo azul que se desvanece en los extremos, como una imagen proyectada sobre el cielo.
        for (int x = 0; x < 240; x += 2) {
            float edge = Math.min(1.0F, Math.min(x, 240 - x) / 46.0F);
            graphics.fillGradient(x, 0, x + 2, 116,
                    color(alpha, edge * 0.85F, 0x05111E), color(alpha, edge * 0.74F, 0x071727));
        }
        graphics.fill(24, 0, 216, 1, color(alpha, 0.35F, 0xA5D5ED));
        graphics.fill(24, 115, 216, 116, color(alpha, 0.25F, 0xA5D5ED));
        drawCentered(graphics, "LOS CAÍDOS", 120, 6, 1.4F, color(alpha, 1, 0xD6ECFC));
        graphics.fill(28, 13, 70, 14, color(alpha, 0.35F, 0x84B5D2));
        graphics.fill(170, 13, 212, 14, color(alpha, 0.35F, 0x84B5D2));

        drawPortrait(graphics, alpha, opacity);
        drawLaurels(graphics, alpha);
        drawFittedCentered(graphics, current.victim(), 120, 78, 214, 1.75F, color(alpha, 1, 0xF1F7FF));
        String attribution = current.killer() == null ? "Eliminador no identificado" : "Eliminado por " + current.killer();
        drawFittedCentered(graphics, attribution, 120, 100, 214, 1.1F, color(alpha, 1, 0xD8E9F6));
        graphics.pose().popPose();
    }

    private static void drawPortrait(GuiGraphics graphics, int alpha, float opacity) {
        graphics.fill(96, 24, 144, 72, color(alpha, 0.22F, 0x7CAACD));
        graphics.fill(97, 25, 143, 71, color(alpha, 0.95F, 0x091827));
        RenderSystem.enableBlend();
        graphics.setColor(0.55F, 0.80F, 1.0F, opacity * 0.95F);
        try {
            PlayerFaceRenderer.draw(graphics, current.skin(), 99, 27, 42);
        } finally {
            graphics.setColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        }
        // Retícula y barrido discretos: el retrato forma parte de la proyección.
        for (int y = 28; y < 69; y += 3) {
            graphics.fill(99, y, 141, y + 1, color(alpha, 0.18F, 0x071725));
        }
        int scan = 27 + (DURATION_TICKS - remainingTicks) % 42;
        graphics.fill(99, scan, 141, scan + 1, color(alpha, 0.18F, 0xC9E9FC));
        for (int side : new int[]{96, 139}) {
            graphics.fill(side, 24, side + 5, 25, color(alpha, 0.8F, 0xBBD9EA));
            graphics.fill(side, 71, side + 5, 72, color(alpha, 0.8F, 0xBBD9EA));
        }
    }

    private static void drawLaurels(GuiGraphics graphics, int alpha) {
        // Emblema propio de homenaje: dos ramas rodean al jugador, sin distritos inventados.
        for (int side : new int[]{-1, 1}) {
            for (int leaf = 0; leaf < 7; leaf++) {
                float angle = (float) Math.toRadians(26 + leaf * 18);
                float x = 120 + side * 38 * (float) Math.sin(angle);
                float y = 46 - 29 * (float) Math.cos(angle);
                graphics.pose().pushPose();
                graphics.pose().translate(x, y, 0);
                graphics.pose().mulPose(Axis.ZP.rotationDegrees(side * (25 + leaf * 12)));
                graphics.fill(-1, -4, 1, 4, color(alpha, 0.7F, 0x9FC6DE));
                graphics.fill(-2, -2, 2, 2, color(alpha, 0.7F, 0x9FC6DE));
                graphics.pose().popPose();
            }
        }
    }

    private record Announcement(String victim, ResourceLocation skin, String killer) {
    }

    private static void drawCentered(GuiGraphics graphics, String text, int center, int y, float scale, int color) {
        drawText(graphics, text, center - Math.round(Minecraft.getInstance().font.width(text(text)) * scale / 2), y, scale, color);
    }

    private static void drawFittedCentered(GuiGraphics graphics, String text, int center, int y, int width, float maxScale, int color) {
        float scale = Math.min(maxScale, width / (float) Math.max(1, Minecraft.getInstance().font.width(text(text))));
        drawCentered(graphics, text, center, y, scale, color);
    }

    private static void drawText(GuiGraphics graphics, String text, int x, int y, float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(Minecraft.getInstance().font, text(text), 0, 0, color, false);
        graphics.pose().popPose();
    }

    private static Component text(String value) {
        return Component.literal(value).withStyle(style -> style.withFont(INTERFACE_FONT));
    }

    private static int color(int alpha, float strength, int rgb) {
        return (Math.round(alpha * strength) << 24) | rgb;
    }

    @Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("killfeed", (gui, graphics, partialTick, width, height) ->
                    render(graphics, partialTick, width, height));
        }
    }
}
