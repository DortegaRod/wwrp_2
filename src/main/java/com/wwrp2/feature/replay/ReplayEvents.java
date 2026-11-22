package com.wwrp2.feature.replay;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import com.wwrp2.Wwrp2;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.List;

/**
 * Comandos {@code /replay} (permiso 2) y eventos: tick de la grabación de zona, chat y limpieza al
 * desconectarse o al parar el servidor.
 *
 * <pre>
 * /replay zona                          muestra la zona guardada
 * /replay zona &lt;esquina1&gt; &lt;esquina2&gt;     define la zona (x z de dos esquinas)
 * /replay start | stop                  graba la zona / para y guarda
 * /replay estado                        qué se está grabando
 * /replay jugador start|stop [jugador]  graba lo que ve un jugador concreto
 * /replay ver &lt;archivo&gt;                 ver un replay dentro del servidor (solo fuera de ronda)
 * /replay marca nueva &lt;nombre&gt;          marcador ahora, donde mira el admin
 * /replay marca lista                   marcadores de la grabación en curso (con su número)
 * /replay marca editar &lt;n&gt; &lt;nombre&gt;     cambia el nombre del marcador n
 * /replay marca borrar &lt;n&gt;              borra el marcador n
 * </pre>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
public final class ReplayEvents {
    private ReplayEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("replay")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("zona")
                        .executes(ReplayEvents::showZone)
                        .then(Commands.argument("esquina1", ColumnPosArgument.columnPos())
                                .then(Commands.argument("esquina2", ColumnPosArgument.columnPos())
                                        .executes(ReplayEvents::setZone))))
                .then(Commands.literal("start").executes(ReplayEvents::startZone))
                .then(Commands.literal("stop").executes(ReplayEvents::stopZone))
                .then(Commands.literal("estado").executes(ReplayEvents::status))
                .then(Commands.literal("ver")
                        .then(Commands.argument("archivo", StringArgumentType.word())
                                .suggests(REPLAY_FILES)
                                .executes(ReplayEvents::view)))
                .then(Commands.literal("marca")
                        .then(Commands.literal("nueva")
                                .then(Commands.argument("nombre", StringArgumentType.greedyString())
                                        .executes(ReplayEvents::addMarker)))
                        .then(Commands.literal("lista").executes(ReplayEvents::listMarkers))
                        .then(Commands.literal("editar")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("nombre", StringArgumentType.greedyString())
                                                .executes(ReplayEvents::renameMarker))))
                        .then(Commands.literal("borrar")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                        .executes(ReplayEvents::removeMarker))))
                .then(Commands.literal("jugador")
                        .then(Commands.literal("start")
                                .executes(c -> startPlayer(c, c.getSource().getPlayerOrException()))
                                .then(Commands.argument("jugador", EntityArgument.player())
                                        .executes(c -> startPlayer(c, EntityArgument.getPlayer(c, "jugador")))))
                        .then(Commands.literal("stop")
                                .executes(c -> stopPlayer(c, c.getSource().getPlayerOrException()))
                                .then(Commands.argument("jugador", EntityArgument.player())
                                        .executes(c -> stopPlayer(c, EntityArgument.getPlayer(c, "jugador")))))));
    }

    // --- Zona --------------------------------------------------------------------------------------

    private static int showZone(CommandContext<CommandSourceStack> c) {
        ReplayZone zone = ReplayZone.load(c.getSource().getServer());
        if (zone == null) {
            c.getSource().sendFailure(Component.literal("No hay zona definida. Usa /replay zona <esquina1> <esquina2>."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Zona de replay: " + zone), false);
        return 1;
    }

    private static int setZone(CommandContext<CommandSourceStack> c) {
        ColumnPos a = ColumnPosArgument.getColumnPos(c, "esquina1");
        ColumnPos b = ColumnPosArgument.getColumnPos(c, "esquina2");
        ReplayZone zone = ReplayZone.of(a.x(), a.z(), b.x(), b.z());
        if (zone.tooBig()) {
            c.getSource().sendFailure(Component.literal("Zona demasiado grande (" + zone + "). Máximo "
                    + ReplayZone.MAX_SIDE_CHUNKS + " chunks (" + ReplayZone.MAX_SIDE_CHUNKS * 16 + " bloques) por lado."));
            return 0;
        }
        if (ZoneRecorder.active() != null) {
            c.getSource().sendFailure(Component.literal("Para antes la grabación en marcha (/replay stop)."));
            return 0;
        }
        try {
            zone.save(c.getSource().getServer());
        } catch (IOException e) {
            c.getSource().sendFailure(Component.literal("No se pudo guardar la zona: " + e.getMessage()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Zona de replay guardada: " + zone), true);
        return 1;
    }

    private static int startZone(CommandContext<CommandSourceStack> c) {
        String error = ZoneRecorder.start(c.getSource().getServer(), "zona_" + ReplayManager.stamp());
        if (error != null) {
            c.getSource().sendFailure(Component.literal(error));
            return 0;
        }
        ZoneRecorder r = ZoneRecorder.active();
        c.getSource().sendSuccess(() -> Component.literal("Grabando la zona " + r.zone()
                + ". El terreno inicial tarda unos segundos (mira /replay estado)."), true);
        return 1;
    }

    private static int stopZone(CommandContext<CommandSourceStack> c) {
        ReplayRecorder recorder = ZoneRecorder.stop();
        if (recorder == null) {
            c.getSource().sendFailure(Component.literal("No hay ninguna grabación de zona en marcha."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Replay de zona guardándose en " + recorder.getOutput()), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ZoneRecorder zone = ZoneRecorder.active();
        if (zone == null) {
            c.getSource().sendSuccess(() -> Component.literal("Zona: no se está grabando."), false);
        } else {
            ReplayRecorder r = zone.recorder();
            c.getSource().sendSuccess(() -> Component.literal("Zona: grabando " + r.elapsedMillis() / 1000 + " s, terreno "
                    + zone.terrainPercent() + " %, " + zone.trackedEntities() + " entidades, "
                    + r.rawBytes() / 1024 + " KB sin comprimir, archivo " + r.getOutput().getFileName()), false);
        }
        for (ReplayViewer v : ReplayViewer.all()) {
            c.getSource().sendSuccess(() -> Component.literal("Visor: " + v.player().getGameProfile().getName()
                    + " ve " + v.name() + " (" + ReplayViewer.time(v.position()) + ")"), false);
        }
        for (ReplayRecorder r : ReplayManager.all()) {
            c.getSource().sendSuccess(() -> Component.literal("Jugador: " + r.getOutput().getFileName()
                    + " (" + r.elapsedMillis() / 1000 + " s)"), false);
        }
        return 1;
    }

    // --- Visor (fase 4) ---------------------------------------------------------------------------

    /** Replays de zona y de ronda guardados ({@code replays/*.mcpr}), los más nuevos primero. */
    private static final SuggestionProvider<CommandSourceStack> REPLAY_FILES = (c, builder) ->
            SharedSuggestionProvider.suggest(replayFiles(c.getSource().getServer()), builder);

    private static java.util.List<String> replayFiles(net.minecraft.server.MinecraftServer server) {
        Path dir = ReplayManager.replaysDir(server);
        if (!Files.isDirectory(dir)) {
            return java.util.List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".mcpr"))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                    .map(p -> p.getFileName().toString().replaceFirst("[.]mcpr$", ""))
                    .toList();
        } catch (IOException e) {
            return java.util.List.of();
        }
    }

    private static int view(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Path dir = ReplayManager.replaysDir(c.getSource().getServer()).toAbsolutePath().normalize();
        Path file = dir.resolve(StringArgumentType.getString(c, "archivo") + ".mcpr").normalize();
        if (!file.getParent().equals(dir) || !Files.isRegularFile(file)) {
            c.getSource().sendFailure(Component.literal("No existe ese replay. Mira las sugerencias de /replay ver."));
            return 0;
        }
        String error = ReplayViewer.start(player, file);
        if (error != null) {
            c.getSource().sendFailure(Component.literal(error));
            return 0;
        }
        return 1;
    }

    // --- Marcadores (solo la grabación de zona en curso) -------------------------------------------

    private static ZoneRecorder recordingOrFail(CommandContext<CommandSourceStack> c) {
        ZoneRecorder zone = ZoneRecorder.active();
        if (zone == null) {
            c.getSource().sendFailure(Component.literal("No hay ninguna grabación de zona en marcha."));
        }
        return zone;
    }

    private static int addMarker(CommandContext<CommandSourceStack> c) {
        ZoneRecorder zone = recordingOrFail(c);
        if (zone == null) {
            return 0;
        }
        String name = StringArgumentType.getString(c, "nombre");
        // Desde un jugador en el Overworld: la cámara del marcador es lo que él ve. Desde consola: el
        // sitio inicial de la cámara del replay.
        if (c.getSource().getEntity() instanceof ServerPlayer player && player.level().dimension() == Level.OVERWORLD) {
            zone.addMarker(MarkerType.MANUAL, name, player.getX(), player.getEyeY(), player.getZ(), player.getYRot(), player.getXRot());
        } else {
            zone.addMarker(MarkerType.MANUAL, name);
        }
        int n = zone.recorder().markers().size();
        c.getSource().sendSuccess(() -> Component.literal("Marcador " + n + " añadido: " + name), true);
        return 1;
    }

    private static int listMarkers(CommandContext<CommandSourceStack> c) {
        ZoneRecorder zone = recordingOrFail(c);
        if (zone == null) {
            return 0;
        }
        List<ReplayRecorder.Marker> markers = zone.recorder().markers();
        if (markers.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("La grabación no tiene marcadores."), false);
            return 1;
        }
        for (int i = 0; i < markers.size(); i++) {
            ReplayRecorder.Marker m = markers.get(i);
            Component line = Component.literal((i + 1) + ". [" + time(m.time()) + "] ")
                    .append(Component.literal(m.name()).withStyle(m.type().color()));
            c.getSource().sendSuccess(() -> line, false);
        }
        return markers.size();
    }

    private static int renameMarker(CommandContext<CommandSourceStack> c) {
        ZoneRecorder zone = recordingOrFail(c);
        if (zone == null) {
            return 0;
        }
        int n = IntegerArgumentType.getInteger(c, "n");
        String name = StringArgumentType.getString(c, "nombre");
        ReplayRecorder.Marker old = zone.recorder().renameMarker(n - 1, name);
        if (old == null) {
            c.getSource().sendFailure(Component.literal("No existe el marcador " + n + " (mira /replay marca lista)."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Marcador " + n + ": «" + old.name() + "» → «" + name + "»"), true);
        return 1;
    }

    private static int removeMarker(CommandContext<CommandSourceStack> c) {
        ZoneRecorder zone = recordingOrFail(c);
        if (zone == null) {
            return 0;
        }
        int n = IntegerArgumentType.getInteger(c, "n");
        ReplayRecorder.Marker old = zone.recorder().removeMarker(n - 1);
        if (old == null) {
            c.getSource().sendFailure(Component.literal("No existe el marcador " + n + " (mira /replay marca lista)."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Marcador " + n + " borrado: " + old.name()
                + ". Los siguientes bajan un número."), true);
        return 1;
    }

    /** Milisegundos desde el inicio como m:ss. */
    private static String time(int millis) {
        int seconds = millis / 1000;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    // --- Jugador concreto -------------------------------------------------------------------------

    private static int startPlayer(CommandContext<CommandSourceStack> c, ServerPlayer target) {
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

    private static int stopPlayer(CommandContext<CommandSourceStack> c, ServerPlayer target) {
        ReplayRecorder recorder = ReplayManager.stop(target.getUUID());
        if (recorder == null) {
            c.getSource().sendFailure(Component.literal("No se estaba grabando a " + target.getGameProfile().getName()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Replay de " + target.getGameProfile().getName()
                + " guardándose en " + recorder.getOutput()), true);
        return 1;
    }

    // --- Eventos ----------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ZoneRecorder.tickActive();
        }
    }

    /** El chat de jugadores no pasa por los ganchos de difusión, así que se graba aparte. */
    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        ZoneRecorder.recordSystemMessage(Component.translatable("chat.type.text",
                event.getPlayer().getDisplayName(), event.getMessage()), false);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ReplayManager.stop(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            ReplayViewer.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ReplayViewer.abortAll();
        ZoneRecorder.stop();
        ReplayManager.stopAll();
    }
}
