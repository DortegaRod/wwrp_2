package com.wwrp2.feature.replay.mixin;

import com.wwrp2.feature.replay.ZoneRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Efectos que el servidor manda jugador a jugador: partículas, explosiones y bloques rompiéndose. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("HEAD"))
    private void wwrp2$particles(ParticleOptions type, double x, double y, double z, int count,
                                 double dx, double dy, double dz, double speed,
                                 CallbackInfoReturnable<Integer> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        ZoneRecorder.recordAt(self.dimension(), x, z, new ClientboundLevelParticlesPacket(type, false,
                x, y, z, (float) dx, (float) dy, (float) dz, (float) speed, count));
    }

    @Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;"
            + "Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZ"
            + "Lnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;",
            at = @At("RETURN"))
    private void wwrp2$explosion(Entity source, DamageSource damage, ExplosionDamageCalculator calculator,
                                 double x, double y, double z, float radius, boolean fire,
                                 Level.ExplosionInteraction interaction, CallbackInfoReturnable<Explosion> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        ZoneRecorder.recordAt(self.dimension(), x, z, new ClientboundExplodePacket(x, y, z, radius,
                cir.getReturnValue().getToBlow(), Vec3.ZERO));
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void wwrp2$breaking(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        ZoneRecorder.recordAt(self.dimension(), pos.getX(), pos.getZ(),
                new ClientboundBlockDestructionPacket(breakerId, pos, progress));
    }
}
