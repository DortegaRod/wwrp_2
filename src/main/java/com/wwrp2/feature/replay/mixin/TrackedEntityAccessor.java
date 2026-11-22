package com.wwrp2.feature.replay.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Da acceso a la entidad de un {@code ChunkMap.TrackedEntity} (clase interna no pública). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Accessor("entity")
    Entity wwrp2$getEntity();

    @Accessor("serverEntity")
    ServerEntity wwrp2$getServerEntity();
}
