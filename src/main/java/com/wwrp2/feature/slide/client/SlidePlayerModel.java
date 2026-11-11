package com.wwrp2.feature.slide.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/**
 * El modelo de jugador de siempre, más la postura de deslizarse: sentado casi en el suelo, el
 * torso echado hacia atrás, una pierna estirada delante y la otra algo levantada, y la mano
 * libre apoyada detrás. Lo coloca {@link SlideModelInstaller} en el renderer de los jugadores.
 *
 * <p>Minecraft calcula primero su postura normal y aquí se mezcla con la de deslizarse según
 * {@link SlideClientState#blend}, así se entra y se sale de ella sin saltos. La armadura y lo
 * que se lleva en las manos siguen al modelo; la capa y los élitros no, se dibujan aparte.</p>
 *
 * <p>Las medidas van en píxeles del modelo (16 por bloque antes de escalar), con la Y hacia
 * abajo y el jugador mirando hacia −Z. En los giros en X, un valor negativo lleva la punta de
 * la pieza hacia delante.</p>
 */
public class SlidePlayerModel extends PlayerModel<AbstractClientPlayer> {
    /** Dónde queda el suelo en el modelo. */
    private static final float GROUND_Y = 24.0F;
    /**
     * A un jugador agachado el renderer lo dibuja 0,125 bloques más abajo
     * (PlayerRenderer#getRenderOffset), y el modelo va escalado a 0,9375: para él, el suelo sube.
     */
    private static final float CROUCHING_GROUND_Y = GROUND_Y - 0.125F * 16.0F / 0.9375F;

    /** Altura de la cadera sobre el suelo: la pierna tumbada mide 4 de grosor y gira por su centro. */
    private static final float HIP_HEIGHT = 2.6F;
    /** La cadera va un poco adelantada para que el cuerpo quede repartido alrededor de la hitbox. */
    private static final float HIP_Z = -1.0F;
    private static final float TORSO_LENGTH = 12.0F;
    /** Lo que baja el eje del brazo desde el cuello, a lo largo del torso. */
    private static final float SHOULDER_DROP = 2.0F;
    /** Giro del torso: echado hacia atrás unos 35 grados. */
    private static final float TORSO_LEAN = -0.6F;
    private static final float TORSO_LEAN_COS = (float) Math.cos(TORSO_LEAN);
    private static final float TORSO_LEAN_SIN = (float) Math.sin(TORSO_LEAN);

    /** Pierna derecha: estirada por delante, rozando el suelo. */
    private static final float LEAD_LEG_X_ROT = -1.52F;
    private static final float LEAD_LEG_Y_ROT = 0.08F;
    /** Pierna izquierda: algo levantada y abierta, como con la rodilla doblada. */
    private static final float TRAIL_LEG_X_ROT = -1.78F;
    private static final float TRAIL_LEG_Y_ROT = -0.3F;

    /** Brazo de la mano principal: adelantado, con lo que lleve apuntando al frente. */
    private static final float MAIN_ARM_X_ROT = -0.9F;
    /** Brazo de la otra mano: hacia atrás y abierto, con la mano en el suelo. */
    private static final float SUPPORT_ARM_X_ROT = 0.55F;
    private static final float SUPPORT_ARM_Z_ROT = 0.2F;
    /**
     * Un brazo más levantado que esto está haciendo algo (apuntar, cubrirse con el escudo, lo
     * que le ponga otro mod) y solo se recoloca en el hombro, sin tocarle el giro.
     */
    private static final float BUSY_ARM_X_ROT = -0.5F;

    /** Si el último dibujado dejó la cabeza y el torso desplazados en Z, que Minecraft no recoloca. */
    private boolean posed;

    public SlidePlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        // El modelo es el mismo para todos los jugadores: se deshace lo del anterior.
        if (posed) {
            head.z = 0.0F;
            body.z = 0.0F;
            posed = false;
        }
        super.setupAnim(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        // El brazo en primera persona se dibuja con este mismo modelo y la edad a 0
        // (PlayerRenderer#renderHand): ahí no se toca nada.
        if (ageInTicks == 0.0F) {
            return;
        }
        float blend = SlideClientState.blend(player, ageInTicks - player.tickCount);
        if (blend <= 0.0F) {
            return;
        }

        float hipY = (player.isCrouching() ? CROUCHING_GROUND_Y : GROUND_Y) - HIP_HEIGHT;
        float neckY = hipY - TORSO_LENGTH * TORSO_LEAN_COS;
        float neckZ = HIP_Z - TORSO_LENGTH * TORSO_LEAN_SIN;
        float shoulderY = neckY + SHOULDER_DROP * TORSO_LEAN_COS;
        float shoulderZ = neckZ + SHOULDER_DROP * TORSO_LEAN_SIN;

        // La cabeza conserva su giro: sigue mirando hacia donde mira el jugador.
        head.y = Mth.lerp(blend, head.y, neckY);
        head.z = Mth.lerp(blend, head.z, neckZ);
        body.y = Mth.lerp(blend, body.y, neckY);
        body.z = Mth.lerp(blend, body.z, neckZ);
        body.xRot = rotLerp(blend, body.xRot, TORSO_LEAN);

        poseLeg(rightLeg, blend, hipY, LEAD_LEG_X_ROT, LEAD_LEG_Y_ROT);
        poseLeg(leftLeg, blend, hipY, TRAIL_LEG_X_ROT, TRAIL_LEG_Y_ROT);

        boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        boolean swingingMainArm = attackTime > 0.0F && player.swingingArm != InteractionHand.OFF_HAND;
        boolean swingingOffArm = attackTime > 0.0F && player.swingingArm == InteractionHand.OFF_HAND;
        poseArm(rightArm, blend, shoulderY, shoulderZ, rightHanded, 1.0F,
                rightHanded ? swingingMainArm : swingingOffArm);
        poseArm(leftArm, blend, shoulderY, shoulderZ, !rightHanded, -1.0F,
                rightHanded ? swingingOffArm : swingingMainArm);

        posed = true;

        // Las capas de la skin copian a su pieza dentro de super.setupAnim: hay que repetirlo.
        hat.copyFrom(head);
        jacket.copyFrom(body);
        leftSleeve.copyFrom(leftArm);
        rightSleeve.copyFrom(rightArm);
        leftPants.copyFrom(leftLeg);
        rightPants.copyFrom(rightLeg);
    }

    private static void poseLeg(ModelPart leg, float blend, float hipY, float xRot, float yRot) {
        leg.y = Mth.lerp(blend, leg.y, hipY);
        leg.z = Mth.lerp(blend, leg.z, HIP_Z);
        leg.xRot = rotLerp(blend, leg.xRot, xRot);
        leg.yRot = rotLerp(blend, leg.yRot, yRot);
    }

    /**
     * @param outward  1 para el brazo derecho y −1 para el izquierdo: el signo del giro en Z que lo abre hacia fuera
     * @param swinging si este brazo está dando un golpe ahora mismo
     */
    private static void poseArm(ModelPart arm, float blend, float shoulderY, float shoulderZ, boolean mainArm,
                                float outward, boolean swinging) {
        arm.y = Mth.lerp(blend, arm.y, shoulderY);
        arm.z = Mth.lerp(blend, arm.z, shoulderZ);
        if (swinging || wrapRadians(arm.xRot) < BUSY_ARM_X_ROT) {
            return;
        }
        if (mainArm) {
            arm.xRot = rotLerp(blend, arm.xRot, MAIN_ARM_X_ROT);
        } else {
            arm.xRot = rotLerp(blend, arm.xRot, SUPPORT_ARM_X_ROT);
            arm.zRot = rotLerp(blend, arm.zRot, SUPPORT_ARM_Z_ROT * outward);
        }
    }

    private static float rotLerp(float blend, float from, float to) {
        return from + blend * wrapRadians(to - from);
    }

    private static float wrapRadians(float radians) {
        return Mth.wrapDegrees(radians * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
    }
}
