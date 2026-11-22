package com.wwrp2.feature.replay;

import com.wwrp2.Wwrp2;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Canal de red del visor de replays. Solo baja información (servidor → cliente) para el menú:
 * los botones del menú mandan comandos normales del visor ({@code /replay ir ...}), así que no hace
 * falta ningún paquete de subida.
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ReplayNetwork {
    /** Se cambia si cambian los paquetes, para que un cliente y un servidor con versiones distintas no se conecten. */
    private static final String PROTOCOL_VERSION = "1";

    // El constructor está marcado para borrarse en 1.20.6, pero su sustituto
    // (ResourceLocation.fromNamespaceAndPath) no existe en los primeros Forge 47, que el mod admite.
    @SuppressWarnings("removal")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Wwrp2.MOD_ID, "replay"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private ReplayNetwork() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> CHANNEL.messageBuilder(ReplayStatePacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ReplayStatePacket::encode)
                .decoder(ReplayStatePacket::decode)
                .consumerMainThread(ReplayStatePacket::handle)
                .add());
    }
}
