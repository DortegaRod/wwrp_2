package com.wwrp2.feature.slide.client;

import com.wwrp2.Wwrp2;
import com.wwrp2.feature.slide.SlideNetwork;
import com.wwrp2.feature.slide.SlideRequestPacket;
import com.wwrp2.feature.slide.SlideRules;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * El deslizamiento del jugador de este cliente: decide cuándo empieza y cuándo termina y lo
 * mueve cada tick. Corre dentro del tick de movimiento del jugador, justo después de leer las
 * teclas y antes de que Minecraft lo mueva, así que la velocidad que se pone aquí es
 * exactamente la que se recorre ese tick.
 *
 * <p>Cómo se juega: agacharse mientras se esprinta empieza el deslizamiento (pulsado en el aire,
 * empieza al aterrizar). Con W pulsada gira poco a poco hacia donde se mira; sin tocar nada
 * sigue recto y se puede mirar y disparar a los lados. Saltar lo corta conservando parte de la
 * velocidad; pulsar S o volver a pulsar agacharse lo frena. Si al terminar se sigue pulsando
 * agacharse, el jugador se queda agachado; si no, se levanta y vuelve a esprintar.</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class SlideController {
    /**
     * Bloques por tick que se corren en llano por cada punto del atributo de velocidad:
     * 0,98 de la tecla entre (1 − 0,546 de rozamiento del suelo).
     */
    private static final double GROUND_SPEED_PER_ATTRIBUTE = 0.98 / (1.0 - 0.546);
    /** Por debajo de esta velocidad no se sabe hacia dónde se mueve el jugador y se usa hacia dónde mira. */
    private static final double MIN_DIRECTION_SPEED = 0.05;
    /**
     * Cambio de velocidad (al cuadrado) entre un tick y el siguiente a partir del cual se entiende
     * que algo de fuera ha empujado al jugador. Un golpe con retroceso pasa de 0,22; el roce con
     * otra entidad o un daño sin retroceso se quedan por debajo de 0,13.
     */
    private static final double EXTERNAL_IMPULSE_SQR = 0.15;
    /** Ticks mínimos de deslizamiento antes de que volver a pulsar agacharse lo frene. */
    private static final int MIN_TICKS_TO_BRAKE = 3;
    /** Cuánto se abre el campo de visión al empezar; compensa el que se pierde al dejar de esprintar. */
    private static final float FOV_KICK = 0.16F;
    /** Comida mínima para esprintar, la misma que pide Minecraft. */
    private static final int MIN_FOOD_TO_SPRINT = 6;

    @Nullable
    private static LocalPlayer owner;
    private static boolean sliding;
    private static int ticks;
    private static int durationTicks;
    private static int airTicks;
    private static double startSpeed;
    private static double speed;
    private static double directionX;
    private static double directionZ;

    /** De 0 a 1: se gasta entero al deslizarse y se recarga solo. Cuanto más hay, más largo y rápido es el deslizamiento. */
    private static float energy = 1.0F;
    private static int cooldown;
    private static int landingBuffer;
    private static boolean armedInAir;
    private static boolean wasShiftDown;

    private static boolean hasLastPosition;
    private static double lastX;
    private static double lastZ;
    /** Velocidad con la que el jugador acabó el tick anterior, para notar si algo de fuera la cambia. */
    @Nullable
    private static Vec3 expectedVelocity;

    private SlideController() {
    }

    /** Por cuánto se multiplica el campo de visión ahora mismo: más cuanto más rápido va el deslizamiento. */
    static float fovScale() {
        return sliding ? 1.0F + FOV_KICK * (float) (speed / startSpeed) : 1.0F;
    }

    /** El servidor ha cortado el deslizamiento (no lo aceptó o se le acabó el tiempo). */
    static void stopFromServer() {
        if (sliding && owner != null) {
            end(owner, false);
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }
        if (player != owner) {
            adopt(player);
        }
        Input input = event.getInput();

        boolean shiftDown = input.shiftKeyDown;
        boolean shiftPressed = shiftDown && !wasShiftDown;
        wasShiftDown = shiftDown;

        // Lo que se ha movido en el último tick. La velocidad guardada de la entidad no sirve:
        // en el suelo ya lleva aplicado el rozamiento y es casi la mitad de lo que se recorre.
        double movedX = hasLastPosition ? player.getX() - lastX : 0.0;
        double movedZ = hasLastPosition ? player.getZ() - lastZ : 0.0;
        lastX = player.getX();
        lastZ = player.getZ();
        hasLastPosition = true;

        if (sliding) {
            tickSlide(player, input, shiftDown, shiftPressed);
            return;
        }

        if (cooldown > 0) {
            cooldown--;
        }
        energy = Math.min(1.0F, energy + 1.0F / SlideRules.ENERGY_RECHARGE_TICKS);
        if (landingBuffer > 0) {
            landingBuffer--;
        }

        boolean onGround = player.onGround();
        if (shiftPressed && !onGround) {
            landingBuffer = SlideRules.LANDING_BUFFER_TICKS;
            armedInAir = true;
        }
        // En el aire el deslizamiento queda pendiente: vale si se aterriza enseguida o si se
        // llega al suelo con agacharse todavía pulsado.
        boolean wantsSlide = onGround && (shiftPressed || landingBuffer > 0 || (armedInAir && shiftDown));
        if (onGround) {
            landingBuffer = 0;
            armedInAir = false;
        }
        if (wantsSlide && cooldown == 0 && player.isSprinting() && SlideRules.canSlide(player)) {
            start(player, input, movedX, movedZ);
        }
    }

    private static void start(LocalPlayer player, Input input, double movedX, double movedZ) {
        double movedSpeed = Math.hypot(movedX, movedZ);
        if (movedSpeed > MIN_DIRECTION_SPEED) {
            directionX = movedX / movedSpeed;
            directionZ = movedZ / movedSpeed;
        } else {
            float yaw = player.getYRot() * Mth.DEG_TO_RAD;
            directionX = -Mth.sin(yaw);
            directionZ = Mth.cos(yaw);
        }

        // Se parte de lo que más corra: lo que ya llevaba (viene de un salto) o su esprint en
        // llano, que ya incluye pociones de velocidad. Lo recorrido se recorta por si viene de un teletransporte.
        double sprintSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED) * GROUND_SPEED_PER_ATTRIBUTE;
        double baseSpeed = Math.max(Math.min(movedSpeed, SlideRules.MAX_START_SPEED), sprintSpeed);
        double boost = Mth.lerp(energy, SlideRules.BASE_BOOST, SlideRules.FULL_BOOST);
        startSpeed = Math.min(baseSpeed * boost, Math.max(SlideRules.MAX_START_SPEED, baseSpeed));
        speed = startSpeed;
        durationTicks = Math.round(Mth.lerp(energy, (float) SlideRules.BASE_DURATION_TICKS,
                (float) SlideRules.FULL_DURATION_TICKS));

        energy = 0.0F;
        ticks = 0;
        airTicks = 0;
        expectedVelocity = null;
        sliding = true;

        // Hitbox y cámara de agachado, sin agacharse de verdad: agachado, Minecraft frena al
        // jugador y no le deja salirse de los bordes.
        player.setForcedPose(Pose.CROUCHING);
        SlideClientState.setSliding(player, true);
        SlideNetwork.CHANNEL.sendToServer(new SlideRequestPacket(true));

        tickSlide(player, input, false, false);
    }

    private static void tickSlide(LocalPlayer player, Input input, boolean shiftDown, boolean shiftPressed) {
        if (!SlideRules.canSlide(player)) {
            end(player, true);
            return;
        }
        // Un golpe o una explosión: se deja la velocidad que ha llegado y se acaba el deslizamiento.
        if (expectedVelocity != null
                && player.getDeltaMovement().distanceToSqr(expectedVelocity) > EXTERNAL_IMPULSE_SQR) {
            end(player, true);
            return;
        }
        // Contra una pared, o frenando a propósito: se para con el rozamiento normal.
        boolean hitWall = player.horizontalCollision && !player.minorHorizontalCollision;
        boolean brake = input.down || (shiftPressed && ticks >= MIN_TICKS_TO_BRAKE);
        if (hitWall || brake) {
            end(player, true);
            return;
        }

        ticks++;
        steer(player, input);
        // Aguanta casi toda la velocidad al principio y la pierde deprisa al final.
        double progress = Math.min(1.0, ticks / (double) durationTicks);
        speed = startSpeed * (1.0 - (1.0 - SlideRules.END_SPEED_FRACTION) * progress * progress);

        if (player.onGround()) {
            airTicks = 0;
        } else if (++airTicks > SlideRules.MAX_AIR_TICKS) {
            // Ha salido por un borde: sigue cayendo con la velocidad que le queda del tick anterior.
            end(player, true);
            return;
        }

        if (input.jumping && player.onGround()) {
            // No se tocan las teclas: Minecraft salta en este mismo tick con la velocidad que se deja aquí.
            setHorizontalSpeed(player, Math.min(speed, SlideRules.JUMP_CARRY_MAX_SPEED));
            end(player, true);
            resumeSprint(player, input, shiftDown);
            return;
        }
        if (ticks >= durationTicks) {
            // No se pone velocidad: la que queda del tick anterior ya lleva el rozamiento del
            // suelo, y desde ella Minecraft acelera o frena sin tirones según las teclas.
            end(player, true);
            resumeSprint(player, input, shiftDown);
            return;
        }

        setHorizontalSpeed(player, speed);
        // Mientras se desliza, las teclas no mueven al jugador (solo giran, en steer) ni lo agachan.
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.shiftKeyDown = false;
    }

    /** Gira la dirección del deslizamiento hacia donde apuntan las teclas de movimiento, con un tope por tick. */
    private static void steer(LocalPlayer player, Input input) {
        float forward = input.up ? 1.0F : 0.0F;
        float strafe = (input.left ? 1.0F : 0.0F) - (input.right ? 1.0F : 0.0F);
        if (forward == 0.0F && strafe == 0.0F) {
            return;
        }
        // Las teclas son relativas a la cámara, igual que al andar (Entity#getInputVector).
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        float sin = Mth.sin(yaw);
        float cos = Mth.cos(yaw);
        double wishX = strafe * cos - forward * sin;
        double wishZ = forward * cos + strafe * sin;

        double current = Math.atan2(directionZ, directionX);
        double wish = Math.atan2(wishZ, wishX);
        double turn = Mth.clamp(Mth.wrapDegrees(Math.toDegrees(wish - current)),
                -SlideRules.TURN_DEGREES_PER_TICK, SlideRules.TURN_DEGREES_PER_TICK);
        double angle = current + Math.toRadians(turn);
        directionX = Math.cos(angle);
        directionZ = Math.sin(angle);
    }

    private static void setHorizontalSpeed(LocalPlayer player, double horizontalSpeed) {
        player.setDeltaMovement(directionX * horizontalSpeed, player.getDeltaMovement().y,
                directionZ * horizontalSpeed);
    }

    /** Como en BO3, si se sigue empujando hacia delante se sale del deslizamiento corriendo. */
    private static void resumeSprint(LocalPlayer player, Input input, boolean shiftDown) {
        boolean enoughFood = player.getFoodData().getFoodLevel() > MIN_FOOD_TO_SPRINT || player.getAbilities().mayfly;
        if (input.up && !shiftDown && enoughFood) {
            player.setSprinting(true);
        }
    }

    private static void end(LocalPlayer player, boolean notifyServer) {
        sliding = false;
        cooldown = SlideRules.COOLDOWN_TICKS;
        expectedVelocity = null;
        // Solo se quita la postura forzada si es la nuestra, por si otro mod ha puesto la suya.
        if (player.getForcedPose() == Pose.CROUCHING) {
            player.setForcedPose(null);
        }
        SlideClientState.setSliding(player, false);
        if (notifyServer) {
            SlideNetwork.CHANNEL.sendToServer(new SlideRequestPacket(false));
        }
    }

    /** Al reaparecer o cambiar de dimensión el jugador es una entidad nueva: se empieza de cero con ella. */
    private static void adopt(LocalPlayer player) {
        if (sliding) {
            sliding = false;
            SlideNetwork.CHANNEL.sendToServer(new SlideRequestPacket(false));
        }
        owner = player;
        SlideClientState.setSliding(player, false);
        energy = 1.0F;
        cooldown = 0;
        landingBuffer = 0;
        armedInAir = false;
        wasShiftDown = false;
        hasLastPosition = false;
        expectedVelocity = null;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // En un solo jugador este evento también llega desde el hilo del servidor: se descarta lo primero.
        if (event.side.isClient() && event.phase == TickEvent.Phase.END && sliding && event.player == owner) {
            expectedVelocity = event.player.getDeltaMovement();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        owner = null;
        sliding = false;
        expectedVelocity = null;
        SlideClientState.clear();
    }
}
