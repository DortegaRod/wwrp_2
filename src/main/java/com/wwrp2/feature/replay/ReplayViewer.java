package com.wwrp2.feature.replay;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraftforge.network.NetworkDirection;
import java.util.Comparator;
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
    private static final String HELP = "/replay menu, pausa, velocidad <x>, ir <m:ss>, atras [s], adelante [s], "
            + "highlights, highlight <n>, jugadores, jugador <nombre>, seguir <nombre>, dejar, reiniciar, salir.";

    private static final int ID_PLAYER_INFO_UPDATE = packetId(new ClientboundPlayerInfoUpdatePacket(
            EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class), List.of()));
    private static final int ID_PLAYER_INFO_REMOVE = packetId(new ClientboundPlayerInfoRemovePacket(List.of()));
    /** Rayas de la línea de tiempo: con 2 px cada una, ocupa lo mismo que la barra (182 px). */
    private static final int TIMELINE_SLOTS = 91;
    private static final double EYE_HEIGHT = 1.62;
    private static final int ID_MOVE_POS = packetId(new ClientboundMoveEntityPacket.Pos(0, (short) 0, (short) 0, (short) 0, false));
    private static final int ID_MOVE_POS_ROT = packetId(new ClientboundMoveEntityPacket.PosRot(0, (short) 0, (short) 0, (short) 0,
            (byte) 0, (byte) 0, false));
    private static final int ID_MOVE_ROT = packetId(new ClientboundMoveEntityPacket.Rot(0, (byte) 0, (byte) 0, false));
    private static final int ID_REMOVE_ENTITIES = packetId(new ClientboundRemoveEntitiesPacket(0));
    private static final int ID_TELEPORT_ENTITY = packetId(teleportEntityPacket());
    private static final int ID_ADD_PLAYER = packetId(addPlayerPacket(0, new UUID(0, 0), 0, 0, 0, (byte) 0, (byte) 0));

    private final ServerPlayer player;
    private final MinecraftServer server;
    private final Path file;
    private final String name;
    private final int duration;
    /**
     * Antes de este tiempo el terreno inicial no ha llegado entero: no se salta a antes, o la cámara
     * se quedaría en el vacío (sobre todo con el replay en pausa).
     */
    private final int terrainReady;
    /** Jugadores del replay por id de entidad, con su última posición (para llevar la cámara a ellos). */
    private final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();
    /** Nombres de los jugadores del replay por UUID (de la lista de jugadores). */
    private final Map<UUID, String> replayNames = new ConcurrentHashMap<>();
    /** Jugador al que se sigue en primera persona (null si no), y su id de entidad actual (-1 si no está). */
    private volatile String followName;
    private volatile int followId = -1;
    /** Marcadores del replay, ordenados por tiempo. */
    private final List<Highlight> highlights;
    /** Siguiente marcador que se anunciará en el chat al pasar por él. */
    private int nextHighlight;
    private long lastHudNanos;
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
    /** Tiempo al que se está saltando (-1 si no). Se avanza mandando los paquetes sin esperar. */
    private volatile int seekTarget = -1;
    /** Al terminar el salto, poner la cámara aquí (al saltar a un highlight). */
    private volatile Highlight seekCamera;
    /** Al terminar un salto que ha vuelto a empezar el replay, devolver la cámara a donde estaba. */
    private volatile boolean seekKeepCamera;
    /** Este paso del replay ha empezado de cero por un salto hacia atrás. */
    private boolean seekRestarted;
    private boolean helpShown;
    /** Última posición que ha mandado el cliente (para no perder la cámara al saltar hacia atrás). */
    private volatile double clientX, clientY, clientZ;
    private volatile float clientYaw, clientPitch;
    private volatile boolean hasClientPos;

    private ReplayViewer(ServerPlayer player, Path file, int duration, int terrainReady, List<Highlight> highlights) {
        this.player = player;
        this.server = player.server;
        this.file = file;
        String fileName = file.getFileName().toString();
        this.name = fileName.endsWith(".mcpr") ? fileName.substring(0, fileName.length() - 5) : fileName;
        this.duration = Math.max(1, duration);
        this.highlights = highlights;
        this.terrainReady = Math.min(terrainReady, this.duration);
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
        int terrainReady;
        List<Highlight> highlights;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry meta = zip.getEntry("metaData.json");
            if (zip.getEntry("recording.tmcpr") == null || meta == null) {
                return "El archivo no es un replay válido.";
            }
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(meta), StandardCharsets.UTF_8)) {
                JsonObject metaData = new Gson().fromJson(reader, JsonObject.class);
                duration = metaData.get("duration").getAsInt();
                terrainReady = metaData.has("wwrp2_terrenoListo") ? metaData.get("wwrp2_terrenoListo").getAsInt() : 0;
            }
            highlights = readHighlights(zip);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Replay: no se pudo abrir {}", file, e);
            return "No se pudo abrir el replay (mira el log del servidor).";
        }
        ReplayViewer viewer = new ReplayViewer(player, file, duration, terrainReady, highlights);
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
        send(ReplayNetwork.CHANNEL.toVanillaPacket(ReplayStatePacket.INACTIVE, NetworkDirection.PLAY_TO_CLIENT));
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
        this.nextHighlight = 0;
        this.tracked.clear();
        this.replayNames.clear();
        this.followId = -1;
        this.seekRestarted = this.seekTarget >= 0;
        try (ZipFile zip = new ZipFile(this.file.toFile());
             DataInputStream in = new DataInputStream(new BufferedInputStream(
                     zip.getInputStream(zip.getEntry("recording.tmcpr")), 1 << 16))) {
            long lastNanos = System.nanoTime();
            double clock = 0; // ms de replay transcurridos
            int index = 0;
            packets:
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
                // Esperar a que le toque, al ritmo de la velocidad y respetando la pausa. Si se está
                // saltando, no se espera hasta llegar al tiempo pedido.
                boolean flushed = false;
                while (this.running && !this.restartRequested) {
                    int seek = this.seekTarget;
                    if (seek >= 0) {
                        if (seek < this.position) {
                            this.restartRequested = true; // hacia atrás: empezar de cero y avanzar rápido
                            break packets;
                        }
                        if (time <= seek) {
                            break; // todavía no ha llegado: mandarlo ya
                        }
                        finishSeek(seek);
                        clock = seek;
                        lastNanos = System.nanoTime();
                    }
                    long now = System.nanoTime();
                    if (!this.paused) {
                        clock += (now - lastNanos) / 1_000_000.0 * this.speed;
                    }
                    lastNanos = now;
                    if (time <= clock) {
                        break;
                    }
                    if (!flushed) {
                        flush();
                        flushed = true;
                    }
                    refreshHudIfDue();
                    Thread.sleep(this.paused ? 50 : (long) Math.min(50, Math.max(1, (time - clock) / this.speed)));
                }
                if (!this.running || this.restartRequested) {
                    break;
                }
                sendRecorded(data);
                this.position = time;
                if (index == 2) {
                    afterLogin();
                }
                if (this.seekTarget < 0) {
                    announceHighlights(time);
                }
                refreshHudIfDue();
                waitWritable();
            }
        }
        flush();
        if (this.running && !this.restartRequested && this.seekTarget >= 0) {
            finishSeek(this.duration); // se ha pedido un tiempo más allá del final
        }
        if (this.running && !this.restartRequested) {
            this.position = this.duration;
            announceHighlights(this.duration);
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
        if (!this.helpShown) {
            this.helpShown = true;
            message(Component.literal("Viendo " + this.name + ". Pulsa J (o /replay menu) para abrir el menú del replay. "
                    + "Comandos: " + HELP).withStyle(ChatFormatting.GRAY));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Saltos en el tiempo

    /**
     * Pide saltar a {@code target} ms. Hacia delante se mandan los paquetes que faltan sin esperar;
     * hacia atrás se empieza el replay de cero y se avanza igual (como en Hypixel: el estado final es
     * el mismo que si se hubiera visto entero).
     *
     * @param camera     dónde poner la cámara al llegar (un highlight), o {@code null}
     * @param keepCamera si el replay vuelve a empezar, devolver la cámara a donde estaba
     */
    private void seek(int target, Highlight camera, boolean keepCamera) {
        if (camera != null) {
            unfollow(false); // el highlight trae su propia cámara
        }
        this.seekCamera = camera;
        this.seekKeepCamera = keepCamera;
        this.seekTarget = Math.max(this.terrainReady, Math.min(target, this.duration));
        if (this.thread != null && !this.thread.isAlive()) {
            // Ya había terminado: arrancar otra vez (empieza de cero y avanza rápido).
            this.restartRequested = false;
            this.running = true;
            this.thread = new Thread(this::run, "wwrp2-visor-" + this.player.getGameProfile().getName());
            this.thread.setDaemon(true);
            this.thread.start();
        }
        updateBossbar();
    }

    /** Se ha llegado al tiempo pedido: colocar la cámara y seguir a ritmo normal. */
    private void finishSeek(int target) {
        Highlight camera = this.seekCamera;
        this.seekCamera = null;
        this.seekTarget = -1;
        if (camera != null) {
            teleport(camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch());
        } else if (this.seekRestarted && this.seekKeepCamera && this.hasClientPos) {
            teleport(this.clientX, this.clientY, this.clientZ, this.clientYaw, this.clientPitch);
        }
        this.seekRestarted = false;
        // Los highlights de antes del salto no se anuncian; el del tiempo exacto sí.
        int from = camera != null ? Math.min(camera.time(), target) : target;
        int next = 0;
        while (next < this.highlights.size() && this.highlights.get(next).time() < from) {
            next++;
        }
        this.nextHighlight = next;
        this.position = Math.max(this.position, target);
        message(Component.literal("Saltado a " + time(target) + ".").withStyle(ChatFormatting.GRAY));
        updateBossbar();
    }

    private void teleport(double x, double y, double z, float yaw, float pitch) {
        send(new ClientboundPlayerPositionPacket(x, y, z, yaw, pitch, Set.of(), 0));
        flush();
    }

    /** "1:23", "83" o "83.5" (segundos) a milisegundos. */
    static int parseTime(String text) throws CommandSyntaxException {
        try {
            String[] parts = text.trim().split(":");
            double seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            if (parts.length > 3 || seconds < 0 || Double.isNaN(seconds)) {
                throw new NumberFormatException();
            }
            return (int) Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            throw new SimpleCommandExceptionType(Component.literal("Tiempo no válido: usa m:ss o segundos (p. ej. 1:23 o 83)."))
                    .create();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Menú del cliente (pantalla propia del mod)

    /** Manda al cliente el estado del replay para el menú. */
    private void sendState(boolean open) {
        List<ReplayStatePacket.Entry> entries = new ArrayList<>(this.highlights.size());
        for (Highlight h : this.highlights) {
            entries.add(new ReplayStatePacket.Entry(h.time(), h.type(), h.name()));
        }
        ReplayStatePacket state = new ReplayStatePacket(true, open, this.name, this.duration, this.position,
                this.paused, this.speed, this.seekTarget >= 0, entries, trackedNames(),
                this.followName == null ? "" : this.followName);
        send(ReplayNetwork.CHANNEL.toVanillaPacket(state, NetworkDirection.PLAY_TO_CLIENT));
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
                    if (e.profile() != null) {
                        this.replayNames.put(e.profileId(), e.profile().getName());
                    }
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
            String playerName = this.replayNames.getOrDefault(packet.getPlayerId(), packet.getPlayerId().toString());
            this.tracked.put(packet.getEntityId(), new Tracked(playerName, packet.getX(), packet.getY(), packet.getZ(),
                    packet.getyRot() * 360f / 256f));
            if (packet.getPlayerId().equals(self)) {
                send(addPlayerPacket(packet.getEntityId(), VIEWER_UUID, packet.getX(), packet.getY(), packet.getZ(),
                        packet.getyRot(), packet.getxRot()));
            } else {
                writeRaw(Unpooled.wrappedBuffer(data));
            }
            if (this.followId < 0 && playerName.equalsIgnoreCase(this.followName)) {
                this.followId = packet.getEntityId(); // ha vuelto a aparecer (reaparecer, salto atrás...)
                send(setCameraPacket(packet.getEntityId()));
            }
        } else {
            trackMovement(id, buf);
            writeRaw(Unpooled.wrappedBuffer(data));
        }
    }

    /** Sigue la posición de los jugadores del replay (para {@code /replay jugador}). */
    private void trackMovement(int id, FriendlyByteBuf buf) {
        if (this.tracked.isEmpty()) {
            return;
        }
        if (id == ID_REMOVE_ENTITIES) {
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) {
                int entityId = buf.readVarInt();
                Tracked removed = this.tracked.remove(entityId);
                if (removed != null && entityId == this.followId) {
                    // Muere o sale de la zona: la cámara se queda donde estaba y se le vuelve a seguir si reaparece.
                    this.followId = -1;
                    send(setCameraPacket(ZoneRecorder.CAMERA_ID));
                    teleport(removed.x, removed.y + EYE_HEIGHT, removed.z, removed.yaw, 0f);
                    message(Component.literal(removed.name + " ha desaparecido; se le vuelve a seguir si reaparece.")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            return;
        }
        if (id != ID_MOVE_POS && id != ID_MOVE_POS_ROT && id != ID_MOVE_ROT && id != ID_TELEPORT_ENTITY) {
            return;
        }
        Tracked t = this.tracked.get(buf.readVarInt());
        if (t == null) {
            return;
        }
        if (id == ID_TELEPORT_ENTITY) {
            t.x = buf.readDouble();
            t.y = buf.readDouble();
            t.z = buf.readDouble();
            t.yaw = buf.readByte() * 360f / 256f;
            return;
        }
        if (id != ID_MOVE_ROT) {
            t.x += buf.readShort() / 4096.0;
            t.y += buf.readShort() / 4096.0;
            t.z += buf.readShort() / 4096.0;
        }
        if (id != ID_MOVE_POS) {
            t.yaw = buf.readByte() * 360f / 256f;
        }
    }

    /** Un jugador del replay y su última posición conocida. */
    private static final class Tracked {
        final String name;
        volatile double x, y, z;
        volatile float yaw;

        Tracked(String name, double x, double y, double z, float yaw) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    /** Nombres de los jugadores que están ahora en el replay, en orden alfabético. */
    private List<String> trackedNames() {
        List<String> names = new ArrayList<>();
        for (Tracked t : this.tracked.values()) {
            names.add(t.name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Pone la cámara detrás del jugador, un poco por encima, mirándolo (como los marcadores de kill). */
    private int goToPlayer(String name) {
        unfollow(false);
        for (Tracked t : this.tracked.values()) {
            if (t.name.equalsIgnoreCase(name)) {
                double yawRad = Math.toRadians(t.yaw);
                double back = 4;
                double up = 2;
                teleport(t.x + Math.sin(yawRad) * back, t.y + up, t.z - Math.cos(yawRad) * back, t.yaw,
                        (float) Math.toDegrees(Math.atan2(up - 1.0, back)));
                return 1;
            }
        }
        message(Component.literal(name + " no está ahora en el replay.").withStyle(ChatFormatting.RED));
        return 0;
    }

    /** Ver el replay desde los ojos del jugador y seguirlo (cámara de espectador de Minecraft). */
    private int follow(String name) {
        for (Map.Entry<Integer, Tracked> entry : this.tracked.entrySet()) {
            if (entry.getValue().name.equalsIgnoreCase(name)) {
                this.followName = entry.getValue().name;
                this.followId = entry.getKey();
                send(setCameraPacket(entry.getKey()));
                message(Component.literal("Siguiendo a " + this.followName + ". Agáchate (shift) o /replay dejar para soltarlo.")
                        .withStyle(ChatFormatting.GRAY));
                updateBossbar();
                return 1;
            }
        }
        message(Component.literal(name + " no está ahora en el replay.").withStyle(ChatFormatting.RED));
        return 0;
    }

    /**
     * Deja de seguir. Con {@code placeCamera}, la cámara libre se queda en los ojos del jugador al que
     * seguía, para que no salte de golpe a otro sitio.
     */
    private void unfollow(boolean placeCamera) {
        if (this.followName == null) {
            return;
        }
        Tracked t = this.followId >= 0 ? this.tracked.get(this.followId) : null;
        this.followName = null;
        this.followId = -1;
        send(setCameraPacket(ZoneRecorder.CAMERA_ID));
        if (placeCamera && t != null) {
            teleport(t.x, t.y + EYE_HEIGHT, t.z, t.yaw, 0f);
        }
        flush();
        updateBossbar();
    }

    private static ClientboundSetCameraPacket setCameraPacket(int entityId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(entityId);
            return new ClientboundSetCameraPacket(buf);
        } finally {
            buf.release();
        }
    }

    /** Lista en el chat los jugadores del replay; clic en uno para ir con la cámara. */
    private void listPlayers() {
        List<String> names = trackedNames();
        if (names.isEmpty()) {
            message(Component.literal("Ahora mismo no hay jugadores en el replay.").withStyle(ChatFormatting.GRAY));
            return;
        }
        MutableComponent line = Component.literal("Jugadores (clic para ir): ").withStyle(ChatFormatting.GRAY);
        for (String name : names) {
            String command = "/replay jugador " + name;
            line.append(Component.literal(name).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
            line.append(Component.literal("  "));
        }
        message(line);
    }

    /** Refresca la barra y la info cada medio segundo de tiempo real (también en pausa). */
    private void refreshHudIfDue() {
        long now = System.nanoTime();
        if (now - this.lastHudNanos >= 500_000_000L) {
            this.lastHudNanos = now;
            updateBossbar();
        }
    }

    /**
     * La barra de arriba: el título es una línea de tiempo de {@link #TIMELINE_SLOTS} rayas del mismo
     * ancho que la barra, con los marcadores en su color; la barra de debajo marca por dónde va.
     * El nombre, el tiempo y la velocidad van en la barra de acción (encima de la hotbar).
     */
    private void updateBossbar() {
        int position = this.position;
        int played = (int) ((long) position * TIMELINE_SLOTS / this.duration);
        MarkerType[] slots = new MarkerType[TIMELINE_SLOTS];
        for (Highlight h : this.highlights) {
            int slot = Math.min(TIMELINE_SLOTS - 1, (int) ((long) h.time() * TIMELINE_SLOTS / this.duration));
            if (slots[slot] == null || priority(h.type()) > priority(slots[slot])) {
                slots[slot] = h.type(); // si coinciden varios en la misma raya, gana el más importante
            }
        }
        MutableComponent timeline = Component.empty();
        StringBuilder run = new StringBuilder();
        ChatFormatting runColor = null;
        for (int i = 0; i < TIMELINE_SLOTS; i++) {
            ChatFormatting color = slots[i] != null ? slots[i].color()
                    : i < played ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY;
            if (color != runColor && run.length() > 0) {
                timeline.append(Component.literal(run.toString()).withStyle(runColor));
                run.setLength(0);
            }
            runColor = color;
            run.append('|');
        }
        timeline.append(Component.literal(run.toString()).withStyle(runColor));
        this.bossbar.setName(timeline);
        this.bossbar.setProgress(Math.min(1f, (float) position / this.duration));
        send(ClientboundBossEventPacket.createUpdateNamePacket(this.bossbar));
        send(ClientboundBossEventPacket.createUpdateProgressPacket(this.bossbar));

        MutableComponent info = Component.literal(this.name + "  ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(time(position) + " / " + time(this.duration)).withStyle(ChatFormatting.YELLOW));
        if (this.speed != 1f) {
            info.append(Component.literal("  x" + this.speed).withStyle(ChatFormatting.AQUA));
        }
        if (this.paused) {
            info.append(Component.literal("  (pausa)").withStyle(ChatFormatting.GOLD));
        }
        String following = this.followName;
        if (following != null) {
            info.append(Component.literal("  POV " + following + " (shift: soltar)").withStyle(ChatFormatting.AQUA));
        }
        int seek = this.seekTarget;
        if (seek >= 0) {
            info.append(Component.literal("  saltando a " + time(seek) + "...").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        send(new ClientboundSetActionBarTextPacket(info));
        sendState(false);
        flush();
    }

    /** Qué color se ve si varios marcadores caen en la misma raya: kill > PvE > manual > fase. */
    private static int priority(MarkerType type) {
        return switch (type) {
            case KILL -> 3;
            case PVE -> 2;
            case MANUAL -> 1;
            case FASE -> 0;
        };
    }

    /** Escribe en el chat los marcadores por los que acaba de pasar la reproducción. */
    private void announceHighlights(int time) {
        while (this.nextHighlight < this.highlights.size() && this.highlights.get(this.nextHighlight).time() <= time) {
            Highlight h = this.highlights.get(this.nextHighlight++);
            message(Component.literal("[" + time(h.time()) + "] ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(h.name()).withStyle(h.type().color())));
        }
    }

    /** Lee el {@code markers.json} del replay (si no hay, sin marcadores). */
    private static List<Highlight> readHighlights(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("markers.json");
        List<Highlight> list = new ArrayList<>();
        if (entry == null) {
            return list;
        }
        try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            for (JsonElement element : new Gson().fromJson(reader, JsonArray.class)) {
                JsonObject marker = element.getAsJsonObject();
                JsonObject value = marker.getAsJsonObject("value");
                MarkerType type = MarkerType.MANUAL;
                if (value.has("wwrp2_tipo")) {
                    try {
                        type = MarkerType.valueOf(value.get("wwrp2_tipo").getAsString());
                    } catch (IllegalArgumentException ignored) {
                        // tipo desconocido: se queda como manual
                    }
                }
                String name = value.has("name") ? value.get("name").getAsString() : "";
                if (name.length() >= 2 && name.charAt(0) == ChatFormatting.PREFIX_CODE) {
                    name = name.substring(2); // quitar el código de color del principio
                }
                JsonObject pos = value.has("position") ? value.getAsJsonObject("position") : new JsonObject();
                list.add(new Highlight(marker.get("realTimestamp").getAsInt(), type, name,
                        number(pos, "x"), number(pos, "y"), number(pos, "z"),
                        (float) number(pos, "yaw"), (float) number(pos, "pitch")));
            }
        }
        list.sort(Comparator.comparingInt(Highlight::time));
        return list;
    }

    private static double number(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsDouble() : 0;
    }

    /** Un marcador del replay, con la cámara que encuadra lo que pasa. */
    private record Highlight(int time, MarkerType type, String name, double x, double y, double z,
                             float yaw, float pitch) {
    }

    // ---------------------------------------------------------------------------------------------
    // Comandos del visor (los únicos que funcionan mientras se ve un replay)

    private void registerCommands() {
        this.commands.register(LiteralArgumentBuilder.<ReplayViewer>literal("replay")
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("salir").executes(c -> {
                    this.server.execute(this::stop);
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("menu").executes(c -> {
                    sendState(true);
                    flush();
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
                    seek(0, null, false);
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("ir")
                        .then(RequiredArgumentBuilder.<ReplayViewer, String>argument("tiempo", StringArgumentType.greedyString())
                                .executes(c -> {
                                    seek(parseTime(StringArgumentType.getString(c, "tiempo")), null, true);
                                    return 1;
                                })))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("atras")
                        .executes(c -> jump(-10))
                        .then(RequiredArgumentBuilder.<ReplayViewer, Integer>argument("segundos", IntegerArgumentType.integer(1, 3600))
                                .executes(c -> jump(-IntegerArgumentType.getInteger(c, "segundos")))))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("adelante")
                        .executes(c -> jump(10))
                        .then(RequiredArgumentBuilder.<ReplayViewer, Integer>argument("segundos", IntegerArgumentType.integer(1, 3600))
                                .executes(c -> jump(IntegerArgumentType.getInteger(c, "segundos")))))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("jugadores").executes(c -> {
                    listPlayers();
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("jugador")
                        .then(RequiredArgumentBuilder.<ReplayViewer, String>argument("nombre", StringArgumentType.word())
                                .executes(c -> goToPlayer(StringArgumentType.getString(c, "nombre")))))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("seguir")
                        .then(RequiredArgumentBuilder.<ReplayViewer, String>argument("nombre", StringArgumentType.word())
                                .executes(c -> follow(StringArgumentType.getString(c, "nombre")))))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("dejar").executes(c -> {
                    unfollow(true);
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("highlights").executes(c -> {
                    listHighlights();
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<ReplayViewer>literal("highlight")
                        .then(RequiredArgumentBuilder.<ReplayViewer, Integer>argument("n", IntegerArgumentType.integer(1))
                                .executes(c -> {
                                    int n = IntegerArgumentType.getInteger(c, "n");
                                    if (n > this.highlights.size()) {
                                        message(Component.literal("Este replay tiene " + this.highlights.size()
                                                + " highlights.").withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    Highlight h = this.highlights.get(n - 1);
                                    seek(h.time(), h, false);
                                    return 1;
                                }))));
    }

    /** Avanza o retrocede unos segundos desde donde va (o desde donde iba a llegar un salto en curso). */
    private int jump(int seconds) {
        int from = this.seekTarget >= 0 ? this.seekTarget : this.position;
        seek(from + seconds * 1000, null, true);
        return 1;
    }

    /** Lista los highlights en el chat; al hacer clic en uno se salta a él. */
    private void listHighlights() {
        if (this.highlights.isEmpty()) {
            message(Component.literal("Este replay no tiene highlights.").withStyle(ChatFormatting.GRAY));
            return;
        }
        message(Component.literal("Highlights (clic para ir):").withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < this.highlights.size(); i++) {
            Highlight h = this.highlights.get(i);
            String command = "/replay highlight " + (i + 1);
            message(Component.literal((i + 1) + ". [" + time(h.time()) + "] ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(h.name()).withStyle(h.type().color()))
                    .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
        }
    }

    private void runCommand(String command) {
        try {
            this.commands.execute(command, this);
        } catch (CommandSyntaxException e) {
            message(Component.literal(e.getRawMessage().getString() + " Mientras ves un replay solo funcionan: " + HELP)
                    .withStyle(ChatFormatting.RED));
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

    private static ClientboundTeleportEntityPacket teleportEntityPacket() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(0);
            buf.writeDouble(0);
            buf.writeDouble(0);
            buf.writeDouble(0);
            buf.writeByte(0);
            buf.writeByte(0);
            buf.writeBoolean(false);
            return new ClientboundTeleportEntityPacket(buf);
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
            } else if (msg instanceof ServerboundPlayerCommandPacket command
                    && command.getAction() == ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY) {
                if (followName != null) {
                    server.execute(() -> unfollow(true));
                }
            } else if (msg instanceof ServerboundMovePlayerPacket move) {
                if (move.hasPosition()) {
                    clientX = move.getX(0);
                    clientY = move.getY(0);
                    clientZ = move.getZ(0);
                    hasClientPos = true;
                }
                if (move.hasRotation()) {
                    clientYaw = move.getYRot(0);
                    clientPitch = move.getXRot(0);
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
