package com.wwrp2.feature.replay.mixin;

import com.wwrp2.feature.replay.ZoneRecorder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Cambios de bloques, luz y block entities de los chunks de la zona.
 *
 * <p>Vanilla solo prepara estos paquetes si hay jugadores viendo el chunk. Dentro de la zona se
 * preparan siempre (aunque no se envíen a nadie) para que la grabación no tenga huecos.</p>
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Shadow
    @Final
    ChunkPos pos;

    @Unique
    private boolean wwrp2$recorded;

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void wwrp2$checkZone(LevelChunk chunk, CallbackInfo ci) {
        this.wwrp2$recorded = ZoneRecorder.records(chunk.getLevel(), this.pos.x, this.pos.z);
    }

    @Redirect(method = "broadcastChanges",
            at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z", remap = false))
    private boolean wwrp2$hasViewers(List<?> players) {
        return players.isEmpty() && !this.wwrp2$recorded;
    }

    @Inject(method = "broadcast", at = @At("HEAD"))
    private void wwrp2$record(List<ServerPlayer> players, Packet<?> packet, CallbackInfo ci) {
        if (this.wwrp2$recorded) {
            ZoneRecorder.recordChunkPacket(packet);
        }
    }
}
