package com.wwrp2.feature.slide;

import com.wwrp2.Wwrp2;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Parte de servidor del deslizamiento: valida lo que pide cada cliente, baja la hitbox del
 * jugador mientras se desliza y avisa a los clientes que lo ven para que reproduzcan la animación.
 *
 * <p>Se usa siempre desde el hilo del servidor (paquetes y eventos del juego).</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class SlideManager {
    /** Jugadores que se están deslizando y los ticks que llevan. */
    private static final Map<UUID, Integer> SLIDING = new HashMap<>();

    private SlideManager() {
    }

    /** Si el jugador se está deslizando ahora mismo. Solo tiene sentido en el servidor. */
    public static boolean isSliding(Player player) {
        return SLIDING.containsKey(player.getUUID());
    }

    static void onClientRequest(ServerPlayer player, boolean sliding) {
        if (!sliding) {
            if (isSliding(player)) {
                stop(player, false);
            }
            return;
        }
        if (isSliding(player)) {
            return;
        }
        if (!SlideRules.canSlide(player)) {
            // El cliente ya ha empezado por su cuenta: se le dice que lo corte.
            SlideNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new SlideStatePacket(player.getId(), false));
            return;
        }

        SLIDING.put(player.getUUID(), 0);
        // Hitbox y altura de ojos de agachado: se pasa por huecos de bloque y medio y un tiro
        // a la altura de la cabeza de alguien de pie falla. setPose lo aplica ya, sin esperar
        // al siguiente tick del jugador, para que el servidor no vea chocar el primer movimiento.
        player.setForcedPose(Pose.CROUCHING);
        player.setPose(Pose.CROUCHING);
        SlideNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> player),
                new SlideStatePacket(player.getId(), true));
    }

    /** @param notifySelf true si es el servidor quien corta el deslizamiento y el propio jugador tiene que enterarse. */
    private static void stop(ServerPlayer player, boolean notifySelf) {
        SLIDING.remove(player.getUUID());
        // Solo se quita la postura forzada si es la nuestra, por si otro mod ha puesto la suya.
        if (player.getForcedPose() == Pose.CROUCHING) {
            player.setForcedPose(null);
        }
        PacketDistributor<Entity> target =
                notifySelf ? PacketDistributor.TRACKING_ENTITY_AND_SELF : PacketDistributor.TRACKING_ENTITY;
        SlideNetwork.CHANNEL.send(target.with(() -> player), new SlideStatePacket(player.getId(), false));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        Integer ticks = SLIDING.get(player.getUUID());
        if (ticks == null) {
            return;
        }
        // El cliente avisa cuando termina; esto solo salta si el aviso no llega o si el jugador
        // ya no puede deslizarse (ha muerto, se ha subido a algo, ha caído al agua...).
        if (ticks >= SlideRules.TIMEOUT_TICKS || !SlideRules.canSlide(player)) {
            stop(player, true);
        } else {
            SLIDING.put(player.getUUID(), ticks + 1);
        }
    }

    /** Quien empieza a ver a un jugador que ya se está deslizando también tiene que ver la animación. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && isSliding(target)
                && event.getEntity() instanceof ServerPlayer watcher) {
            SlideNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> watcher),
                    new SlideStatePacket(target.getId(), true));
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // Al reaparecer el jugador es una entidad nueva, sin postura forzada.
        SLIDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SLIDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        // En un solo jugador el mod sigue cargado al cambiar de mundo: cada servidor empieza de cero.
        SLIDING.clear();
    }
}
