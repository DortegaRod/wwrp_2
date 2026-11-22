package com.wwrp2.feature.replay.mixin;

import com.wwrp2.feature.replay.ZoneRecorder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Todo lo que el servidor manda sobre una entidad (movimiento, animaciones, equipo, metadatos...).
 * El servidor lo prepara cada tick para todas las entidades activas, haya jugadores cerca o no.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow
    @Final
    Entity entity;

    @Inject(method = "broadcast", at = @At("HEAD"))
    private void wwrp2$record(Packet<?> packet, CallbackInfo ci) {
        ZoneRecorder.recordEntityPacket(this.entity, packet);
    }

    @Inject(method = "broadcastRemoved", at = @At("HEAD"))
    private void wwrp2$removed(CallbackInfo ci) {
        ZoneRecorder.onEntityRemoved(this.entity);
    }
}
