package com.wwrp2;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Punto de entrada del mod. Las features viven en {@code com.wwrp2.feature} y se
 * registran solas, así que aquí no va lógica de ninguna feature (ver CONTRIBUTING.md).
 */
@Mod(Wwrp2.MOD_ID)
public class Wwrp2 {
    public static final String MOD_ID = "wwrp2";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Wwrp2(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);

        // Features con DeferredRegister: una línea por feature, aquí debajo.
        // Ejemplo: MiFeatureRegistry.register(modEventBus);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("wwrp-2 cargado");
    }
}
