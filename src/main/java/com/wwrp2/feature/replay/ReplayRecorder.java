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
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Escribe un replay en formato {@code .mcpr} (el de Replay Mod): un zip con {@code recording.tmcpr}
 * (por paquete: tiempo en ms, longitud y bytes), {@code metaData.json} y {@code markers.json}.
 *
 * <p>Los paquetes pueden llegar ya codificados (handler de Netty de un jugador, ver
 * {@link #attachTo(ServerPlayer)}) o como objetos (grabación de zona, ver {@link #recordPacket(Packet)}).
 * La escritura a disco va en un hilo aparte para no frenar el servidor ni la red.</p>
 */
public final class ReplayRecorder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HANDLER_NAME = "wwrp2_replay";
    /** Versión del formato de archivo de Replay Mod (ReplayMetaData.CURRENT_FILE_FORMAT_VERSION). */
    private static final int FILE_FORMAT_VERSION = 14;
    /** Versión de nuestro propio formato, por si cambiamos algo y hay que leer replays viejos. */
    public static final int WWRP2_FORMAT_VERSION = 1;

    /** Marcador de la línea de tiempo (tiempo en ms desde el inicio de la grabación). */
    public record Marker(int time, MarkerType type, String name, double x, double y, double z, float yaw, float pitch) {
    }

    private final String name;
    private final Path output;
    private final Path tempRecording;
    private final long startMillis = System.currentTimeMillis();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wwrp2-replay-writer");
        t.setDaemon(true);
        return t;
    });
    private final List<Marker> markers = new ArrayList<>();
    private final Set<String> players = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> warnedPackets = ConcurrentHashMap.newKeySet();
    private final DataOutputStream recording;
    private final AtomicLong rawBytes = new AtomicLong();

    private volatile boolean stopped;
    private volatile int lastTimestamp;
    private ServerPlayer attachedPlayer;

    public ReplayRecorder(Path directory, String fileName) throws IOException {
        this.name = fileName;
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

    /** Bytes sin comprimir grabados hasta ahora. */
    public long rawBytes() {
        return this.rawBytes.get();
    }

    /** Apunta un jugador en los metadatos (Replay Mod lo usa para la lista de jugadores). */
    public void addPlayer(UUID uuid) {
        this.players.add(uuid.toString());
    }

    /** Replay Mod espera que el flujo empiece en fase LOGIN con un "Login Success". */
    public void recordLogin(GameProfile profile) {
        ClientboundGameProfilePacket login = new ClientboundGameProfilePacket(profile);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(ConnectionProtocol.LOGIN.getPacketId(PacketFlow.CLIENTBOUND, login));
            login.write(buf);
            recordEncoded(buf);
        } finally {
            buf.release();
        }
    }

    /** Codifica un paquete de juego y lo graba. Los bundles se graban paquete a paquete. */
    public void recordPacket(Packet<?> packet) {
        if (this.stopped) {
            return;
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                recordPacket(sub);
            }
            return;
        }
        int id = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
        if (id < 0) {
            warnOnce(packet, null);
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(id);
            packet.write(buf);
            recordEncoded(buf);
        } catch (RuntimeException e) {
            warnOnce(packet, e);
        } finally {
            buf.release();
        }
    }

    private void warnOnce(Packet<?> packet, Exception e) {
        if (this.warnedPackets.add(packet.getClass())) {
            LOGGER.warn("Replay {}: no se puede grabar el paquete {}, se omite", this.name,
                    packet.getClass().getSimpleName(), e);
        }
    }

    /** Copia un paquete ya codificado (id + datos) y lo encola. Se puede llamar desde cualquier hilo. */
    public void recordEncoded(ByteBuf packet) {
        if (this.stopped) {
            return;
        }
        int timestamp = elapsedMillis();
        byte[] data = new byte[packet.readableBytes()];
        packet.getBytes(packet.readerIndex(), data);
        this.lastTimestamp = timestamp;
        this.rawBytes.addAndGet(data.length + 8L);
        this.writer.execute(() -> {
            try {
                this.recording.writeInt(timestamp);
                this.recording.writeInt(data.length);
                this.recording.write(data);
            } catch (IOException e) {
                LOGGER.error("No se pudo escribir un paquete del replay {}", this.name, e);
            }
        });
    }

    /**
     * Graba lo que el servidor envía a un jugador real enganchándose a su pipeline de Netty, justo
     * después del {@code encoder}. Falla si la conexión es local (anfitrión de singleplayer/LAN).
     */
    public void attachTo(ServerPlayer player) {
        ChannelPipeline pipeline = player.connection.connection.channel().pipeline();
        if (pipeline.get("encoder") == null) {
            throw new IllegalStateException("La conexión de " + player.getGameProfile().getName()
                    + " es local (anfitrión de singleplayer/LAN) y no se puede grabar");
        }
        pipeline.addBefore("encoder", HANDLER_NAME, new Handler());
        this.attachedPlayer = player;
    }

    public synchronized void addMarker(MarkerType type, String name, double x, double y, double z, float yaw, float pitch) {
        this.markers.add(new Marker(elapsedMillis(), type, name, x, y, z, yaw, pitch));
    }

    /** Copia de los marcadores, en el orden en que se añadieron (el índice es el que usan los comandos). */
    public synchronized List<Marker> markers() {
        return List.copyOf(this.markers);
    }

    /** Cambia el nombre del marcador {@code index}. Devuelve el marcador antiguo, o {@code null} si no existe. */
    public synchronized Marker renameMarker(int index, String name) {
        if (index < 0 || index >= this.markers.size()) {
            return null;
        }
        Marker old = this.markers.get(index);
        this.markers.set(index, new Marker(old.time(), old.type(), name, old.x(), old.y(), old.z(), old.yaw(), old.pitch()));
        return old;
    }

    /** Borra el marcador {@code index}. Devuelve el borrado, o {@code null} si no existe. */
    public synchronized Marker removeMarker(int index) {
        if (index < 0 || index >= this.markers.size()) {
            return null;
        }
        return this.markers.remove(index);
    }

    /** Cancela una grabación que no llegó a arrancar: cierra y borra el temporal sin crear el .mcpr. */
    public void discard() {
        this.stopped = true;
        detach();
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
        detach();

        CompletableFuture<Path> done = new CompletableFuture<>();
        this.writer.execute(() -> {
            try {
                this.recording.close();
                pack();
                done.complete(this.output);
            } catch (Exception e) {
                LOGGER.error("No se pudo guardar el replay {}", this.name, e);
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

    private void detach() {
        if (this.attachedPlayer == null) {
            return;
        }
        try {
            this.attachedPlayer.connection.connection.channel().pipeline().remove(HANDLER_NAME);
        } catch (NoSuchElementException ignored) {
            // El jugador ya se había desconectado y el pipeline ya no tiene el handler.
        }
    }

    private void pack() throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(this.output))) {
            zip.putNextEntry(new ZipEntry("recording.tmcpr"));
            Files.copy(this.tempRecording, zip);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metaData.json"));
            zip.write(asciiJson(metaData()));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("markers.json"));
            zip.write(asciiJson(markersJson()));
            zip.closeEntry();
        }
    }

    /**
     * El JSON solo con ASCII (lo demás como escapes unicode de JSON). Replay Mod lee estos archivos con la
     * codificación por defecto del sistema, que en Windows con Java 17 no es UTF-8: sin esto, las
     * tildes y el {@code §} de los colores saldrían mal.
     */
    private static byte[] asciiJson(String json) {
        StringBuilder out = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch < 128) {
                out.append(ch);
            } else {
                out.append(String.format("\\u%04x", (int) ch));
            }
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
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
        meta.addProperty("generator", "wwrp-2 replay v" + WWRP2_FORMAT_VERSION);
        meta.addProperty("selfId", -1);
        JsonArray list = new JsonArray();
        this.players.forEach(list::add);
        meta.add("players", list);
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
                // El código de color al principio hace que Replay Mod pinte el nombre de ese color.
                value.addProperty("name", m.type().color() + m.name());
            }
            value.addProperty("wwrp2_tipo", m.type().name());
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
                recordEncoded(buf);
            }
            super.write(ctx, msg, promise);
        }
    }
}
