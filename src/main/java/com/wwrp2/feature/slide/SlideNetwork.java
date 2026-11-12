package com.wwrp2.feature.slide;

import com.wwrp2.Wwrp2;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Canal de red del deslizamiento. El aviso viaja cliente → servidor → resto de clientes:
 * {@link SlideRequestPacket} sube al servidor y {@link SlideStatePacket} baja a quien ve al jugador.
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SlideNetwork {
    /** Se cambia si cambian los paquetes, para que un cliente y un servidor con versiones distintas no se conecten. */
    private static final String PROTOCOL_VERSION = "1";

    // El constructor está marcado para borrarse en 1.20.6, pero su sustituto
    // (ResourceLocation.fromNamespaceAndPath) no existe en los primeros Forge 47, que el mod admite.
    @SuppressWarnings("removal")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Wwrp2.MOD_ID, "slide"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private SlideNetwork() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.messageBuilder(SlideRequestPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(SlideRequestPacket::encode)
                    .decoder(SlideRequestPacket::decode)
                    .consumerMainThread(SlideRequestPacket::handle)
                    .add();
            CHANNEL.messageBuilder(SlideStatePacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(SlideStatePacket::encode)
                    .decoder(SlideStatePacket::decode)
                    .consumerMainThread(SlideStatePacket::handle)
                    .add();
        });
    }
}
