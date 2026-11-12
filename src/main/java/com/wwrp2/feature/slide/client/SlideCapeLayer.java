package com.wwrp2.feature.slide.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Items;

/**
 * La capa de un jugador que se está deslizando. La de Minecraft (CapeLayer) cuelga siempre del
 * cuello de un jugador de pie, que con la postura de deslizarse queda en el aire, por encima de
 * él. {@link SlidePlayerModel} la esconde mientras dura la postura y esta capa la dibuja colgando
 * de la espalda, ondeando hacia atrás con la velocidad igual que la normal pero sin meterse en el
 * suelo.
 *
 * <p>Copia lo que hace CapeLayer y va mezclando su colocación con la del deslizamiento según
 * {@link SlideClientState#blend}, para que la capa no salte al entrar ni al salir.</p>
 */
public class SlideCapeLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final float CAPE_LENGTH = 16.0F;
    /** Lo que se deja entre la punta de la capa y el suelo, que la capa tiene grosor y no cuelga justo del punto. */
    private static final float GROUND_MARGIN = 1.0F;
    /** Lo más que puede bajar la capa al deslizarse antes de tocar el suelo, en grados desde la vertical. */
    private static final float MIN_SLIDE_ANGLE = (float) Math.toDegrees(Math.acos(
            Mth.clamp((SlidePlayerModel.CAPE_HINGE_HEIGHT - GROUND_MARGIN) / CAPE_LENGTH, 0.0F, 1.0F)));
    /** Y lo más que puede subir con el viento: horizontal, sin apuntar hacia arriba. */
    private static final float MAX_SLIDE_ANGLE = 90.0F;

    /** Lo que CapeLayer separa la capa del centro del torso, y lo que la baja al agacharse, en píxeles. */
    private static final float VANILLA_BACK_OFFSET = 2.0F;
    private static final float VANILLA_CROUCH_DROP = 2.4F;
    private static final float VANILLA_CROUCH_ANGLE = 25.0F;

    public SlideCapeLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
                       float headPitch) {
        if (!(getParentModel() instanceof SlidePlayerModel model) || !model.restoreCape()) {
            return;
        }
        // Las mismas condiciones que CapeLayer para dibujar la capa.
        ResourceLocation texture = player.getCloakTextureLocation();
        if (!player.isCapeLoaded() || player.isInvisible() || !player.isModelPartShown(PlayerModelPart.CAPE)
                || texture == null || player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }

        // Cómo ondea la capa, tal cual lo calcula CapeLayer: se queda atrás respecto al jugador al moverse.
        double dx = Mth.lerp(partialTick, player.xCloakO, player.xCloak) - Mth.lerp(partialTick, player.xo, player.getX());
        double dy = Mth.lerp(partialTick, player.yCloakO, player.yCloak) - Mth.lerp(partialTick, player.yo, player.getY());
        double dz = Mth.lerp(partialTick, player.zCloakO, player.zCloak) - Mth.lerp(partialTick, player.zo, player.getZ());
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot) * Mth.DEG_TO_RAD;
        double sin = Mth.sin(bodyYaw);
        double cos = -Mth.cos(bodyYaw);
        float lift = Mth.clamp((float) dy * 10.0F, -6.0F, 32.0F);
        float back = Mth.clamp((float) (dx * sin + dz * cos) * 100.0F, 0.0F, 150.0F);
        float side = Mth.clamp((float) (dx * cos - dz * sin) * 100.0F, -20.0F, 20.0F);
        float bob = Mth.lerp(partialTick, player.oBob, player.bob);
        lift += Mth.sin(Mth.lerp(partialTick, player.walkDistO, player.walkDist) * 6.0F) * 32.0F * bob;
        float angle = 6.0F + back / 2.0F + lift;

        boolean crouching = player.isCrouching();
        float vanillaAngle = crouching ? angle + VANILLA_CROUCH_ANGLE : angle;
        float vanillaY = crouching ? VANILLA_CROUCH_DROP : 0.0F;
        float slideAngle = Mth.clamp(angle, MIN_SLIDE_ANGLE, MAX_SLIDE_ANGLE);

        float blend = model.capeBlend();
        poseStack.pushPose();
        poseStack.translate(0.0F, Mth.lerp(blend, vanillaY, model.capeHingeY()) / 16.0F,
                Mth.lerp(blend, VANILLA_BACK_OFFSET, model.capeHingeZ()) / 16.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(blend, vanillaAngle, slideAngle)));
        poseStack.mulPose(Axis.ZP.rotationDegrees(side / 2.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - side / 2.0F));
        VertexConsumer consumer = buffer.getBuffer(RenderType.entitySolid(texture));
        model.renderCloak(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}
