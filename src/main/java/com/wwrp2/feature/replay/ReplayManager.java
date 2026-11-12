package com.wwrp2.feature.replay;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Grabaciones del punto de vista de jugadores concretos (lo que reciben ellos por red). */
public final class ReplayManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Map<UUID, ReplayRecorder> RECORDERS = new ConcurrentHashMap<>();

    private ReplayManager() {
    }

    /** Carpeta donde se guardan todos los replays. */
    public static Path replaysDir(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("replays");
    }

    public static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    /** Empieza a grabar al jugador. Devuelve {@code null} si ya se estaba grabando o si falla. */
    public static ReplayRecorder start(ServerPlayer player) {
        if (RECORDERS.containsKey(player.getUUID())) {
            return null;
        }
        String name = player.getGameProfile().getName() + "_" + stamp();
        ReplayRecorder recorder;
        try {
            recorder = new ReplayRecorder(replaysDir(player.server).resolve("jugadores"), name);
        } catch (IOException e) {
            LOGGER.error("No se pudo crear el archivo del replay de {}", player.getGameProfile().getName(), e);
            return null;
        }
        try {
            recorder.recordLogin(player.getGameProfile());
            recorder.addPlayer(player.getUUID());
            recorder.attachTo(player);
        } catch (RuntimeException e) {
            LOGGER.error("No se pudo empezar el replay de {}: {}", player.getGameProfile().getName(), e.getMessage());
            recorder.discard();
            return null;
        }
        RECORDERS.put(player.getUUID(), recorder);
        return recorder;
    }

    /** Para la grabación del jugador. Devuelve {@code null} si no se estaba grabando. */
    public static ReplayRecorder stop(UUID player) {
        ReplayRecorder recorder = RECORDERS.remove(player);
        if (recorder != null) {
            recorder.stop();
        }
        return recorder;
    }

    public static Collection<ReplayRecorder> all() {
        return RECORDERS.values();
    }

    public static void stopAll() {
        for (UUID id : RECORDERS.keySet()) {
            stop(id);
        }
    }
}
