package com.wwrp2.feature.rondas;

import com.wwrp2.Wwrp2;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Máquina de estados de la ronda: guarda la fase actual, el número de ronda y cuánto lleva la
 * fase, anuncia cada cambio en el chat y lanza {@link RoundPhaseChangeEvent}.
 *
 * <p>Solo existe en el servidor y no se guarda en el mundo: al arrancar el servidor siempre se
 * empieza en {@link RoundPhase#WAITING}. Se usa siempre desde el hilo del servidor (comandos,
 * eventos del juego, ticks).</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class RoundManager {
    private static RoundPhase phase = RoundPhase.WAITING;
    private static int roundNumber;
    private static long ticksInPhase;
    private static boolean changingPhase;

    private RoundManager() {
    }

    public static RoundPhase phase() {
        return phase;
    }

    /** Número de la ronda en curso, o de la última que se jugó; 0 si todavía no ha habido ninguna. */
    public static int roundNumber() {
        return roundNumber;
    }

    /** Ticks que lleva la fase actual (20 ticks = 1 segundo). Pensado para el Timer de Partida. */
    public static long ticksInPhase() {
        return ticksInPhase;
    }

    public static boolean isRoundRunning() {
        return phase != RoundPhase.WAITING;
    }

    /** Empieza una ronda nueva en preparación. Devuelve false si ya hay una en curso. */
    public static boolean start(MinecraftServer server) {
        checkNotChangingPhase();
        if (isRoundRunning()) {
            return false;
        }
        roundNumber++;
        changePhase(server, RoundPhase.PREPARATION, false);
        return true;
    }

    /**
     * Pasa a la fase siguiente; desde finalización la ronda termina y se vuelve a espera.
     * Devuelve false si no hay ronda en curso.
     */
    public static boolean advance(MinecraftServer server) {
        checkNotChangingPhase();
        if (!isRoundRunning()) {
            return false;
        }
        changePhase(server, phase.next(), false);
        return true;
    }

    /** Corta la ronda en curso y vuelve a espera. Devuelve false si no hay ronda en curso. */
    public static boolean stop(MinecraftServer server) {
        checkNotChangingPhase();
        if (!isRoundRunning()) {
            return false;
        }
        changePhase(server, RoundPhase.WAITING, true);
        return true;
    }

    private static void checkNotChangingPhase() {
        // Un cambio de fase dentro de otro haría que cada feature viera las fases en distinto orden.
        if (changingPhase) {
            throw new IllegalStateException(
                    "No se puede cambiar de fase mientras se reparte RoundPhaseChangeEvent; hazlo en el siguiente tick");
        }
    }

    private static void changePhase(MinecraftServer server, RoundPhase next, boolean stopped) {
        RoundPhase previous = phase;
        phase = next;
        ticksInPhase = 0;

        server.getPlayerList().broadcastSystemMessage(announcement(next, stopped), false);

        changingPhase = true;
        try {
            MinecraftForge.EVENT_BUS.post(new RoundPhaseChangeEvent(server, previous, next, roundNumber, stopped));
        } finally {
            changingPhase = false;
        }
    }

    private static Component announcement(RoundPhase next, boolean stopped) {
        MutableComponent message = Component.literal("[Ronda " + roundNumber + "] ").withStyle(ChatFormatting.GOLD);
        if (next == RoundPhase.WAITING) {
            String end = stopped ? "Ronda cancelada" : "Ronda terminada";
            return message.append(Component.literal(end).withStyle(ChatFormatting.GRAY));
        }
        return message.append(Component.literal(next.displayName()).withStyle(next.color(), ChatFormatting.BOLD));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ticksInPhase++;
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        // En un solo jugador el mod sigue cargado al cambiar de mundo: cada servidor empieza de cero.
        phase = RoundPhase.WAITING;
        roundNumber = 0;
        ticksInPhase = 0;
        changingPhase = false;
    }
}
