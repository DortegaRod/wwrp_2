package com.wwrp2.feature.killfeed;

import com.wwrp2.Wwrp2;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** Canal propio de la feature, únicamente para recibir efectos confirmados por el servidor. */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KillfeedNetwork {
    private static final String PROTOCOL_VERSION = "1";

    @SuppressWarnings("removal")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Wwrp2.MOD_ID, "killfeed"), () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    private KillfeedNetwork() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.messageBuilder(KillfeedEffectPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(KillfeedEffectPacket::encode)
                    .decoder(KillfeedEffectPacket::decode)
                    .consumerMainThread(KillfeedEffectPacket::handle)
                    .add();
            CHANNEL.messageBuilder(KillfeedCombatPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(KillfeedCombatPacket::encode)
                    .decoder(KillfeedCombatPacket::decode)
                    .consumerMainThread(KillfeedCombatPacket::handle)
                    .add();
        });
    }
}
