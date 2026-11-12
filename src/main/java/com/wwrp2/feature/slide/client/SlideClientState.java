package com.wwrp2.feature.slide.client;

import com.wwrp2.Wwrp2;
import com.wwrp2.feature.slide.SlideRules;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Qué jugadores se están deslizando según este cliente, y cuánto de la animación lleva cada
 * uno. El propio jugador entra aquí en cuanto empieza ({@link SlideController}); los demás,
 * cuando llega el paquete del servidor.
 *
 * <p>La animación no salta de golpe: cada jugador tiene una mezcla de 0 (postura normal) a 1
 * (deslizándose) que sube y baja en unos pocos ticks, y con ella se dibujan la postura
 * ({@link SlidePlayerModel}) y la cámara ({@link SlideCameraEffects}).</p>
 */
@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)
public final class SlideClientState {
    /** La postura entra en 3 ticks y sale en 4. */
    private static final float BLEND_IN_PER_TICK = 1.0F / 3.0F;
    private static final float BLEND_OUT_PER_TICK = 1.0F / 4.0F;
    private static final int DUST_PARTICLES_PER_TICK = 2;

    /** Por id de entidad. */
    private static final Int2ObjectMap<Animation> ANIMATIONS = new Int2ObjectOpenHashMap<>();

    private static final class Animation {
        boolean sliding;
        int ticks;
        float blend;
        float previousBlend;
    }

    private SlideClientState() {
    }

    public static boolean isSliding(Entity entity) {
        Animation animation = ANIMATIONS.get(entity.getId());
        return animation != null && animation.sliding;
    }

    /** Cuánto de la postura de deslizamiento hay que dibujar en este frame, de 0 a 1 y ya suavizado. */
    public static float blend(Entity entity, float partialTick) {
        Animation animation = ANIMATIONS.get(entity.getId());
        if (animation == null) {
            return 0.0F;
        }
        float linear = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), animation.previousBlend, animation.blend);
        return linear * linear * (3.0F - 2.0F * linear);
    }

    /** Llega del servidor: otro jugador empieza o termina, o el servidor corta el del propio jugador. */
    public static void onServerState(int entityId, boolean sliding) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (minecraft.player != null && minecraft.player.getId() == entityId) {
            // El propio jugador se anima sin esperar al servidor; solo importa si se lo corta.
            if (!sliding) {
                SlideController.stopFromServer();
            }
            return;
        }
        setSliding(entityId, sliding, minecraft.level.getEntity(entityId));
    }

    static void setSliding(Entity entity, boolean sliding) {
        setSliding(entity.getId(), sliding, entity);
    }

    private static void setSliding(int entityId, boolean sliding, Entity entity) {
        Animation animation = ANIMATIONS.get(entityId);
        if (animation == null) {
            if (!sliding) {
                return;
            }
            animation = new Animation();
            ANIMATIONS.put(entityId, animation);
        }
        if (animation.sliding == sliding) {
            return;
        }
        animation.sliding = sliding;
        animation.ticks = 0;
        if (sliding && entity != null) {
            playStartSound(entity);
        }
    }

    static void clear() {
        ANIMATIONS.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ANIMATIONS.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }

        ObjectIterator<Int2ObjectMap.Entry<Animation>> iterator = ANIMATIONS.int2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<Animation> entry = iterator.next();
            Animation animation = entry.getValue();
            Entity entity = minecraft.level.getEntity(entry.getIntKey());
            boolean local = entity != null && entity == minecraft.player;

            // Si el final de otro jugador no llega (se ha ido lejos, lag), no se queda deslizándose para siempre.
            if (animation.sliding && !local && ++animation.ticks > SlideRules.TIMEOUT_TICKS) {
                animation.sliding = false;
            }
            animation.previousBlend = animation.blend;
            animation.blend = animation.sliding
                    ? Math.min(1.0F, animation.blend + BLEND_IN_PER_TICK)
                    : Math.max(0.0F, animation.blend - BLEND_OUT_PER_TICK);

            if (entity instanceof Player player) {
                if (!local) {
                    syncPose(player, animation.sliding);
                }
                if (animation.sliding) {
                    tickSlidingPlayer(player, local);
                }
            }
            if (!animation.sliding && animation.blend <= 0.0F) {
                iterator.remove();
            }
        }
    }

    /**
     * Cada cliente calcula por su cuenta la postura de los demás jugadores, así que la hitbox
     * de agachado hay que forzarla también aquí. La del propio jugador la lleva {@link SlideController}.
     */
    private static void syncPose(Player player, boolean sliding) {
        if (sliding) {
            if (player.getForcedPose() == null) {
                player.setForcedPose(Pose.CROUCHING);
            }
        } else if (player.getForcedPose() == Pose.CROUCHING) {
            player.setForcedPose(null);
        }
    }

    private static void tickSlidingPlayer(Player player, boolean local) {
        // Sin esto las piernas y los brazos seguirían dando zancadas, y muy deprisa.
        player.walkAnimation.setSpeed(0.0F);
        if (local) {
            // Ni la cámara cabecea como al andar.
            player.bob = 0.0F;
        }
        if (player.onGround()) {
            spawnDust(player);
        }
    }

    /** Polvo del bloque que se pisa, como el de esprintar (Entity#spawnSprintParticle). */
    private static void spawnDust(Player player) {
        Level level = player.level();
        BlockPos pos = player.getOnPos();
        BlockState state = level.getBlockState(pos);
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;
        }
        // Lo que se ha movido en este tick; la velocidad de los demás jugadores no llega al cliente.
        double movedX = player.getX() - player.xo;
        double movedZ = player.getZ() - player.zo;
        for (int i = 0; i < DUST_PARTICLES_PER_TICK; i++) {
            double x = player.getX() + (player.getRandom().nextDouble() - 0.5) * player.getBbWidth();
            double z = player.getZ() + (player.getRandom().nextDouble() - 0.5) * player.getBbWidth();
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state).setPos(pos),
                    x, player.getY() + 0.1, z, movedX * -2.0, 1.0, movedZ * -2.0);
        }
    }

    /** El sonido de pisar el bloque de debajo, más grave: suena a arrastrarse por él. */
    private static void playStartSound(Entity entity) {
        Level level = entity.level();
        BlockPos pos = entity.getOnPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        SoundType sound = state.getSoundType(level, pos, entity);
        level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), sound.getStepSound(), SoundSource.PLAYERS,
                sound.getVolume() * 0.5F, sound.getPitch() * 0.6F, false);
    }
}
