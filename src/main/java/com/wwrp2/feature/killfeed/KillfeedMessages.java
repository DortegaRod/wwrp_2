package com.wwrp2.feature.killfeed;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EntityType;

import java.util.UUID;

/** Formatea el anuncio original sin perder traducciones, equipos ni nombres de armas. */
public final class KillfeedMessages {
    private KillfeedMessages() {
    }

    /** Devuelve el mismo componente si no es una muerte de jugador reconocida. */
    public static Component format(Component message) {
        if (victimName(message) == null) {
            return message;
        }

        // Se sustituye el texto que ya iba a mostrarse, no se publica otro mensaje.
        return Component.empty()
                .append(Component.literal("[Baja] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(message.copy());
    }

    /** Nombre del jugador caído para el aviso breve; null si no es una muerte reconocida. */
    public static Component victimName(Component message) {
        if (!(message.getContents() instanceof TranslatableContents contents)
                || !contents.getKey().startsWith("death.")
                || contents.getArgs().length == 0
                || !(contents.getArgs()[0] instanceof Component victim)
                || !isPlayerName(victim)) {
            return null;
        }
        return victim;
    }

    /** Identidad del caído para usar su skin, también si el nombre está envuelto por un equipo. */
    public static UUID victimId(Component message) {
        Component victim = victimName(message);
        var info = victim == null ? null : entityInfo(victim, true);
        return info == null ? null : info.id;
    }

    /** Usa el atacante identificado por Minecraft, sin confundir un arma con un jugador. */
    public static Component killerName(Component message) {
        Component victim = victimName(message);
        if (victim == null) {
            return null;
        }
        var contents = (TranslatableContents) message.getContents();
        if (contents.getArgs().length < 2 || !(contents.getArgs()[1] instanceof Component killer)) {
            return null;
        }
        var victimInfo = entityInfo(victim, true);
        var killerInfo = entityInfo(killer, false);
        // Se compara la identidad, no el texto: un suicidio no tiene otro eliminador.
        return victimInfo != null && killerInfo != null && !victimInfo.id.equals(killerInfo.id) ? killer : null;
    }

    private static HoverEvent.EntityTooltipInfo entityInfo(Component name, boolean playersOnly) {
        HoverEvent hover = name.getStyle().getHoverEvent();
        var info = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_ENTITY);
        if (info != null && (!playersOnly || info.type == EntityType.PLAYER)) {
            return info;
        }
        for (Component sibling : name.getSiblings()) {
            var nested = entityInfo(sibling, playersOnly);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static boolean isPlayerName(Component name) {
        HoverEvent hover = name.getStyle().getHoverEvent();
        HoverEvent.EntityTooltipInfo entity = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_ENTITY);
        if (entity != null && entity.type == EntityType.PLAYER) {
            return true;
        }
        // Un equipo o un mod puede envolver el nombre en componentes hermanos.
        return name.getSiblings().stream().anyMatch(KillfeedMessages::isPlayerName);
    }
}
