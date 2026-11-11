package com.wwrp2.feature.replay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.SharedConstants;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Graba lo que el servidor envía a un jugador y lo guarda como un {@code .mcpr} (formato de Replay Mod).
 *
 * <p>Se engancha al pipeline de Netty del jugador con un handler de salida colocado justo después del
 * {@code encoder}: así recibe cada paquete ya codificado (id + datos), que es lo que guarda el replay.
 * La escritura a disco va en un hilo aparte para no frenar la red.</p>
 */
public final class ReplayRecorder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HANDLER_NAME = "wwrp2_replay";
    /** Versión del formato de archivo de Replay Mod (ReplayMetaData.CURRENT_FILE_FORMAT_VERSION). */
    private static final int FILE_FORMAT_VERSION = 14;

    /** Marcador de la línea de tiempo (tiempo en ms desde el inicio de la grabación). */
    public record Marker(int time, String name, double x, double y, double z, float yaw, float pitch) {
    }

    private final ServerPlayer player;
    private final GameProfile profile;
    private final Path output;
    private final Path tempRecording;
    private final long startMillis = System.currentTimeMillis();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wwrp2-replay-writer");
        t.setDaemon(true);
        return t;
    });
    private final List<Marker> markers = new ArrayList<>();
    private final DataOutputStream recording;

    private volatile boolean stopped;
    private volatile int lastTimestamp;

    public ReplayRecorder(ServerPlayer player, Path directory, String fileName) throws IOException {
        this.player = player;
        this.profile = player.getGameProfile();
        Files.createDirectories(directory);
        this.output = directory.resolve(fileName + ".mcpr");
        this.tempRecording = directory.resolve(fileName + ".tmcpr.tmp");
        this.recording = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(this.tempRecording)));
    }

    public Path getOutput() {
        return this.output;
    }

    public int elapsedMillis() {
        return (int) (System.currentTimeMillis() - this.startMillis);
    }

    /** Empieza a grabar: el replay arranca con el login del jugador y después los paquetes de juego. */
    public void start() {
        ChannelPipeline pipeline = this.player.connection.connection.channel().pipeline();
        if (pipeline.get("encoder") == null) {
            // El anfitrión de un mundo singleplayer/LAN no pasa por red: sus paquetes no se codifican.
            throw new IllegalStateException("La conexión de " + this.profile.getName()
                    + " es local (anfitrión de singleplayer/LAN) y no se puede grabar");
        }

        // Replay Mod espera que el flujo empiece en fase LOGIN con un "Login Success".
        ClientboundGameProfilePacket login = new ClientboundGameProfilePacket(this.profile);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(ConnectionProtocol.LOGIN.getPacketId(PacketFlow.CLIENTBOUND, login));
            login.write(buf);
            record(buf);
        } finally {
            buf.release();
        }

        pipeline.addBefore("encoder", HANDLER_NAME, new Handler());
    }

    /** Copia el paquete ya codificado y lo encola para escribirlo. Se puede llamar desde cualquier hilo. */
    private void record(ByteBuf packet) {
        if (this.stopped) {
            return;
        }
        int timestamp = elapsedMillis();
        byte[] data = new byte[packet.readableBytes()];
        packet.getBytes(packet.readerIndex(), data);
        this.lastTimestamp = timestamp;
        this.writer.execute(() -> {
            try {
                this.recording.writeInt(timestamp);
                this.recording.writeInt(data.length);
                this.recording.write(data);
            } catch (IOException e) {
                LOGGER.error("No se pudo escribir un paquete del replay de {}", this.profile.getName(), e);
            }
        });
    }

    public synchronized void addMarker(String name) {
        this.markers.add(new Marker(elapsedMillis(), name,
                this.player.getX(), this.player.getY(), this.player.getZ(),
                this.player.getYRot(), this.player.getXRot()));
    }

    /** Cancela una grabación que no llegó a arrancar: cierra y borra el temporal sin crear el .mcpr. */
    public void discard() {
        this.stopped = true;
        this.writer.execute(() -> {
            try {
                this.recording.close();
                Files.deleteIfExists(this.tempRecording);
            } catch (IOException ignored) {
                // Nada que hacer: solo era un temporal.
            }
        });
        this.writer.shutdown();
    }

    /** Deja de grabar y empaqueta el {@code .mcpr}. El futuro se completa con la ruta del archivo. */
    public CompletableFuture<Path> stop() {
        if (this.stopped) {
            return CompletableFuture.completedFuture(this.output);
        }
        this.stopped = true;

        try {
            this.player.connection.connection.channel().pipeline().remove(HANDLER_NAME);
        } catch (NoSuchElementException ignored) {
            // El jugador ya se había desconectado y el pipeline ya no tiene el handler.
        }

        CompletableFuture<Path> done = new CompletableFuture<>();
        this.writer.execute(() -> {
            try {
                this.recording.close();
                pack();
                done.complete(this.output);
            } catch (Exception e) {
                LOGGER.error("No se pudo guardar el replay de {}", this.profile.getName(), e);
                done.completeExceptionally(e);
            } finally {
                try {
                    Files.deleteIfExists(this.tempRecording);
                } catch (IOException ignored) {
                    // Si no se puede borrar el temporal no es grave.
                }
            }
        });
        this.writer.shutdown();
        return done;
    }

    private void pack() throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(this.output))) {
            zip.putNextEntry(new ZipEntry("recording.tmcpr"));
            Files.copy(this.tempRecording, zip);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metaData.json"));
            zip.write(metaData().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("markers.json"));
            zip.write(markersJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private String metaData() {
        JsonObject meta = new JsonObject();
        meta.addProperty("singleplayer", false);
        meta.addProperty("serverName", "wwrp-2");
        meta.addProperty("duration", this.lastTimestamp);
        meta.addProperty("date", this.startMillis);
        meta.addProperty("mcversion", SharedConstants.getCurrentVersion().getName());
        meta.addProperty("fileFormat", "MCPR");
        meta.addProperty("fileFormatVersion", FILE_FORMAT_VERSION);
        meta.addProperty("protocol", SharedConstants.getProtocolVersion());
        meta.addProperty("generator", "wwrp-2 replay");
        meta.addProperty("selfId", -1);
        JsonArray players = new JsonArray();
        players.add(this.profile.getId().toString());
        meta.add("players", players);
        return new Gson().toJson(meta);
    }

    private synchronized String markersJson() {
        JsonArray root = new JsonArray();
        for (Marker m : this.markers) {
            JsonObject position = new JsonObject();
            position.addProperty("x", m.x());
            position.addProperty("y", m.y());
            position.addProperty("z", m.z());
            position.addProperty("yaw", m.yaw());
            position.addProperty("pitch", m.pitch());
            position.addProperty("roll", 0f);
            JsonObject value = new JsonObject();
            if (m.name() != null) {
                value.addProperty("name", m.name());
            }
            value.add("position", position);
            JsonObject entry = new JsonObject();
            entry.addProperty("realTimestamp", m.time());
            entry.add("value", value);
            root.add(entry);
        }
        return new Gson().toJson(root);
    }

    /** Ve cada paquete codificado que sale hacia el jugador, lo copia y lo deja pasar sin tocarlo. */
    private final class Handler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ByteBuf buf) {
                record(buf);
            }
            super.write(ctx, msg, promise);
        }
    }
}
