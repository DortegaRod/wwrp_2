package com.wwrp2.feature.replay.mixin;

import com.wwrp2.feature.replay.ZoneRecorder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/** Lo que el servidor manda a todos, a una dimensión o alrededor de un punto (sonidos, efectos...). */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void wwrp2$all(Packet<?> packet, CallbackInfo ci) {
        ZoneRecorder.recordGlobal(packet);
    }

    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V",
            at = @At("HEAD"))
    private void wwrp2$dimension(Packet<?> packet, ResourceKey<Level> dimension, CallbackInfo ci) {
        ZoneRecorder.recordDimension(dimension, packet);
    }

    // También se graba lo que no se manda a "except": quien causó el sonido ya lo oyó en su cliente.
    @Inject(method = "broadcast", at = @At("HEAD"))
    private void wwrp2$near(Player except, double x, double y, double z, double radius,
                            ResourceKey<Level> dimension, Packet<?> packet, CallbackInfo ci) {
        ZoneRecorder.recordAt(dimension, x, z, packet);
    }

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",
            at = @At("HEAD"))
    private void wwrp2$systemMessage(Component message, Function<ServerPlayer, Component> perPlayer,
                                     boolean overlay, CallbackInfo ci) {
        ZoneRecorder.recordSystemMessage(message, overlay);
    }
}
