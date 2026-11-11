package com.wwrp2.feature.replay;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wwrp2.Wwrp2;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Comandos de prueba ({@code /replay}) y limpieza al desconectarse o al parar el servidor. */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class ReplayEvents {
    private ReplayEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("replay")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("start")
                        .executes(c -> start(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("jugador", EntityArgument.player())
                                .executes(c -> start(c, EntityArgument.getPlayer(c, "jugador")))))
                .then(Commands.literal("stop")
                        .executes(c -> stop(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("jugador", EntityArgument.player())
                                .executes(c -> stop(c, EntityArgument.getPlayer(c, "jugador")))))
                .then(Commands.literal("list").executes(ReplayEvents::list)));
    }

    private static int start(CommandContext<CommandSourceStack> c, ServerPlayer target) throws CommandSyntaxException {
        ReplayRecorder recorder = ReplayManager.start(target);
        if (recorder == null) {
            c.getSource().sendFailure(Component.literal("No se pudo grabar a " + target.getGameProfile().getName()
                    + ": ya se le está grabando, es el anfitrión de un mundo singleplayer/LAN"
                    + " (no se puede grabar) o ha fallado (mira el log del servidor)."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Grabando a " + target.getGameProfile().getName()), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c, ServerPlayer target) {
        ReplayRecorder recorder = ReplayManager.stop(target.getUUID());
        if (recorder == null) {
            c.getSource().sendFailure(Component.literal("No se estaba grabando a " + target.getGameProfile().getName()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Replay de " + target.getGameProfile().getName()
                + " guardándose en " + recorder.getOutput()), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        if (ReplayManager.all().isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("No se está grabando a nadie."), false);
            return 0;
        }
        for (ReplayRecorder r : ReplayManager.all()) {
            c.getSource().sendSuccess(() -> Component.literal("- " + r.getOutput().getFileName()
                    + " (" + r.elapsedMillis() / 1000 + " s)"), false);
        }
        return ReplayManager.all().size();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ReplayManager.stop(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ReplayManager.stopAll();
    }
}
