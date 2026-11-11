package com.wwrp2.feature.rondas;

import net.minecraft.ChatFormatting;

/**
 * Fases de una ronda, en el orden en que se juegan. Después de {@link #ENDING} se vuelve a
 * {@link #WAITING} y la ronda se da por terminada.
 */
public enum RoundPhase {
    /** No hay ronda en curso: se espera a que un OP inicie la siguiente. */
    WAITING("Espera", ChatFormatting.GRAY),
    /** La ronda ha empezado, pero todavía no se lucha (repartir equipo, colocarse...). */
    PREPARATION("Preparación", ChatFormatting.YELLOW),
    /** Fase de lucha. */
    COMBAT("Combate", ChatFormatting.RED),
    /** La lucha ha acabado: resultados, limpieza del mapa... */
    ENDING("Finalización", ChatFormatting.AQUA);

    private final String displayName;
    private final ChatFormatting color;

    RoundPhase(String displayName, ChatFormatting color) {
        this.displayName = displayName;
        this.color = color;
    }

    /** Nombre que ven los jugadores. */
    public String displayName() {
        return displayName;
    }

    /** Color con el que se anuncia la fase en el chat. */
    public ChatFormatting color() {
        return color;
    }

    /** La fase que viene después de esta; tras {@link #ENDING} vuelve a {@link #WAITING}. */
    public RoundPhase next() {
        RoundPhase[] phases = values();
        return phases[(ordinal() + 1) % phases.length];
    }
}
