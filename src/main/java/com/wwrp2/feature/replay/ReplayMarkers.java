package com.wwrp2.feature.replay;

import com.wwrp2.Wwrp2;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * Fase 3: marcadores automáticos de muertes en la grabación de zona.
 *
 * <ul>
 *   <li>Kill entre jugadores: {@code Mistic → Pepe}. Cuenta también las muertes «empujadas» (caer
 *       tras un golpe): Minecraft se las atribuye al último jugador que pegó, y se añade la causa,
 *       {@code Mistic → Pepe (caída)}.</li>
 *   <li>Muerte sin jugador que mate: {@code Pepe murió (caída)} o {@code Pepe murió (Zombie)}.</li>
 * </ul>
 *
 * <p>Color: rojo si mata un jugador, naranja si es PvE ({@link MarkerType}). La cámara del marcador encuadra a la víctima: unos bloques por detrás y por encima, mirándola.
 * Los cambios de fase los marca {@link ReplayRounds}. Multikills y rachas quedan para más adelante.</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class ReplayMarkers {
    /** Distancia de la cámara a la víctima, hacia atrás y hacia arriba (bloques). */
    private static final double CAMERA_BACK = 6;
    private static final double CAMERA_UP = 3;

    /** Causas de muerte más comunes, por el id de mensaje del daño. El resto sale con el id tal cual. */
    private static final Map<String, String> CAUSES = Map.ofEntries(
            Map.entry("fall", "caída"),
            Map.entry("lava", "lava"),
            Map.entry("inFire", "fuego"),
            Map.entry("onFire", "fuego"),
            Map.entry("hotFloor", "fuego"),
            Map.entry("drown", "ahogado"),
            Map.entry("explosion", "explosión"),
            Map.entry("explosion.player", "explosión"),
            Map.entry("fireworks", "fuegos artificiales"),
            Map.entry("outOfWorld", "vacío"),
            Map.entry("genericKill", "/kill"),
            Map.entry("starve", "hambre"),
            Map.entry("inWall", "asfixia"),
            Map.entry("cactus", "cactus"),
            Map.entry("magic", "magia"),
            Map.entry("indirectMagic", "magia"),
            Map.entry("wither", "wither"),
            Map.entry("freeze", "congelado"),
            Map.entry("lightningBolt", "rayo"),
            Map.entry("fallingBlock", "bloque"),
            Map.entry("anvil", "yunque"),
            Map.entry("flyIntoWall", "contra la pared"),
            Map.entry("outsideBorder", "borde del mundo"),
            Map.entry("arrow", "flecha"),
            Map.entry("trident", "tridente"),
            Map.entry("generic", "desconocida"));

    private ReplayMarkers() {
    }

    /** Prioridad mínima y sin cancelados: solo se marca si nadie (otro mod) ha evitado la muerte. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        ZoneRecorder zone = ZoneRecorder.active();
        if (zone == null || victim.level().dimension() != Level.OVERWORLD
                || !zone.zone().containsBlock(victim.getX(), victim.getZ())) {
            return;
        }
        MarkerType type = playerKiller(victim, event.getSource()) != null ? MarkerType.KILL : MarkerType.PVE;
        markKill(zone, victim, type, markerName(victim, event.getSource()));
    }

    /** El jugador que se lleva la kill (directa o indirecta), o {@code null} si es PvE. */
    static ServerPlayer playerKiller(ServerPlayer victim, DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer killer && killer != victim) {
            return killer;
        }
        return victim.getKillCredit() instanceof ServerPlayer killer && killer != victim ? killer : null;
    }

    static String markerName(ServerPlayer victim, DamageSource source) {
        String name = victim.getGameProfile().getName();
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer killer && killer != victim) {
            return killer.getGameProfile().getName() + " → " + name;
        }
        // Muerte indirecta (caída tras un golpe, lava...): Minecraft da la kill al último que pegó.
        LivingEntity credit = victim.getKillCredit();
        if (credit instanceof ServerPlayer killer && killer != victim) {
            return killer.getGameProfile().getName() + " → " + name + " (" + cause(source) + ")";
        }
        if (attacker != null && attacker != victim) {
            return name + " murió (" + attacker.getName().getString() + ")";
        }
        return name + " murió (" + cause(source) + ")";
    }

    private static String cause(DamageSource source) {
        String id = source.getMsgId();
        return CAUSES.getOrDefault(id, id);
    }

    /** Añade el marcador con la cámara detrás y encima de la víctima, mirándola. */
    private static void markKill(ZoneRecorder zone, ServerPlayer victim, MarkerType type, String name) {
        float yaw = victim.getYRot();
        double rad = Math.toRadians(yaw);
        // Vector «hacia delante» de Minecraft: x = -sin(yaw), z = cos(yaw).
        double x = victim.getX() + Math.sin(rad) * CAMERA_BACK;
        double z = victim.getZ() - Math.cos(rad) * CAMERA_BACK;
        double y = victim.getY() + CAMERA_UP;
        // Apunta al pecho de la víctima (≈1 bloque sobre los pies).
        float pitch = (float) Math.toDegrees(Math.atan2(CAMERA_UP - 1, CAMERA_BACK));
        zone.addMarker(type, name, x, y, z, Mth.wrapDegrees(yaw), pitch);
    }
}
