package com.daniel.wwrp2.feature.dilles.killheal;

import com.daniel.wwrp2.Wwrp2;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KillHealing {
    private static final float MAX_HEALTH_FRACTION = 0.5F;

    private KillHealing() {
    }

    // PlayerRevive cancela la muerte inicial al derribar con prioridad HIGHEST.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }

        // La fuente conserva al dueño de un proyectil; el crédito cubre bajas indirectas.
        LivingEntity creditedKiller = event.getSource().getEntity() instanceof LivingEntity attacker
                ? attacker
                : victim.getKillCredit();

        if (!(creditedKiller instanceof ServerPlayer killer)
                || killer == victim
                || !killer.isAlive()
                || killer.isRemoved()
                || killer.isSpectator()) {
            return;
        }

        // heal() respeta la vida máxima y el evento de curación de Forge.
        killer.heal(killer.getMaxHealth() * MAX_HEALTH_FRACTION);
    }
}
