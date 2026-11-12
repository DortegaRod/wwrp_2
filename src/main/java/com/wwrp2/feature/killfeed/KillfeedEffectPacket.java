package com.wwrp2.feature.killfeed;

import com.wwrp2.feature.killfeed.client.KillfeedLightning;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Posición del efecto visual; el mensaje de muerte sigue usando el chat de Minecraft. */
public record KillfeedEffectPacket(ResourceLocation dimension, double x, double y, double z) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(dimension);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
    }

    public static KillfeedEffectPacket decode(FriendlyByteBuf buffer) {
        return new KillfeedEffectPacket(buffer.readResourceLocation(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> KillfeedLightning.show(dimension, x, y, z));
    }
}
