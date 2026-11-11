package com.wwrp2.feature.slide;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/**
 * Los números del deslizamiento y la regla de cuándo se puede deslizar, compartidos por
 * cliente y servidor. Las velocidades van en bloques por tick (20 ticks = 1 segundo); como
 * referencia, esprintar en llano son unos 0,28 bloques por tick.
 */
public final class SlideRules {
    /** Lo que dura un deslizamiento con el impulso cargado del todo. */
    public static final int FULL_DURATION_TICKS = 18;
    /** Lo que dura con el impulso vacío (deslizar dos veces seguidas). */
    public static final int BASE_DURATION_TICKS = 10;

    /** Por cuánto se multiplica la velocidad al empezar, con el impulso lleno y con el impulso vacío. */
    public static final double FULL_BOOST = 1.5;
    public static final double BASE_BOOST = 1.15;
    /** Tope de la velocidad inicial, para que encadenar saltos y deslizamientos no acelere sin fin. */
    public static final double MAX_START_SPEED = 0.62;
    /** Parte de la velocidad inicial que queda al terminar; se pierde despacio al principio y rápido al final. */
    public static final double END_SPEED_FRACTION = 0.45;

    /** Ticks que tarda en recargarse el impulso entero después de un deslizamiento. */
    public static final int ENERGY_RECHARGE_TICKS = 50;
    /** Ticks sin poder volver a deslizarse después de terminar uno. */
    public static final int COOLDOWN_TICKS = 5;

    /** Cuánto puede girar el deslizamiento por tick, en grados. */
    public static final float TURN_DEGREES_PER_TICK = 4.5F;
    /** Ticks seguidos en el aire (al salir por un borde) antes de dar el deslizamiento por terminado. */
    public static final int MAX_AIR_TICKS = 6;
    /** Ticks que se recuerda un agacharse pulsado en el aire, para deslizarse al aterrizar. */
    public static final int LANDING_BUFFER_TICKS = 8;
    /** Velocidad máxima que se conserva al cortar el deslizamiento con un salto. */
    public static final double JUMP_CARRY_MAX_SPEED = 0.40;

    /**
     * Ticks tras los que el servidor (y los demás clientes) dan por terminado un deslizamiento
     * del que no han recibido el final: el máximo posible más un segundo de margen por lag.
     */
    public static final int TIMEOUT_TICKS = FULL_DURATION_TICKS + MAX_AIR_TICKS + 20;

    private SlideRules() {
    }

    /**
     * Si el jugador está en condiciones de deslizarse. Que además esté esprintando y en el suelo
     * lo comprueba solo el cliente, que es quien lo sabe en el momento exacto.
     */
    public static boolean canSlide(Player player) {
        return player.isAlive()
                && !player.isSpectator()
                && !player.isPassenger()
                && !player.getAbilities().flying
                && !player.isFallFlying()
                && !player.isSleeping()
                && !player.isInWater()
                && !player.isInLava()
                && !player.onClimbable()
                // Arrastrándose por un hueco de un bloque no hay sitio para deslizarse.
                && player.getPose() != Pose.SWIMMING;
    }
}
