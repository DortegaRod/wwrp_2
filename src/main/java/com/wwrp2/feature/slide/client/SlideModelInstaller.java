package com.wwrp2.feature.slide.client;

import com.mojang.logging.LogUtils;
import com.wwrp2.Wwrp2;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;

/**
 * Cambia el modelo de los renderers de jugador por {@link SlidePlayerModel}. Forge no tiene
 * ningún evento para tocar la postura de un jugador después de que Minecraft la calcule, y la
 * única forma sin mixins es que el propio modelo sea el nuestro.
 *
 * <p>Se hace cada vez que Minecraft crea los renderers (al arrancar y al recargar recursos).
 * Si otro mod ya ha puesto su modelo, o el cambio falla, se deja como está: el deslizamiento
 * funciona igual, solo que los demás jugadores se ven agachados en vez de con la animación.</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SlideModelInstaller {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Nombre SRG de {@code LivingEntityRenderer.model}, que es protected. */
    private static final String MODEL_FIELD = "f_115290_";
    /** Nombre con el que Minecraft registra el renderer de las skins de brazos finos. */
    private static final String SLIM_SKIN = "slim";

    private SlideModelInstaller() {
    }

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            EntityRenderer<? extends Player> renderer = event.getPlayerSkin(skin);
            if (!(renderer instanceof PlayerRenderer playerRenderer)) {
                continue;
            }
            if (playerRenderer.getModel().getClass() != PlayerModel.class) {
                LOGGER.warn("El modelo de jugador '{}' ya lo ha cambiado otro mod ({}): no se pone la animación de deslizarse",
                        skin, playerRenderer.getModel().getClass().getName());
                continue;
            }
            boolean slim = SLIM_SKIN.equals(skin);
            try {
                SlidePlayerModel model = new SlidePlayerModel(
                        event.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
                ObfuscationReflectionHelper.setPrivateValue(LivingEntityRenderer.class, playerRenderer, model, MODEL_FIELD);
                LOGGER.debug("Animación de deslizarse puesta en el modelo de jugador '{}'", skin);
            } catch (RuntimeException e) {
                LOGGER.error("No se ha podido poner la animación de deslizarse en el modelo de jugador '{}'", skin, e);
            }
        }
    }
}
