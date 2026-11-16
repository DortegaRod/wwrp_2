package com.wwrp2.feature.replay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.RootCommandNode;
import com.mojang.logging.LogUtils;
import com.wwrp2.feature.replay.mixin.PlayerListAccessor;
import com.wwrp2.feature.rondas.RoundManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fase 4: ver un replay dentro del propio servidor, sin mods (diseño de ServerReplay, MIT).
 *
 * <p>Mientras alguien ve un replay:</p>
 * <ul>
 *   <li>Sale del mundo real: se le quita del nivel y de la lista de jugadores (los demás lo ven
 *       como desconectado). Su cuerpo no se queda en el mapa.</li>
 *   <li>Un handler de Netty en su conexión tira todo lo que el servidor real le manda (menos el
 *       keep-alive) y todo lo que su cliente manda (movimiento, clics...), salvo los comandos del
 *       visor.</li>
 *   <li>Un hilo aparte lee el {@code .mcpr} y le manda los paquetes ya codificados con sus tiempos,
 *       directamente por debajo del {@code encoder}.</li>
 * </ul>
 * <p>Al salir se le manda un "Join Game" del mundo real y todo lo que recibe un jugador al entrar,
 * y vuelve al sitio exacto donde estaba.</p>
 */
public final class ReplayViewer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, ReplayViewer> VIEWERS = new ConcurrentHashMap<>();
    private static final String HANDLER_NAME = "wwrp2_replay_visor";
    /**
     * Si el espectador sale en el replay, se le cambia el UUID: el cliente no puede tener otra
     * entidad con el mismo UUID que su propio jugador.
     */
    private static final UUID VIEWER_UUID = UUIDUtil.createOfflinePlayerUUID("-wwrp2-visor-");
    /** Canales de Forge/vanilla que el cliente manda al recibir un "Join Game" y que hay que dejar pasar. */
    private static final Set<String> ALLOWED_CHANNELS = Set.of("minecraft:register", "minecraft:unregister", "minecraft:brand");
    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 8f;

    private static final int ID_PLAYER_INFO_UPDATE = packetId(new ClientboundPlayerInfoUpdatePacket(
            EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class), List.of()));
    private static final int ID_PLAYER_INFO_REMOVE = packetId(new ClientboundPlayerInfoRemovePacket(List.of()));
    private static final int ID_ADD_PLAYER = packetId(addPlayerPacket(0, new UUID(0, 0), 0, 0, 0, (byte) 0, (byte) 0));

    private final ServerPlayer player;
    private final MinecraftServer server;
    private final Path file;
    private final String name;
    private final int duration;
    private final ServerBossEvent bossbar;
    private final CommandDispatcher<ReplayViewer> commands = new CommandDispatcher<>();
    /** UUIDs que el replay ha metido en la lista de jugadores del cliente, para quitarlos al salir. */
    private final Set<UUID> replayPlayers = ConcurrentHashMap.newKeySet();
    private ChannelHandlerContext encoder;
    private Thread thread;

    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean restartRequested;
    private volatile float speed = 1f;
    /** Milisegundos del replay que ya se han mandado. */
    private volatile int position;

    private ReplayViewer(ServerPlayer player, Path file, int duration) {
        this.player = player;
        this.server = player.server;
        this.file = file;
        String fileName = file.getFileName().toString();
        this.name = fileName.endsWith(".mcpr") ? fileName.substring(0, fileName.length() - 5) : fileName;
        this.duration = Math.max(1, duration);
        this.bossbar = new ServerBossEvent(Component.literal(this.name), BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarOverlay.PROGRESS);
        registerCommands();
    }

    // ---------------------------------------------------------------------------------------------
    // API (hilo del servidor)

    public static ReplayViewer get(ServerPlayer player) {
        return VIEWERS.get(player.getUUID());
    }

    public static Collection<ReplayViewer> all() {
        return List.copyOf(VIEWERS.values());
    }

    /** Empieza a enseñarle el replay al jugador. Devuelve un mensaje de error o {@code null} si va bien. */
    public static String start(ServerPlayer player, Path file) {
        if (VIEWERS.containsKey(player.getUUID())) {
            return "Ya estás viendo un replay.";
        }
        if (RoundManager.isRoundRunning()) {
            return "Solo se pueden ver replays fuera de ronda.";
        }
        if (player.connection.connection.channel().pipeline().get("encoder") == null) {
            return "El anfitrión de un mundo singleplayer/LAN no puede ver replays (usa un servidor dedicado).";
        }
        int duration;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry meta = zip.getEntry("metaData.json");
            if (zip.getEntry("recording.tmcpr") == null || meta == null) {
                return "El archivo no es un replay válido.";
            }
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(meta), StandardCharsets.UTF_8)) {
                duration = new Gson().fromJson(reader, JsonObject.class).get("duration").getAsInt();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Replay: no se pudo abrir {}", file, e);
            return "No se pudo abrir el replay (mira el log del servidor).";
        }
        ReplayViewer viewer = new ReplayViewer(player, file, duration);
        VIEWERS.put(player.getUUID(), viewer);
        viewer.enter();
        LOGGER.info("Replay: {} está viendo {}", player.getGameProfile().getName(), file.getFileName());
        return null;
    }

    /** Devuelve a todos los espectadores al mundo real (p. ej. porque empieza una ronda). */
    public static void stopAll(Component reason) {
        for (ReplayViewer viewer : all()) {
            viewer.stop();
            viewer.player.sendSystemMessage(reason);
        }
    }

    /**
     * El espectador se ha desconectado: solo se limpia. No hace falta volver a meterlo en el nivel:
     * {@code PlayerList.remove} lo guarda igual (con la posición de antes de ver el replay).
     */
    public static void onLogout(ServerPlayer player) {
        ReplayViewer viewer = VIEWERS.remove(player.getUUID());
        if (viewer != null) {
            viewer.stopThread();
            viewer.removeHandler();
        }
    }

    /** Al parar el servidor: los jugadores se van a desconectar igualmente. */
    public static void abortAll() {
        for (ReplayViewer viewer : all()) {
            onLogout(viewer.player);
        }
    }

    public String name() {
        return this.name;
    }

    public ServerPlayer player() {
        return this.player;
    }

    public int position() {
        return this.position;
    }

    // ---------------------------------------------------------------------------------------------
    // Entrar y salir

    private void enter() {
        PlayerList list = this.server.getPlayerList();
        // Fuera del mundo real: los demás lo ven irse del TAB y desaparecer.
        list.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(this.player.getUUID())));
        this.player.serverLevel().removePlayerImmediately(this.player, Entity.RemovalReason.CHANGED_DIMENSION);
        // Como en un cambio de dimensión: quitado del nivel pero "vivo" (las capabilities de Forge siguen).
        this.player.revive();
        players().remove(this.player);

        ChannelPipeline pipeline = this.player.connection.connection.channel().pipeline();
        pipeline.addBefore("packet_handler", HANDLER_NAME, new Filter());
        this.encoder = pipeline.context("encoder");

        // Limpiar lo que el cliente tiene del mundo real que el "Join Game" del replay no borra.
        List<UUID> realPlayers = new ArrayList<>();
        for (ServerPlayer other : list.getPlayers()) {
            realPlayers.add(other.getUUID());
        }
        realPlayers.add(this.player.getUUID());
        send(new ClientboundPlayerInfoRemovePacket(realPlayers));
        for (CustomBossEvent event : this.server.getCustomBossEvents().getEvents()) {
            if (event.getPlayers().contains(this.player)) {
                send(ClientboundBossEventPacket.createRemovePacket(event.getId()));
            }
        }
        flush();

        this.thread = new Thread(this::run, "wwrp2-visor-" + this.player.getGameProfile().getName());
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Sale del replay y vuelve al mundo real, al mismo sitio. */
    public void stop() {
        if (VIEWERS.remove(this.player.getUUID()) == null) {
            return;
        }
        stopThread();
        // Quitar lo del replay que el "Join Game" del mundo real no borra.
        send(new ClientboundPlayerInfoRemovePacket(new ArrayList<>(this.replayPlayers)));
        send(ClientboundBossEventPacket.createRemovePacket(this.bossbar.getId()));
        flush();
        removeHandler();
        rejoin();
        LOGGER.info("Replay: {} ha vuelto del replay {}", this.player.getGameProfile().getName(), this.name);
    }

    private void stopThread() {
        this.running = false;
        Thread t = this.thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void removeHandler() {
        try {
            this.player.connection.connection.channel().pipeline().remove(HANDLER_NAME);
        } catch (RuntimeException ignored) {
            // La conexión ya estaba cerrada.
        }
    }

    /** Lo mismo que {@code PlayerList.placeNewPlayer} manda a un jugador que entra, sin crear otro jugador. */
    private void rejoin() {
        ServerPlayer p = this.player;
        ServerLevel level = p.serverLevel();
        PlayerList list = this.server.getPlayerList();
        GameRules rules = level.getGameRules();
        ServerGamePacketListenerImpl conn = p.connection;

        conn.send(new ClientboundLoginPacket(p.getId(), level.getLevelData().isHardcore(),
                p.gameMode.getGameModeForPlayer(), p.gameMode.getPreviousGameModeForPlayer(),
                this.server.levelKeys(), ((PlayerListAccessor) list).wwrp2$getSynchronizedRegistries(),
                level.dimensionTypeId(), level.dimension(), BiomeManager.obfuscateSeed(level.getSeed()),
                list.getMaxPlayers(), list.getViewDistance(), list.getSimulationDistance(),
                rules.getBoolean(GameRules.RULE_REDUCEDDEBUGINFO), !rules.getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN),
                level.isDebug(), level.isFlat(), p.getLastDeathLocation(), p.getPortalCooldown()));
        conn.send(respawnInPlace(p.gameMode.getGameModeForPlayer(), p.gameMode.getPreviousGameModeForPlayer()));
        conn.send(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(level.enabledFeatures())));
        conn.send(new ClientboundChangeDifficultyPacket(level.getDifficulty(), level.getLevelData().isDifficultyLocked()));
        conn.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
        conn.send(new ClientboundSetCarriedItemPacket(p.getInventory().selected));
        MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(list, p));
        conn.send(new ClientboundUpdateRecipesPacket(this.server.getRecipeManager().getRecipes()));
        conn.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(this.server.registries())));
        list.sendPlayerPermissionLevel(p); // también manda el árbol de comandos real
        p.getStats().markAllDirty();
        p.getRecipeBook().sendInitialRecipeBook(p);
        ZoneRecorder.scoreboardPackets(this.server.getScoreboard()).forEach(conn::send);
        conn.teleport(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());

        players().add(p);
        conn.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(list.getPlayers()));
        list.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p)));
        list.sendLevelInfo(p, level);
        level.addNewPlayer(p);
        for (CustomBossEvent event : this.server.getCustomBossEvents().getEvents()) {
            if (event.getPlayers().contains(p) && event.isVisible()) {
                conn.send(ClientboundBossEventPacket.createAddPacket(event));
            }
        }
        for (MobEffectInstance effect : p.getActiveEffects()) {
            conn.send(new ClientboundUpdateMobEffectPacket(p.getId(), effect));
        }
        list.sendAllPlayerInfo(p); // inventario, vida y experiencia
    }

    /**
     * El cliente, al recibir un login estando ya conectado, crea un mundo nuevo pero reutiliza el
     * jugador local, que sigue apuntando al mundo anterior (choques con bloques invisibles, no poder
     * golpear a nadie). Un respawn a la misma dimensión crea un jugador local nuevo en el mundo actual.
     */
    private ClientboundRespawnPacket respawnInPlace(GameType mode, GameType previous) {
        ServerLevel level = this.player.serverLevel();
        return new ClientboundRespawnPacket(level.dimensionTypeId(), level.dimension(),
                BiomeManager.obfuscateSeed(level.getSeed()), mode, previous, level.isDebug(), level.isFlat(),
                ClientboundRespawnPacket.KEEP_ALL_DATA, this.player.getLastDeathLocation(),
                this.player.getPortalCooldown());
    }

    private List<ServerPlayer> players() {
        return ((PlayerListAccessor) this.server.getPlayerList()).wwrp2$getPlayers();
    }

    // ---------------------------------------------------------------------------------------------
    // Reproducción (hilo propio)

    private void run() {
        try {
            do {
                this.restartRequested = false;
                stream();
            } while (this.running && this.restartRequested);
        } catch (InterruptedException e) {
            // Se ha parado el visor.
        } catch (Exception e) {
            LOGGER.error("Replay: fallo reproduciendo {} para {}", this.name, this.player.getGameProfile().getName(), e);
            message(Component.literal("Fallo al reproducir el replay. Usa /replay salir.").withStyle(ChatFormatting.RED));
        }
    }

    private void stream() throws IOException, InterruptedException {
        if (!this.replayPlayers.isEmpty()) {
            send(new ClientboundPlayerInfoRemovePacket(new ArrayList<>(this.replayPlayers)));
            this.replayPlayers.clear();
        }
        this.position = 0;
        try (ZipFile zip = new ZipFile(this.file.toFile());
             DataInputStream in = new DataInputStream(new BufferedInputStream(
                     zip.getInputStream(zip.getEntry("recording.tmcpr")), 1 << 16))) {
            long lastNanos = System.nanoTime();
            double clock = 0; // ms de replay transcurridos
            int index = 0;
            int lastBossbar = -1000;
            while (this.running && !this.restartRequested) {
                int time;
                try {
                    time = in.readInt();
                } catch (EOFException end) {
                    break;
                }
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                if (index++ == 0) {
                    continue; // "Login Success": es de la fase de login, no se puede mandar en juego.
                }
                // Esperar a que le toque, al ritmo de la velocidad y respetando la pausa.
                if (time > clock) {
                    flush();
                    while (time > clock && this.running && !this.restartRequested) {
                        long now = System.nanoTime();
                        if (!this.paused) {
                            clock += (now - lastNanos) / 1_000_000.0 * this.speed;
                        }
                        lastNanos = now;
                        if (time > clock) {
                            Thread.sleep(this.paused ? 50 : (long) Math.min(50, Math.max(1, (time - clock) / this.speed)));
                        }
                    }
                    if (!this.running || this.restartRequested) {
                        break;
                    }
                }
                sendRecorded(data);
                this.position = time;
                if (index == 2) {
                    afterLogin();
                }
                if (time - lastBossbar >= 1000) {
                    lastBossbar = time;
                    updateBossbar();
                }
                waitWritable();
            }
        }
        flush();
        if (this.running && !this.restartRequested) {
            this.position = this.duration;
            updateBossbar();
            message(Component.literal("Fin del replay. /replay reiniciar para verlo otra vez o /replay salir para volver.")
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** Tras el "Join Game" del replay (que lo resetea todo en el cliente): barra, comandos y ayuda. */
    private void afterLogin() {
        send(respawnInPlace(GameType.SPECTATOR, null));
        send(ClientboundBossEventPacket.createAddPacket(this.bossbar));
        @SuppressWarnings({"unchecked", "rawtypes"})
        RootCommandNode<net.minecraft.commands.SharedSuggestionProvider> root = (RootCommandNode) this.commands.getRoot();
        send(new ClientboundCommandsPacket(root));
        message(Component.literal("Viendo " + this.name + ". Comandos: /replay pausa, /replay velocidad <x>, "
                + "/replay reiniciar, /replay salir.").withStyle(ChatFormatting.GRAY));
    }

    /** Manda un paquete grabado. Casi todos van tal cual; los que llevan el UUID del espectador se cambian. */
    private void sendRecorded(byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        int id = buf.readVarInt();
        UUID self = this.player.getUUID();
        if (id == ID_PLAYER_INFO_UPDATE) {
            ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(buf);
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries = packet.entries();
            for (int i = 0; i < entries.size(); i++) {
                ClientboundPlayerInfoUpdatePacket.Entry e = entries.get(i);
                if (e.profileId().equals(self)) {
                    entries.set(i, new ClientboundPlayerInfoUpdatePacket.Entry(VIEWER_UUID, e.profile(), e.listed(),
                            e.latency(), e.gameMode(), e.displayName(), e.chatSession()));
                }
                if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                    this.replayPlayers.add(entries.get(i).profileId());
                }
            }
            send(packet);
        } else if (id == ID_PLAYER_INFO_REMOVE) {
            ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(buf);
            List<UUID> ids = new ArrayList<>();
            for (UUID uuid : packet.profileIds()) {
                ids.add(uuid.equals(self) ? VIEWER_UUID : uuid);
            }
            this.replayPlayers.removeAll(ids);
            send(new ClientboundPlayerInfoRemovePacket(ids));
        } else if (id == ID_ADD_PLAYER) {
            ClientboundAddPlayerPacket packet = new ClientboundAddPlayerPacket(buf);
            if (packet.getPlayerId().equals(self)) {
                send(addPlayerPacket(packet.getEntityId(), VIEWER_UUID, packet.getX(), packet.getY(), packet.getZ(),
                        packet.getyRot(), packet.getxRot()));
            } else {
                writeRaw(Unpooled.wrappedBuffer(data));
            }
        } else {
            writeRaw(Unpooled.wrappedBuffer(data));
        }
    }

    private void updateBossbar() {
        MutableComponent title = Component.literal(this.name + " ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(time(this.position) + " / " + time(this.duration)).withStyle(ChatFormatting.YELLOW));
        if (this.speed != 1f) {
            title.append(Component.literal(" x" + this.speed).withStyle(ChatFormatting.AQUA));
        }
        if (this.paused) {
            title.append(Component.literal(" (pausa)").withStyle(ChatFormatting.GOLD));
        }
        this.bossbar.setName(title);
        this.bossbar.setProgress(Math.min(1f, (float) this.position / this.duration));
        send(ClientboundBossEventPacket.createUpdateNamePacket(this.bossbar));
        send(ClientboundBossEventPacket.createUpdateProgressPacket(this.bossbar));
        flush();
    }

    // ---------------------------------------------------------------------------------------------
    // Comandos del visor (los únicos que funcionan mientras se ve un replay)

    private void registerCommands() {
        this.commands.register(LiteralArgumentBuilder.<ReplayViewer>literal("replay")
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("salir").executes(c -> {
                    this.server.execute(this::stop);
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("pausa").executes(c -> {
                    this.paused = !this.paused;
                    updateBossbar();
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("velocidad")
                        .then(RequiredArgumentBuilder.<ReplayViewer, Float>argument("x", FloatArgumentType.floatArg(MIN_SPEED, MAX_SPEED))
                                .executes(c -> {
                                    this.speed = FloatArgumentType.getFloat(c, "x");
                                    updateBossbar();
                                    return 1;
                                })))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("reiniciar").executes(c -> {
                    this.paused = false;
                    this.restartRequested = true;
                    if (this.thread != null && !this.thread.isAlive()) {
                        // Ya había terminado: arrancar otra vez.
                        this.running = true;
                        this.thread = new Thread(this::run, "wwrp2-visor-" + this.player.getGameProfile().getName());
                        this.thread.setDaemon(true);
                        this.thread.start();
                    }
                    return 1;
                })));
    }

    private void runCommand(String command) {
        try {
            this.commands.execute(command, this);
        } catch (CommandSyntaxException e) {
            message(Component.literal("Mientras ves un replay solo funcionan: /replay pausa, /replay velocidad <"
                    + MIN_SPEED + "-" + MAX_SPEED + ">, /replay reiniciar y /replay salir.").withStyle(ChatFormatting.RED));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Envío directo por debajo del encoder

    private void send(Packet<?> packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(packetId(packet));
        packet.write(buf);
        writeRaw(buf);
    }

    private void message(Component text) {
        send(new ClientboundSystemChatPacket(text, false));
        flush();
    }

    private void writeRaw(ByteBuf buf) {
        ChannelHandlerContext ctx = this.encoder;
        if (ctx != null && ctx.channel().isActive()) {
            ctx.write(buf);
        } else {
            buf.release();
        }
    }

    private void flush() {
        ChannelHandlerContext ctx = this.encoder;
        if (ctx != null) {
            ctx.flush();
        }
    }

    /** Si el cliente va lento (p. ej. con el terreno inicial), esperar en vez de llenar la memoria. */
    private void waitWritable() throws InterruptedException {
        Channel channel = this.encoder.channel();
        if (!channel.isWritable()) {
            flush();
            while (!channel.isWritable() && channel.isActive() && this.running) {
                Thread.sleep(5);
            }
        }
    }

    private static int packetId(Packet<?> packet) {
        return ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
    }

    private static ClientboundAddPlayerPacket addPlayerPacket(int entityId, UUID uuid, double x, double y, double z,
                                                              byte yRot, byte xRot) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(entityId);
            buf.writeUUID(uuid);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeByte(yRot);
            buf.writeByte(xRot);
            return new ClientboundAddPlayerPacket(buf);
        } finally {
            buf.release();
        }
    }

    static String time(int millis) {
        int seconds = millis / 1000;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    /**
     * Aísla al espectador: tira lo que el servidor real le manda (menos keep-alive y desconexión) y lo
     * que manda su cliente (menos keep-alive, ajustes, canales y los comandos del visor).
     */
    private final class Filter extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundKeepAlivePacket || msg instanceof ServerboundPongPacket
                    || msg instanceof ServerboundClientInformationPacket || msg instanceof ServerboundResourcePackPacket) {
                super.channelRead(ctx, msg);
            } else if (msg instanceof ServerboundCustomPayloadPacket payload) {
                if (ALLOWED_CHANNELS.contains(payload.getIdentifier().toString())) {
                    super.channelRead(ctx, msg);
                } else {
                    payload.getData().release();
                }
            } else if (msg instanceof ServerboundChatCommandPacket command) {
                server.execute(() -> runCommand(command.command()));
            } else if (msg instanceof ServerboundChatPacket) {
                server.execute(() -> message(Component.literal("El chat no funciona mientras ves un replay.")
                        .withStyle(ChatFormatting.RED)));
            }
            // Todo lo demás (moverse, clics, inventario...) se tira.
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof Packet<?> && !(msg instanceof ClientboundKeepAlivePacket)
                    && !(msg instanceof ClientboundDisconnectPacket)) {
                promise.setSuccess(); // del mundo real: no le llega
                return;
            }
            super.write(ctx, msg, promise);
        }
    }
}
