package com.wwrp2.feature.replay;

import com.mojang.logging.LogUtils;
import com.wwrp2.Wwrp2;
import com.wwrp2.feature.rondas.RoundPhase;
import com.wwrp2.feature.rondas.RoundPhaseChangeEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Fase 2: graba cada ronda sola. Empieza al pasar a Preparación y para cuando la ronda termina
 * (jugada entera o cortada con {@code /ronda parar}). El archivo se llama
 * {@code ronda-<N>_<fecha>.mcpr}.
 *
 * <p>La grabación de la ronda manda: si al empezar la ronda había una grabación manual de zona,
 * se para (se guarda) y empieza la de la ronda. Si durante la ronda un admin hace
 * {@code /replay stop}, se respeta y al terminar la ronda no se hace nada.</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class ReplayRounds {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** La grabación que arrancó la ronda en curso, para no parar al terminar una que sea manual. */
    private static ZoneRecorder roundRecording;

    private ReplayRounds() {
    }

    @SubscribeEvent
    public static void onRoundPhaseChange(RoundPhaseChangeEvent event) {
        MinecraftServer server = event.getServer();
        if (event.getRoundPhase() == RoundPhase.PREPARATION) {
            startRound(server, event.getRoundNumber());
        } else if (event.isRoundOver()) {
            stopRound(server, event.getRoundNumber(), event.wasStopped());
        }
    }

    private static void startRound(MinecraftServer server, int round) {
        ReplayRecorder manual = ZoneRecorder.stop();
        if (manual != null) {
            tellAdmins(server, "Replay: paro la grabación manual (" + manual.getOutput().getFileName()
                    + ") para grabar la ronda " + round + ".", ChatFormatting.YELLOW);
        }
        String error = ZoneRecorder.start(server, "ronda-" + round + "_" + ReplayManager.stamp());
        if (error != null) {
            roundRecording = null;
            LOGGER.warn("Replay: no se pudo grabar la ronda {}: {}", round, error);
            tellAdmins(server, "Replay: la ronda " + round + " NO se está grabando. " + error, ChatFormatting.RED);
            return;
        }
        roundRecording = ZoneRecorder.active();
        tellAdmins(server, "Replay: grabando la ronda " + round + ".", ChatFormatting.GRAY);
    }

    private static void stopRound(MinecraftServer server, int round, boolean cancelled) {
        ZoneRecorder recording = roundRecording;
        roundRecording = null;
        if (recording == null || ZoneRecorder.active() != recording) {
            return; // No se grababa, o un admin la paró o la cambió por una manual.
        }
        ReplayRecorder recorder = ZoneRecorder.stop();
        if (recorder != null) {
            tellAdmins(server, "Replay: ronda " + round + (cancelled ? " (cancelada)" : "")
                    + " guardándose en " + recorder.getOutput().getFileName() + ".", ChatFormatting.GRAY);
        }
    }

    /** Los avisos solo los ven los OP (permiso 2), igual que los comandos {@code /replay}. */
    private static void tellAdmins(MinecraftServer server, String text, ChatFormatting color) {
        Component message = Component.literal(text).withStyle(color);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(message);
            }
        }
    }
}
