package com.wwrp2.feature.killfeed;

import com.wwrp2.Wwrp2;
import com.wwrp2.feature.rondas.RoundManager;
import com.wwrp2.feature.rondas.RoundPhase;
import com.wwrp2.feature.rondas.RoundPhaseChangeEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Confirma la muerte después del evento cancelable y envía únicamente el efecto visual. */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class KillfeedEvents {
    private static final double EFFECT_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final Map<UUID, LivingDeathEvent> PENDING_DEATHS = new LinkedHashMap<>();

    private KillfeedEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (RoundManager.phase() == RoundPhase.COMBAT && !event.isCanceled()
                && event.getEntity() instanceof ServerPlayer victim) {
            PENDING_DEATHS.put(victim.getUUID(), event);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_DEATHS.isEmpty()) {
            return;
        }
        if (RoundManager.phase() != RoundPhase.COMBAT) {
            PENDING_DEATHS.clear();
            return;
        }
        var deaths = Map.copyOf(PENDING_DEATHS);
        PENDING_DEATHS.clear();
        for (LivingDeathEvent death : deaths.values()) {
            ServerPlayer victim = (ServerPlayer) death.getEntity();
            // También cubre una cancelación posterior de otro mod en la misma prioridad.
            if (!death.isCanceled() && victim.isDeadOrDying()
                    && victim.level().getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) {
                showDeathEffect(victim);
            }
        }
    }

    @SubscribeEvent
    public static void onRoundPhaseChanged(RoundPhaseChangeEvent event) {
        boolean combat = event.getRoundPhase() == RoundPhase.COMBAT;
        if (!combat) {
            PENDING_DEATHS.clear();
        }
        KillfeedNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new KillfeedCombatPacket(combat));
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            KillfeedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new KillfeedCombatPacket(RoundManager.phase() == RoundPhase.COMBAT));
        }
    }

    private static void showDeathEffect(ServerPlayer victim) {
        // Solo se envían coordenadas. El cliente dibuja el rayo sin añadir entidades
        // al mundo: no hay daño, fuego, pararrayos, cobre ni dependencia de sus chunks.
        var packet = new KillfeedEffectPacket(victim.level().dimension().location(),
                victim.getX(), victim.getY(), victim.getZ());
        for (ServerPlayer receiver : victim.serverLevel().players()) {
            if (receiver.distanceToSqr(victim) <= EFFECT_DISTANCE_SQUARED && canSeeDeath(victim, receiver)) {
                KillfeedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> receiver), packet);
            }
        }
    }

    private static boolean canSeeDeath(ServerPlayer victim, ServerPlayer receiver) {
        Team team = victim.getTeam();
        if (team == null) {
            return true;
        }
        boolean sameTeam = team.isAlliedTo(receiver.getTeam());
        return switch (team.getDeathMessageVisibility()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> sameTeam;
            case HIDE_FOR_OWN_TEAM -> !sameTeam;
        };
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_DEATHS.clear();
    }
}
