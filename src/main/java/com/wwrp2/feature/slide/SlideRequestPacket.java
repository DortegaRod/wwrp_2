package com.wwrp2.feature.slide;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Cliente → servidor: el jugador que lo envía empieza ({@code sliding = true}) o termina
 * ({@code false}) de deslizarse. No dice a quién se refiere: siempre es quien lo manda, así
 * nadie puede hacer deslizarse a otro.
 */
public record SlideRequestPacket(boolean sliding) {

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(sliding);
    }

    public static SlideRequestPacket decode(FriendlyByteBuf buffer) {
        return new SlideRequestPacket(buffer.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender != null) {
            SlideManager.onClientRequest(sender, sliding);
        }
    }
}
