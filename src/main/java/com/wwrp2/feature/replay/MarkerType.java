package com.wwrp2.feature.replay;

import net.minecraft.ChatFormatting;

/**
 * Tipo de marcador, que decide su color.
 *
 * <p>Replay Mod no guarda color en los marcadores, así que el color va de dos formas en el
 * {@code markers.json}: como código de color al principio del nombre (Minecraft lo pinta al
 * mostrar el texto) y como campo propio {@code wwrp2_tipo}, que Replay Mod ignora y que usará el
 * visor del servidor (fase 4).</p>
 */
public enum MarkerType {
    /** Inicio de ronda, cambios de fase y fin de ronda. */
    FASE(ChatFormatting.GREEN),
    /** Un jugador mata a otro, directa o indirectamente. */
    KILL(ChatFormatting.RED),
    /** Muerte por mobs o por el entorno (caída, lava, /kill...). */
    PVE(ChatFormatting.GOLD),
    /** Añadido a mano por un admin. */
    MANUAL(ChatFormatting.BLUE);

    private final ChatFormatting color;

    MarkerType(ChatFormatting color) {
        this.color = color;
    }

    public ChatFormatting color() {
        return this.color;
    }
}
