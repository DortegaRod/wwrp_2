package com.wwrp2.feature.replay.client;

import com.wwrp2.feature.replay.ReplayStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * Menú del visor de replays: línea de tiempo con los highlights (clic para saltar), controles, la
 * lista de highlights y la de jugadores (clic para llevar la cámara a uno). No hace nada por su cuenta: cada botón manda un comando del visor
 * ({@code /replay ir ...}) y lo que se ve sale del último {@link ReplayStatePacket}.
 */
public final class ReplayScreen extends Screen {
    private static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f, 8f};
    private static final int LINE = 11;
    private static final int MAX_LINES = 6;
    private static final int BAR_HEIGHT = 8;
    /** Distancia en píxeles a la que un clic en la barra cuenta como clic en un highlight. */
    private static final int MARKER_GRAB = 3;

    private int left, top, panelWidth, panelHeight;
    private int barX, barY, barWidth;
    private int listY;
    private int scroll;
    private int playerScroll;
    private Button pauseButton;

    public ReplayScreen() {
        super(Component.literal("Replay"));
    }

    @Override
    protected void init() {
        ReplayClientState.menuWanted = true;
        this.panelWidth = Math.min(this.width - 20, 380);
        this.panelHeight = 82 + LINE * (MAX_LINES + 1);
        this.left = (this.width - this.panelWidth) / 2;
        this.top = this.height - this.panelHeight - 8;
        int inner = this.panelWidth - 16;
        this.barX = this.left + 8;
        this.barY = this.top + 20;
        this.barWidth = inner;

        String[] labels = {"« 10s", "Pausa", "10s »", "Vel. -", "Vel. +", "Inicio", "Salir"};
        int gap = 2;
        int buttonWidth = (inner - gap * (labels.length - 1)) / labels.length;
        int y = this.barY + BAR_HEIGHT + 8;
        for (int i = 0; i < labels.length; i++) {
            int x = this.barX + i * (buttonWidth + gap);
            int index = i;
            Button button = Button.builder(Component.literal(labels[i]), b -> pressed(index))
                    .bounds(x, y, buttonWidth, 20).build();
            addRenderableWidget(button);
            if (i == 1) {
                this.pauseButton = button;
            }
        }
        this.listY = y + 26;
    }

    private void pressed(int button) {
        ReplayStatePacket state = ReplayClientState.state();
        if (state == null) {
            return;
        }
        switch (button) {
            case 0 -> command("replay atras 10");
            case 1 -> command("replay pausa");
            case 2 -> command("replay adelante 10");
            case 3 -> changeSpeed(state, -1);
            case 4 -> changeSpeed(state, 1);
            case 5 -> command("replay reiniciar");
            case 6 -> {
                onClose();
                command("replay salir");
            }
            default -> {
            }
        }
    }

    /** Sube ({@code direction > 0}) o baja la velocidad un paso de {@link #SPEEDS}. */
    private static void changeSpeed(ReplayStatePacket state, int direction) {
        float speed = state.speed();
        if (direction > 0) {
            for (float s : SPEEDS) {
                if (s > speed + 0.001f) {
                    command("replay velocidad " + s);
                    return;
                }
            }
        } else {
            for (int i = SPEEDS.length - 1; i >= 0; i--) {
                if (SPEEDS[i] < speed - 0.001f) {
                    command("replay velocidad " + SPEEDS[i]);
                    return;
                }
            }
        }
    }

    /** Atajos con el menú abierto: espacio pausa, R reinicia, A/D ±10 s, J cierra. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ReplayClientState.state() != null) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_SPACE -> {
                    command("replay pausa");
                    return true;
                }
                case GLFW.GLFW_KEY_R -> {
                    command("replay reiniciar");
                    return true;
                }
                case GLFW.GLFW_KEY_A -> {
                    command("replay atras 10");
                    return true;
                }
                case GLFW.GLFW_KEY_D -> {
                    command("replay adelante 10");
                    return true;
                }
                default -> {
                }
            }
            if (ReplayClientState.OPEN_MENU.matches(keyCode, scanCode)) {
                onClose();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static void command(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }

    @Override
    public void tick() {
        ReplayStatePacket state = ReplayClientState.state();
        if (state == null) {
            onClose();
            return;
        }
        this.pauseButton.setMessage(Component.literal(state.paused() ? "Seguir" : "Pausa"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ReplayStatePacket state = ReplayClientState.state();
        if (state == null) {
            return;
        }
        g.fill(this.left, this.top, this.left + this.panelWidth, this.top + this.panelHeight, 0xC0101010);

        // Cabecera: nombre a la izquierda; tiempo, velocidad y estado a la derecha.
        g.drawString(this.font, state.name(), this.barX, this.top + 6, 0x55FF55);
        String info = time(state.position()) + " / " + time(state.duration())
                + (state.speed() != 1f ? "  x" + trim(state.speed()) : "")
                + (state.seeking() ? "  saltando..." : state.paused() ? "  pausa" : "");
        g.drawString(this.font, info, this.barX + this.barWidth - this.font.width(info), this.top + 6, 0xFFFF55);

        String keys = "Espacio: pausa  R: inicio  A/D: \u00b110 s  Rueda: velocidad  J: cerrar";
        g.drawString(this.font, keys, this.left + (this.panelWidth - this.font.width(keys)) / 2,
                this.top + this.panelHeight - 10, 0x777777);

        // Línea de tiempo.
        int played = (int) ((long) this.barWidth * state.position() / Math.max(1, state.duration()));
        g.fill(this.barX, this.barY, this.barX + this.barWidth, this.barY + BAR_HEIGHT, 0xFF3A3A3A);
        g.fill(this.barX, this.barY, this.barX + Math.min(played, this.barWidth), this.barY + BAR_HEIGHT, 0xFF9A9A9A);
        List<ReplayStatePacket.Entry> highlights = state.highlights();
        for (ReplayStatePacket.Entry h : highlights) {
            int x = markerX(state, h);
            g.fill(x - 1, this.barY - 3, x + 1, this.barY + BAR_HEIGHT + 3, color(h));
        }
        int head = this.barX + Math.min(played, this.barWidth);
        g.fill(head - 1, this.barY - 4, head + 1, this.barY + BAR_HEIGHT + 4, 0xFFFFFFFF);

        // Columna izquierda: highlights (clic para ir).
        int column = columnWidth();
        g.drawString(this.font, highlights.isEmpty() ? "Sin highlights" : "Highlights", this.barX, this.listY, 0xAAAAAA);
        this.scroll = Math.max(0, Math.min(this.scroll, highlights.size() - MAX_LINES));
        for (int i = 0; i < MAX_LINES && this.scroll + i < highlights.size(); i++) {
            ReplayStatePacket.Entry h = highlights.get(this.scroll + i);
            int y = this.listY + LINE * (i + 1);
            if (inside(mouseX, mouseY, this.barX, y - 1, column, LINE)) {
                g.fill(this.barX - 2, y - 1, this.barX + column + 2, y + LINE - 1, 0x40FFFFFF);
            }
            String when = "[" + time(h.time()) + "] ";
            g.drawString(this.font, when, this.barX, y, 0xAAAAAA);
            g.drawString(this.font, this.font.plainSubstrByWidth(h.name(), column - this.font.width(when)),
                    this.barX + this.font.width(when), y, color(h));
        }
        if (highlights.size() > MAX_LINES) {
            String more = (this.scroll + 1) + "-" + Math.min(highlights.size(), this.scroll + MAX_LINES)
                    + "/" + highlights.size();
            g.drawString(this.font, more, this.barX + column - this.font.width(more), this.listY, 0x777777);
        }

        // Columna derecha: jugadores que están ahora en el replay (clic para llevar la cámara).
        List<String> players = state.players();
        int playersX = playersX();
        g.drawString(this.font, players.isEmpty() ? "Sin jugadores ahora" : "Jugadores (clic: ir, der.: POV)",
                playersX, this.listY, 0xAAAAAA);
        this.playerScroll = Math.max(0, Math.min(this.playerScroll, players.size() - MAX_LINES));
        for (int i = 0; i < MAX_LINES && this.playerScroll + i < players.size(); i++) {
            int y = this.listY + LINE * (i + 1);
            if (inside(mouseX, mouseY, playersX, y - 1, column, LINE)) {
                g.fill(playersX - 2, y - 1, playersX + column + 2, y + LINE - 1, 0x40FFFFFF);
            }
            String name = players.get(this.playerScroll + i);
            boolean followed = name.equalsIgnoreCase(state.following());
            g.drawString(this.font, this.font.plainSubstrByWidth((followed ? "\u25b6 " : "") + name, column), playersX, y,
                    followed ? 0xFFFF55 : 0x55FFFF);
        }
        if (players.size() > MAX_LINES) {
            String more = (this.playerScroll + 1) + "-" + Math.min(players.size(), this.playerScroll + MAX_LINES)
                    + "/" + players.size();
            g.drawString(this.font, more, playersX + column - this.font.width(more), this.listY, 0x777777);
        }

        super.render(g, mouseX, mouseY, partialTick);

        // Al pasar el ratón por la barra: el highlight que hay debajo o el tiempo.
        if (inside(mouseX, mouseY, this.barX, this.barY - 4, this.barWidth, BAR_HEIGHT + 8)) {
            ReplayStatePacket.Entry h = markerAt(state, mouseX);
            Component tip = h != null
                    ? Component.literal("[" + time(h.time()) + "] " + h.name()).withStyle(h.type().color())
                    : Component.literal(time(timeAt(state, mouseX)));
            g.renderTooltip(this.font, tip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ReplayStatePacket state = ReplayClientState.state();
        if (state != null && button == 1) {
            int column = columnWidth();
            List<String> players = state.players();
            for (int i = 0; i < MAX_LINES && this.playerScroll + i < players.size(); i++) {
                int y = this.listY + LINE * (i + 1);
                if (inside(mouseX, mouseY, playersX(), y - 1, column, LINE)) {
                    String name = players.get(this.playerScroll + i);
                    command(name.equalsIgnoreCase(state.following()) ? "replay dejar" : "replay seguir " + name);
                    return true;
                }
            }
        }
        if (state != null && button == 0) {
            if (inside(mouseX, mouseY, this.barX, this.barY - 4, this.barWidth, BAR_HEIGHT + 8)) {
                ReplayStatePacket.Entry h = markerAt(state, mouseX);
                if (h != null) {
                    command("replay highlight " + (state.highlights().indexOf(h) + 1));
                } else {
                    command(String.format(Locale.ROOT, "replay ir %.1f", timeAt(state, mouseX) / 1000.0));
                }
                return true;
            }
            int column = columnWidth();
            for (int i = 0; i < MAX_LINES && this.scroll + i < state.highlights().size(); i++) {
                int y = this.listY + LINE * (i + 1);
                if (inside(mouseX, mouseY, this.barX, y - 1, column, LINE)) {
                    command("replay highlight " + (this.scroll + i + 1));
                    return true;
                }
            }
            List<String> players = state.players();
            for (int i = 0; i < MAX_LINES && this.playerScroll + i < players.size(); i++) {
                int y = this.listY + LINE * (i + 1);
                if (inside(mouseX, mouseY, playersX(), y - 1, column, LINE)) {
                    command("replay jugador " + players.get(this.playerScroll + i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        ReplayStatePacket state = ReplayClientState.state();
        if (mouseY >= this.listY && mouseY < this.top + this.panelHeight
                && mouseX >= this.left && mouseX < this.left + this.panelWidth) {
            // Encima de las listas: moverlas.
            if (mouseX >= playersX() - 4) {
                this.playerScroll -= (int) Math.signum(delta);
            } else {
                this.scroll -= (int) Math.signum(delta);
            }
        } else if (state != null && delta != 0) {
            changeSpeed(state, delta > 0 ? 1 : -1); // en cualquier otro sitio: velocidad
        }
        return true;
    }

    /** Ancho de cada una de las dos columnas (highlights y jugadores). */
    private int columnWidth() {
        return (this.barWidth - 12) / 2;
    }

    private int playersX() {
        return this.barX + columnWidth() + 12;
    }

    @Override
    public void onClose() {
        ReplayClientState.menuWanted = false;
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int markerX(ReplayStatePacket state, ReplayStatePacket.Entry h) {
        return this.barX + (int) ((long) this.barWidth * h.time() / Math.max(1, state.duration()));
    }

    /** El highlight más cercano al ratón en la barra, si está a menos de {@link #MARKER_GRAB} px. */
    private ReplayStatePacket.Entry markerAt(ReplayStatePacket state, double mouseX) {
        ReplayStatePacket.Entry best = null;
        double bestDistance = MARKER_GRAB + 0.5;
        for (ReplayStatePacket.Entry h : state.highlights()) {
            double distance = Math.abs(markerX(state, h) - mouseX);
            if (distance < bestDistance) {
                best = h;
                bestDistance = distance;
            }
        }
        return best;
    }

    private int timeAt(ReplayStatePacket state, double mouseX) {
        double fraction = Math.max(0, Math.min(1, (mouseX - this.barX) / this.barWidth));
        return (int) (fraction * state.duration());
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int color(ReplayStatePacket.Entry h) {
        Integer rgb = h.type().color().getColor();
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }

    private static String trim(float speed) {
        return speed == (int) speed ? Integer.toString((int) speed) : Float.toString(speed);
    }

    private static String time(int millis) {
        int seconds = millis / 1000;
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }
}
