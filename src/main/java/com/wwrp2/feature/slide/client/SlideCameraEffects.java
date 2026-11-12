package com.wwrp2.feature.slide.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wwrp2.Wwrp2;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Lo que nota en primera persona quien se desliza: la cámara se inclina un poco de lado, el
 * campo de visión se abre con la velocidad y el arma se ladea. Que la cámara baje lo hace
 * Minecraft solo, al tener el jugador la postura de agachado.
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class SlideCameraEffects {
    private static final float CAMERA_ROLL_DEGREES = 3.5F;
    private static final float HAND_ROLL_DEGREES = 7.0F;
    private static final float HAND_DROP = 0.05F;

    private SlideCameraEffects() {
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || event.getCamera().getEntity() != minecraft.player
                || !minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }
        float blend = SlideClientState.blend(minecraft.player, (float) event.getPartialTick());
        if (blend > 0.0F) {
            event.setRoll(event.getRoll() + CAMERA_ROLL_DEGREES * blend);
        }
    }

    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        // Minecraft ya suaviza por su cuenta los cambios de este valor de un tick a otro.
        if (event.getPlayer() == Minecraft.getInstance().player) {
            event.setNewFovModifier(event.getNewFovModifier() * SlideController.fovScale());
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        // Las dos manos se dibujan con la misma matriz, una detrás de otra: con moverla al
        // dibujar la principal se mueven las dos.
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getHand() != InteractionHand.MAIN_HAND || minecraft.player == null) {
            return;
        }
        float blend = SlideClientState.blend(minecraft.player, event.getPartialTick());
        if (blend > 0.0F) {
            PoseStack poseStack = event.getPoseStack();
            poseStack.translate(0.0F, -HAND_DROP * blend, 0.0F);
            poseStack.mulPose(Axis.ZP.rotationDegrees(HAND_ROLL_DEGREES * blend));
        }
    }
}
