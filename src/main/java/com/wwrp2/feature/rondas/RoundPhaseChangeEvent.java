package com.wwrp2.feature.rondas;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;

/**
 * Se lanza en el bus de Forge, en el servidor, cada vez que la ronda cambia de fase. Se escucha
 * como cualquier otro evento, con {@code @SubscribeEvent} en una clase con
 * {@code @Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)}.
 *
 * <p>Mientras se reparte este evento no se puede volver a cambiar de fase: si una feature
 * necesita saltar otra fase al recibirlo, que lo haga en el siguiente tick.</p>
 */
public class RoundPhaseChangeEvent extends Event {
    private final MinecraftServer server;
    private final RoundPhase previousPhase;
    private final RoundPhase phase;
    private final int roundNumber;
    private final boolean stopped;

    public RoundPhaseChangeEvent(MinecraftServer server, RoundPhase previousPhase, RoundPhase phase,
                                 int roundNumber, boolean stopped) {
        this.server = server;
        this.previousPhase = previousPhase;
        this.phase = phase;
        this.roundNumber = roundNumber;
        this.stopped = stopped;
    }

    public MinecraftServer getServer() {
        return server;
    }

    /** La fase de la que se viene. */
    public RoundPhase getPreviousRoundPhase() {
        return previousPhase;
    }

    /**
     * La fase en la que se está ahora. No se llama {@code getPhase()} porque {@link Event} ya
     * tiene uno con otro significado (la prioridad con la que se está repartiendo el evento).
     */
    public RoundPhase getRoundPhase() {
        return phase;
    }

    /** Número de la ronda; si la ronda acaba de terminar, el de la que ha terminado. */
    public int getRoundNumber() {
        return roundNumber;
    }

    /** true si la ronda acaba de terminar, tanto si se ha jugado entera como si se ha parado. */
    public boolean isRoundOver() {
        return phase == RoundPhase.WAITING;
    }

    /** true si la ronda se ha cortado con {@code /ronda parar} o {@link RoundManager#stop} antes de acabar. */
    public boolean wasStopped() {
        return stopped;
    }
}
