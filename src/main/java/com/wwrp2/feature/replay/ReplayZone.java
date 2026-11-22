package com.wwrp2.feature.replay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rectángulo del Overworld que se graba, en bloques (las dos esquinas incluidas). Se guarda en la
 * carpeta del mundo, en {@code wwrp2_replay_zona.json}.
 */
public record ReplayZone(int minX, int minZ, int maxX, int maxZ) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "wwrp2_replay_zona.json";
    /**
     * Lado máximo en chunks. El cliente que ve el replay solo guarda los chunks a 32 + 3 del centro,
     * así que la zona no puede pasar de ~65 chunks; dejamos margen.
     */
    public static final int MAX_SIDE_CHUNKS = 64;

    public static ReplayZone of(int x1, int z1, int x2, int z2) {
        return new ReplayZone(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public int minChunkX() {
        return this.minX >> 4;
    }

    public int minChunkZ() {
        return this.minZ >> 4;
    }

    public int maxChunkX() {
        return this.maxX >> 4;
    }

    public int maxChunkZ() {
        return this.maxZ >> 4;
    }

    public int widthChunks() {
        return maxChunkX() - minChunkX() + 1;
    }

    public int depthChunks() {
        return maxChunkZ() - minChunkZ() + 1;
    }

    public int chunkCount() {
        return widthChunks() * depthChunks();
    }

    public ChunkPos centerChunk() {
        return new ChunkPos((minChunkX() + maxChunkX()) / 2, (minChunkZ() + maxChunkZ()) / 2);
    }

    /** Si el chunk está dentro (la zona se graba a chunks completos). */
    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= minChunkX() && chunkX <= maxChunkX() && chunkZ >= minChunkZ() && chunkZ <= maxChunkZ();
    }

    public boolean containsBlock(double x, double z) {
        return containsChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    public boolean tooBig() {
        return widthChunks() > MAX_SIDE_CHUNKS || depthChunks() > MAX_SIDE_CHUNKS;
    }

    @Override
    public String toString() {
        // Solo ASCII: la consola de Windows no muestra bien flechas ni signos de multiplicar.
        return "(" + this.minX + ", " + this.minZ + ") a (" + this.maxX + ", " + this.maxZ + "), "
                + widthChunks() + "x" + depthChunks() + " chunks";
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
    }

    public void save(MinecraftServer server) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("minX", this.minX);
        json.addProperty("minZ", this.minZ);
        json.addProperty("maxX", this.maxX);
        json.addProperty("maxZ", this.maxZ);
        Files.writeString(file(server), new Gson().toJson(json), StandardCharsets.UTF_8);
    }

    /** La zona guardada, o {@code null} si no hay ninguna o el archivo está mal. */
    public static ReplayZone load(MinecraftServer server) {
        Path path = file(server);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            JsonObject json = new Gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            return of(json.get("minX").getAsInt(), json.get("minZ").getAsInt(),
                    json.get("maxX").getAsInt(), json.get("maxZ").getAsInt());
        } catch (Exception e) {
            LOGGER.error("No se pudo leer la zona de replay de {}", path, e);
            return null;
        }
    }
}
