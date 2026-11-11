package com.wwrp2.feature.rondas;

import com.mojang.brigadier.context.CommandContext;
import com.wwrp2.Wwrp2;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Comando {@code /ronda}. Iniciar, pasar de fase y parar es solo para OP; {@code /ronda} y
 * {@code /ronda estado} los puede usar cualquiera.
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class RoundCommands {
    /** Nivel de permiso de OP para comandos de juego, el mismo que pide /gamemode. */
    private static final int OP_PERMISSION_LEVEL = 2;

    private RoundCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ronda")
                .executes(RoundCommands::status)
                .then(Commands.literal("estado").executes(RoundCommands::status))
                .then(Commands.literal("iniciar").requires(RoundCommands::isOp).executes(RoundCommands::start))
                .then(Commands.literal("siguiente").requires(RoundCommands::isOp).executes(RoundCommands::advance))
                .then(Commands.literal("parar").requires(RoundCommands::isOp).executes(RoundCommands::stop)));
    }

    private static boolean isOp(CommandSourceStack source) {
        return source.hasPermission(OP_PERMISSION_LEVEL);
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!RoundManager.start(source.getServer())) {
            source.sendFailure(Component.literal("Ya hay una ronda en curso (" + RoundManager.phase().displayName()
                    + "). Usa /ronda siguiente o /ronda parar."));
            return 0;
        }
        return 1;
    }

    private static int advance(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!RoundManager.advance(source.getServer())) {
            source.sendFailure(Component.literal("No hay ninguna ronda en curso. Usa /ronda iniciar."));
            return 0;
        }
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!RoundManager.stop(source.getServer())) {
            source.sendFailure(Component.literal("No hay ninguna ronda en curso."));
            return 0;
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        String text;
        if (RoundManager.isRoundRunning()) {
            long seconds = RoundManager.ticksInPhase() / 20;
            text = String.format("Ronda %d · %s · lleva %d:%02d", RoundManager.roundNumber(),
                    RoundManager.phase().displayName(), seconds / 60, seconds % 60);
        } else if (RoundManager.roundNumber() == 0) {
            text = "No hay ninguna ronda en curso.";
        } else {
            text = "No hay ninguna ronda en curso. La última fue la ronda " + RoundManager.roundNumber() + ".";
        }
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
