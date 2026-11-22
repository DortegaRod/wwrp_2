package com.wwrp2.feature.replay.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wwrp2.Wwrp2;
import com.wwrp2.feature.replay.ReplayStatePacket;
import net.minecraft.client.KeyMapping;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Lo que el cliente sabe del replay que está viendo (lo manda el servidor) y la tecla del menú.
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class ReplayClientState {
    static final KeyMapping OPEN_MENU = new KeyMapping("key.wwrp2.replay_menu", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.wwrp2");

    /** Estado actual, o {@code null} si no está viendo ningún replay. */
    private static ReplayStatePacket state;
    /**
     * El menú estaba abierto y no lo ha cerrado el jugador. Al saltar hacia atrás el replay vuelve a
     * empezar y la pantalla de carga del mundo lo tapa: con esto se vuelve a abrir al terminar.
     */
    static boolean menuWanted;

    private ReplayClientState() {
    }

    public static ReplayStatePacket state() {
        return state;
    }

    public static void onState(ReplayStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!packet.active()) {
            state = null;
            menuWanted = false;
            if (minecraft.screen instanceof ReplayScreen) {
                minecraft.setScreen(null);
            }
            return;
        }
        state = packet;
        if (packet.open() || (menuWanted && !packet.seeking() && minecraft.screen == null)) {
            openMenu();
        }
    }

    static void openMenu() {
        Minecraft minecraft = Minecraft.getInstance();
        if (state != null && !(minecraft.screen instanceof ReplayScreen)) {
            minecraft.setScreen(new ReplayScreen());
        }
    }

    /**
     * El visor manda más de un "Join Game" por la misma conexión (al entrar al replay, al saltar
     * hacia atrás y al volver). Forge, en cada uno, mete sus filtros en la tubería de red y, como ya
     * estaban, falla ("Duplicate handler name: forge:forge_fixes") y se salta el final del login.
     * Este evento llega justo antes: se quitan para que Forge los vuelva a poner sin error. En el
     * primer login todavía no existen y no se toca nada.
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ChannelPipeline pipeline = event.getConnection().channel().pipeline();
        for (String name : FORGE_FILTERS) {
            if (pipeline.get(name) != null) {
                pipeline.remove(name);
            }
        }
    }

    private static final String[] FORGE_FILTERS = {"forge:vanilla_filter", "forge:forge_fixes"};

    /** Al desconectarse no llega el estado "inactivo": limpiar al entrar o salir de un servidor. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            state = null;
            menuWanted = false;
            return;
        }
        while (OPEN_MENU.consumeClick()) {
            openMenu();
        }
    }

    @Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        private Keys() {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MENU);
        }
    }
}
