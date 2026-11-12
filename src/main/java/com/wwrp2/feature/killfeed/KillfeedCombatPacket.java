package com.wwrp2.feature.killfeed;

import com.wwrp2.feature.killfeed.client.KillfeedChat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Estado autoritativo de combate, enviado al cambiar de fase y al conectar un jugador. */
public record KillfeedCombatPacket(boolean active) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
    }

    public static KillfeedCombatPacket decode(FriendlyByteBuf buffer) {
        return new KillfeedCombatPacket(buffer.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> KillfeedChat.setCombatActive(active));
    }
}
