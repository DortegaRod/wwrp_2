package com.wwrp2.feature.replay;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.wwrp2.feature.replay.mixin.ChunkMapAccessor;
import com.wwrp2.feature.replay.mixin.PlayerListAccessor;
import com.wwrp2.feature.replay.mixin.TrackedEntityAccessor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Graba todo lo que pasa en una zona del Overworld, como si hubiera una cámara que lo ve todo.
 *
 * <p>No hay jugador falso: los Mixin de {@code replay.mixin} avisan aquí de cada paquete que el
 * servidor reparte (chunks, entidades, sonidos, partículas, explosiones...) y se graba si cae dentro
 * de la zona. Al empezar se genera el estado inicial: login, mundo, jugadores y todo el terreno de
 * la zona (por partes, para no dar tirones); cuando el terreno está, se empiezan a seguir entidades.</p>
 *
 * <p>Solo puede haber una grabación de zona a la vez. Todo se llama desde el hilo del servidor,
 * salvo lo que marque lo contrario.</p>
 */
public final class ZoneRecorder {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** ID de entidad de la cámara en el replay; muy alto para que no choque con entidades reales. */
    private static final int CAMERA_ID = Integer.MAX_VALUE - 1;
    private static final GameProfile CAMERA = new GameProfile(
            UUID.nameUUIDFromBytes("wwrp2:replay-camara".getBytes()), "Camara");
    /** Distancia de chunks que se anuncia al cliente del replay (el máximo de Minecraft). */
    private static final int CHUNK_RADIUS = 32;
    /** Tiempo máximo por tick dedicado a mandar terreno al empezar, en nanosegundos. */
    private static final long TERRAIN_BUDGET_NANOS = 10_000_000L;
    /**
     * Objetivo de vida que solo existe en el replay (no en la partida real): se ve como "18 ❤" bajo
     * el nombre de cada jugador. Nombre propio para no chocar con objetivos reales.
     */
    private static final Objective HEALTH = new Objective(new Scoreboard(), "wwrp2_vida", ObjectiveCriteria.DUMMY,
            Component.literal("❤").withStyle(ChatFormatting.RED), ObjectiveCriteria.RenderType.INTEGER);

    private static volatile ZoneRecorder active;

    private final ServerLevel level;
    private final ReplayZone zone;
    private final ReplayRecorder recorder;
    private final Deque<ChunkPos> pendingTerrain = new ArrayDeque<>();
    private final int totalChunks;
    private final IntSet knownEntities = new IntOpenHashSet();
    /** Última vida grabada de cada jugador (por nombre), para grabar solo los cambios. */
    private final Object2IntMap<String> lastHealth = new Object2IntOpenHashMap<>();
    private volatile boolean terrainDone;
    /** Donde empieza la cámara del replay; los marcadores sin sitio propio apuntan aquí. */
    private double cameraX, cameraY, cameraZ;

    private ZoneRecorder(ServerLevel level, ReplayZone zone, ReplayRecorder recorder) {
        this.level = level;
        this.zone = zone;
        this.recorder = recorder;
        ChunkPos center = zone.centerChunk();
        List<ChunkPos> chunks = new ArrayList<>(zone.chunkCount());
        for (int x = zone.minChunkX(); x <= zone.maxChunkX(); x++) {
            for (int z = zone.minChunkZ(); z <= zone.maxChunkZ(); z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        // Del centro hacia fuera: lo primero que se ve al abrir el replay es lo de alrededor de la cámara.
        chunks.sort(Comparator.comparingInt(c -> c.getChessboardDistance(center)));
        this.pendingTerrain.addAll(chunks);
        this.totalChunks = chunks.size();
    }

    public static ZoneRecorder active() {
        return active;
    }

    /**
     * Empieza a grabar la zona guardada. Devuelve un mensaje de error, o {@code null} si ha
     * empezado bien.
     */
    public static String start(MinecraftServer server, String fileName) {
        if (active != null) {
            return "Ya hay una grabación de zona en marcha.";
        }
        ReplayZone zone = ReplayZone.load(server);
        if (zone == null) {
            return "No hay zona definida. Usa /replay zona <esquina1> <esquina2>.";
        }
        if (zone.tooBig()) {
            return "La zona es demasiado grande (" + zone + "). Máximo " + ReplayZone.MAX_SIDE_CHUNKS
                    + " chunks por lado.";
        }
        ReplayRecorder recorder;
        try {
            recorder = new ReplayRecorder(ReplayManager.replaysDir(server), fileName);
        } catch (IOException e) {
            LOGGER.error("No se pudo crear el archivo del replay de zona", e);
            return "No se pudo crear el archivo (mira el log del servidor).";
        }
        ZoneRecorder zoneRecorder = new ZoneRecorder(server.overworld(), zone, recorder);
        try {
            zoneRecorder.recordInitialState(server);
        } catch (RuntimeException e) {
            LOGGER.error("No se pudo generar el estado inicial del replay de zona", e);
            recorder.discard();
            return "No se pudo empezar (mira el log del servidor).";
        }
        active = zoneRecorder;
        LOGGER.info("Replay de zona: grabando {} en {}", zone, recorder.getOutput());
        return null;
    }

    /** Para la grabación de zona. Devuelve el grabador (para saber dónde se guarda) o {@code null}. */
    public static ReplayRecorder stop() {
        ZoneRecorder current = active;
        if (current == null) {
            return null;
        }
        active = null;
        current.recorder.stop();
        LOGGER.info("Replay de zona: parado, guardando en {}", current.recorder.getOutput());
        return current.recorder;
    }

    public ReplayRecorder recorder() {
        return this.recorder;
    }

    public ReplayZone zone() {
        return this.zone;
    }

    /** Marcador en un sitio concreto: al saltar a él en el visor, la cámara se pone ahí. */
    public void addMarker(MarkerType type, String name, double x, double y, double z, float yaw, float pitch) {
        this.recorder.addMarker(type, name, x, y, z, yaw, pitch);
    }

    /** Marcador sin sitio propio (cambios de fase): la cámara va a la posición inicial del replay. */
    public void addMarker(MarkerType type, String name) {
        this.recorder.addMarker(type, name, this.cameraX, this.cameraY, this.cameraZ, 0f, 45f);
    }

    /** Porcentaje del terreno inicial ya grabado. */
    public int terrainPercent() {
        return 100 - (int) (100L * this.pendingTerrain.size() / Math.max(1, this.totalChunks));
    }

    public int trackedEntities() {
        return this.knownEntities.size();
    }

    // ---------------------------------------------------------------------------------------------
    // Estado inicial

    private void recordInitialState(MinecraftServer server) {
        ReplayRecorder r = this.recorder;
        ChunkPos center = this.zone.centerChunk();

        r.recordLogin(CAMERA);
        r.recordPacket(new ClientboundLoginPacket(CAMERA_ID, false, GameType.SPECTATOR, null,
                server.levelKeys(),
                ((PlayerListAccessor) server.getPlayerList()).wwrp2$getSynchronizedRegistries(),
                this.level.dimensionTypeId(), this.level.dimension(),
                BiomeManager.obfuscateSeed(this.level.getSeed()), server.getMaxPlayers(),
                CHUNK_RADIUS, CHUNK_RADIUS, false, true, this.level.isDebug(), this.level.isFlat(),
                Optional.empty(), 0));
        r.recordPacket(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));

        Abilities abilities = new Abilities();
        abilities.flying = true;
        abilities.mayfly = true;
        abilities.invulnerable = true;
        r.recordPacket(new ClientboundPlayerAbilitiesPacket(abilities));

        r.recordPacket(new ClientboundSetChunkCacheRadiusPacket(CHUNK_RADIUS));
        r.recordPacket(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
        r.recordPacket(new ClientboundSetDefaultSpawnPositionPacket(this.level.getSharedSpawnPos(),
                this.level.getSharedSpawnAngle()));

        // La cámara empieza un poco por encima del suelo en el centro de la zona.
        int x = center.getMiddleBlockX();
        int z = center.getMiddleBlockZ();
        int y = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 20;
        r.recordPacket(new ClientboundPlayerPositionPacket(x + 0.5, y, z + 0.5, 0f, 45f, Set.of(), 0));
        this.cameraX = x + 0.5;
        this.cameraY = y;
        this.cameraZ = z + 0.5;

        r.recordPacket(new ClientboundInitializeBorderPacket(this.level.getWorldBorder()));
        r.recordPacket(new ClientboundSetTimePacket(this.level.getGameTime(), this.level.getDayTime(),
                this.level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        if (this.level.isRaining()) {
            r.recordPacket(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0f));
            r.recordPacket(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                    this.level.getRainLevel(1f)));
            r.recordPacket(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                    this.level.getThunderLevel(1f)));
        }

        // Lista de jugadores (hace falta para que se vean con su skin).
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        r.recordPacket(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players));
        for (ServerPlayer player : players) {
            r.addPlayer(player.getUUID());
        }

        recordScoreboard(server.getScoreboard());
        r.recordPacket(new ClientboundSetObjectivePacket(HEALTH, ClientboundSetObjectivePacket.METHOD_ADD));
        r.recordPacket(new ClientboundSetDisplayObjectivePacket(Scoreboard.DISPLAY_SLOT_BELOW_NAME, HEALTH));
    }

    /**
     * Estado actual del scoreboard real, igual que lo recibe un jugador al entrar
     * ({@code PlayerList.updateEntireScoreboard}). Los cambios posteriores ya se graban solos porque
     * pasan por {@code PlayerList.broadcastAll}. Ojo: Minecraft solo envía los objetivos que se
     * muestran en algún sitio (lateral, TAB o bajo el nombre).
     */
    private void recordScoreboard(ServerScoreboard scoreboard) {
        scoreboardPackets(scoreboard).forEach(this.recorder::recordPacket);
    }

    /** Paquetes con el estado entero del scoreboard (también los usa el visor al devolver al jugador). */
    static List<Packet<?>> scoreboardPackets(ServerScoreboard scoreboard) {
        List<Packet<?>> packets = new ArrayList<>();
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            packets.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }
        Set<Objective> shown = new HashSet<>();
        for (int slot = 0; slot < Scoreboard.DISPLAY_SLOTS; slot++) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null && shown.add(objective)) {
                packets.addAll(scoreboard.getStartTrackingPackets(objective));
            }
        }
        return packets;
    }

    // ---------------------------------------------------------------------------------------------
    // Tick: terreno inicial y entidades que entran o salen de la zona

    /** Se llama al final de cada tick del servidor. */
    public static void tickActive() {
        ZoneRecorder current = active;
        if (current != null) {
            current.tick();
        }
    }

    private void tick() {
        if (!this.terrainDone) {
            sendTerrain();
            return;
        }
        syncEntities();
        syncHealth();
    }

    /** Graba la vida (en medios corazones, como el criterio "health" de vanilla) cuando cambia. */
    private void syncHealth() {
        for (ServerPlayer player : this.level.players()) {
            if (!this.zone.containsBlock(player.getX(), player.getZ())) {
                continue;
            }
            String name = player.getScoreboardName();
            int health = Mth.ceil(player.getHealth());
            if (!this.lastHealth.containsKey(name) || this.lastHealth.getInt(name) != health) {
                this.lastHealth.put(name, health);
                this.recorder.recordPacket(new ClientboundSetScorePacket(ServerScoreboard.Method.CHANGE,
                        HEALTH.getName(), name, health));
            }
        }
    }

    private void sendTerrain() {
        long deadline = System.nanoTime() + TERRAIN_BUDGET_NANOS;
        while (!this.pendingTerrain.isEmpty() && System.nanoTime() < deadline) {
            ChunkPos pos = this.pendingTerrain.poll();
            // Carga el chunk si hace falta (el mapa debería estar ya generado).
            LevelChunk chunk = this.level.getChunk(pos.x, pos.z);
            this.recorder.recordPacket(new ClientboundLevelChunkWithLightPacket(chunk,
                    this.level.getLightEngine(), null, null));
        }
        if (this.pendingTerrain.isEmpty()) {
            this.terrainDone = true;
            LOGGER.info("Replay de zona: terreno inicial grabado ({} chunks)", this.totalChunks);
        }
    }

    private void syncEntities() {
        IntSet inZone = new IntOpenHashSet();
        for (Object tracked : ((ChunkMapAccessor) this.level.getChunkSource().chunkMap).wwrp2$getEntityMap().values()) {
            TrackedEntityAccessor access = (TrackedEntityAccessor) tracked;
            Entity entity = access.wwrp2$getEntity();
            if (entity.isRemoved() || !this.zone.containsBlock(entity.getX(), entity.getZ())) {
                continue;
            }
            int id = entity.getId();
            inZone.add(id);
            if (this.knownEntities.add(id)) {
                // Entidad nueva en la zona: los mismos paquetes que recibe un jugador al empezar a verla.
                List<Packet<ClientGamePacketListener>> pairing = new ArrayList<>();
                access.wwrp2$getServerEntity().sendPairingData(null, pairing::add);
                pairing.forEach(this.recorder::recordPacket);
                if (entity instanceof ServerPlayer player) {
                    this.recorder.addPlayer(player.getUUID());
                }
            }
        }
        // Las que ya no están (han salido de la zona o han desaparecido).
        IntArrayList gone = new IntArrayList();
        this.knownEntities.forEach(id -> {
            if (!inZone.contains(id)) {
                gone.add(id);
            }
        });
        if (!gone.isEmpty()) {
            gone.forEach(this.knownEntities::remove);
            this.recorder.recordPacket(new ClientboundRemoveEntitiesPacket(gone));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ganchos llamados desde los Mixin

    /** Si los cambios de este chunk se graban (para {@code ChunkHolder}). */
    public static boolean records(Level level, int chunkX, int chunkZ) {
        ZoneRecorder current = active;
        return current != null && level == current.level && current.zone.containsChunk(chunkX, chunkZ);
    }

    /** Cambios de bloques, luz y block entities de un chunk que está en la zona. */
    public static void recordChunkPacket(Packet<?> packet) {
        ZoneRecorder current = active;
        if (current != null) {
            current.recorder.recordPacket(packet);
        }
    }

    /** Paquete de una entidad (movimiento, animación, equipo, metadatos...). */
    public static void recordEntityPacket(Entity entity, Packet<?> packet) {
        ZoneRecorder current = active;
        if (current != null && current.terrainDone && current.knownEntities.contains(entity.getId())) {
            current.recorder.recordPacket(packet);
        }
    }

    /** La entidad desaparece del mundo (muere, se descarga...). */
    public static void onEntityRemoved(Entity entity) {
        ZoneRecorder current = active;
        if (current != null && current.knownEntities.remove(entity.getId())) {
            current.recorder.recordPacket(new ClientboundRemoveEntitiesPacket(entity.getId()));
        }
    }

    /** Paquete para todo el servidor (lista de jugadores, etc.). Puede llegar desde otros hilos. */
    public static void recordGlobal(Packet<?> packet) {
        ZoneRecorder current = active;
        if (current != null) {
            current.recorder.recordPacket(packet);
        }
    }

    /** Paquete para todos los de una dimensión (hora, clima...). */
    public static void recordDimension(ResourceKey<Level> dimension, Packet<?> packet) {
        ZoneRecorder current = active;
        if (current != null && dimension == current.level.dimension()) {
            current.recorder.recordPacket(packet);
        }
    }

    /** Paquete en un punto concreto (sonidos, efectos, partículas, explosiones...). */
    public static void recordAt(ResourceKey<Level> dimension, double x, double z, Packet<?> packet) {
        ZoneRecorder current = active;
        if (current != null && dimension == current.level.dimension() && current.zone.containsBlock(x, z)) {
            current.recorder.recordPacket(packet);
        }
    }

    /** Mensajes del sistema (muertes, avisos...). */
    public static void recordSystemMessage(Component message, boolean overlay) {
        recordGlobal(new ClientboundSystemChatPacket(message, overlay));
    }
}
