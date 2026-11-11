package com.wwrp2.feature.slide;

import com.wwrp2.feature.slide.client.SlideClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Servidor → cliente: el jugador con ese id de entidad ha empezado o terminado de deslizarse.
 * Lo reciben los clientes que lo están viendo, para reproducir la animación; al propio jugador
 * solo se le manda con {@code sliding = false}, cuando el servidor le corta el deslizamiento.
 */
public record SlideStatePacket(int entityId, boolean sliding) {

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeBoolean(sliding);
    }

    public static SlideStatePacket decode(FriendlyByteBuf buffer) {
        return new SlideStatePacket(buffer.readVarInt(), buffer.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        // Las clases de client/ no existen en un servidor dedicado: solo se tocan desde el cliente.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SlideClientState.onServerState(entityId, sliding));
    }
}
