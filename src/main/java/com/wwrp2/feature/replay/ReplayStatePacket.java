package com.wwrp2.feature.replay;

import com.wwrp2.feature.replay.client.ReplayClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Servidor → cliente: cómo va el replay que está viendo el jugador, para el menú. Se manda cada
 * medio segundo mientras ve un replay; {@link #INACTIVE} al salir.
 *
 * @param active   está viendo un replay
 * @param open     abrir el menú ({@code /replay menu})
 * @param seeking  está saltando a otro tiempo
 * @param players  jugadores que están ahora en el replay
 * @param following jugador al que se sigue en primera persona, o vacío
 */
public record ReplayStatePacket(boolean active, boolean open, String name, int duration, int position,
                                boolean paused, float speed, boolean seeking, List<Entry> highlights,
                                List<String> players, String following) {

    public static final ReplayStatePacket INACTIVE = new ReplayStatePacket(false, false, "", 1, 0, false, 1f, false, List.of(), List.of(), "");

    /** Un highlight: tiempo en ms, tipo (color) y nombre. */
    public record Entry(int time, MarkerType type, String name) {
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        buffer.writeBoolean(open);
        buffer.writeUtf(name);
        buffer.writeVarInt(duration);
        buffer.writeVarInt(position);
        buffer.writeBoolean(paused);
        buffer.writeFloat(speed);
        buffer.writeBoolean(seeking);
        buffer.writeCollection(highlights, (buf, entry) -> {
            buf.writeVarInt(entry.time());
            buf.writeEnum(entry.type());
            buf.writeUtf(entry.name());
        });
        buffer.writeCollection(players, FriendlyByteBuf::writeUtf);
        buffer.writeUtf(following);
    }

    public static ReplayStatePacket decode(FriendlyByteBuf buffer) {
        return new ReplayStatePacket(buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readFloat(), buffer.readBoolean(),
                buffer.readList(buf -> new Entry(buf.readVarInt(), buf.readEnum(MarkerType.class), buf.readUtf())),
                buffer.readList(FriendlyByteBuf::readUtf), buffer.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        // Las clases de client/ no existen en un servidor dedicado: solo se tocan desde el cliente.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ReplayClientState.onState(this));
    }
}
