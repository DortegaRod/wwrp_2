package com.daniel.wwrp2.feature.ocultarnametag.client;

import com.daniel.wwrp2.Wwrp2;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Oculta el nametag de los jugadores cuando hay una pared entre la cámara y ellos,
 * para que no se pueda ver a nadie a través de los bloques.
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class NametagOcclusionHandler {

    private NametagOcclusionHandler() {
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Entity viewer = mc.getCameraEntity();
        if (viewer == null || target == viewer) {
            return;
        }
        // En espectador se atraviesan los bloques, así que no ocultamos nada.
        if (mc.player != null && mc.player.isSpectator()) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        if (!isVisible(target.level(), camera.getPosition(), target, viewer, event.getPartialTick())) {
            event.setResult(Event.Result.DENY);
        }
    }

    /**
     * El jugador cuenta como visible si desde la cámara se ven sus ojos, su torso o sus pies.
     * No se comprueba la posición del propio nametag porque queda por encima de la cabeza
     * y asomaría por encima de una pared de dos bloques.
     */
    private static boolean isVisible(Level level, Vec3 from, Player target, Entity viewer, float partialTick) {
        Vec3 feet = target.getPosition(partialTick);
        double[] heights = {target.getEyeHeight(), target.getBbHeight() * 0.5, 0.1};

        for (double dy : heights) {
            Vec3 to = feet.add(0, dy, 0);
            // VISUAL: cristales, paneles y rejas no tapan, igual que con la cámara en tercera persona.
            ClipContext context = new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer);
            if (level.clip(context).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }
}
