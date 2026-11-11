package com.wwrp2.feature.slide.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wwrp2.Wwrp2;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * La tecla de deslizarse: C por defecto, y cada jugador la puede cambiar en Opciones → Controles,
 * en el apartado de movimiento.
 *
 * <p>En Minecraft la C ya la usa «Cargar barra de objetos» del modo creativo, así que en la
 * pantalla de controles las dos salen marcadas como repetidas. Funcionan las dos a la vez.</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SlideKeyBindings {
    /** Solo cuenta jugando: con un menú o el chat abiertos no se desliza. */
    public static final KeyMapping SLIDE = new KeyMapping("wwrp2.slide.key", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, KeyMapping.CATEGORY_MOVEMENT);

    private SlideKeyBindings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SLIDE);
    }
}
