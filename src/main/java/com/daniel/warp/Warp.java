package com.daniel.warp;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Punto de entrada del mod. Las features viven en {@code com.daniel.warp.feature} y se
 * registran solas, así que aquí no va lógica de ninguna feature (ver CONTRIBUTING.md).
 */
@Mod(Warp.MOD_ID)
public class Warp {
    public static final String MOD_ID = "warp";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Warp(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);

        // Features con DeferredRegister: una línea por feature, aquí debajo.
        // Ejemplo: MiFeatureRegistry.register(modEventBus);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Warp cargado");
    }
}
