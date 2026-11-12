package com.wwrp2.feature.replay.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Da acceso a las entidades que sigue el servidor (los valores son {@code ChunkMap.TrackedEntity}). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("entityMap")
    Int2ObjectMap<?> wwrp2$getEntityMap();
}
