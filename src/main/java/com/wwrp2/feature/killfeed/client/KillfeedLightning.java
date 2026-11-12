package com.wwrp2.feature.killfeed.client;

import com.wwrp2.Wwrp2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Dibuja el rayo durante dos segundos, sin entidades ni lógica de rayos en el mundo. */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class KillfeedLightning {
    private static final List<Flash> FLASHES = new ArrayList<>();

    private KillfeedLightning() {
    }

    public static void show(ResourceLocation dimension, double x, double y, double z) {
        var level = Minecraft.getInstance().level;
        if (!KillfeedChat.isCombatActive() || level == null || !level.dimension().location().equals(dimension)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }
        LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, level);
        bolt.moveTo(x, y, z);
        bolt.setVisualOnly(true);
        bolt.setDamage(0);
        if (FLASHES.size() >= 32) {
            FLASHES.remove(0);
        }
        FLASHES.add(new Flash(bolt));
        level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.5F, 0.9F, false);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || minecraft.isPaused()) {
            return;
        }
        FLASHES.removeIf(flash -> flash.bolt.level() != minecraft.level || --flash.ticks <= 0);
        for (Flash flash : FLASHES) {
            if (flash.ticks % 3 == 0) {
                flash.bolt.seed = ThreadLocalRandom.current().nextLong();
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || FLASHES.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var camera = event.getCamera().getPosition();
        var buffers = minecraft.renderBuffers().bufferSource();
        for (Flash flash : FLASHES) {
            if (flash.bolt.level() == minecraft.level) {
                minecraft.getEntityRenderDispatcher().render(flash.bolt,
                        flash.bolt.getX() - camera.x, flash.bolt.getY() - camera.y, flash.bolt.getZ() - camera.z,
                        0, event.getPartialTick(), event.getPoseStack(), buffers, LightTexture.FULL_BRIGHT);
            }
        }
        buffers.endBatch(RenderType.lightning());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        FLASHES.clear();
    }

    private static final class Flash {
        private final LightningBolt bolt;
        private int ticks = 40;

        private Flash(LightningBolt bolt) {
            this.bolt = bolt;
        }
    }
}
