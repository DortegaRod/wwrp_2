package com.wwrp2.feature.killfeed.client;

import com.wwrp2.Wwrp2;
import com.wwrp2.feature.killfeed.KillfeedMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Cambia una sola entrada del chat y reproduce un aviso corto en el cliente receptor. */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class KillfeedChat {
    private static boolean combatActive;

    private KillfeedChat() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSystemMessage(ClientChatReceivedEvent.System event) {
        if (!combatActive || event.isOverlay()) {
            return;
        }
        Component original = event.getMessage();
        Component formatted = KillfeedMessages.format(original);
        if (formatted == original) {
            return;
        }
        event.setMessage(formatted);
        Component killer = KillfeedMessages.killerName(original);
        KillfeedAnnouncement.show(KillfeedMessages.victimName(original).getString(),
                KillfeedMessages.victimId(original), killer == null ? null : killer.getString());
        var player = Minecraft.getInstance().player;
        if (player != null) {
            // Golpe grave de anuncio, sin explosión ni efectos en el mundo.
            player.playSound(SoundEvents.GENERIC_EXPLODE, 0.6F, 0.65F);
        }
    }

    public static boolean isCombatActive() {
        return combatActive;
    }

    public static void setCombatActive(boolean active) {
        combatActive = active;
        if (!active) {
            KillfeedAnnouncement.clear();
            KillfeedLightning.clear();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        setCombatActive(false);
    }
}
