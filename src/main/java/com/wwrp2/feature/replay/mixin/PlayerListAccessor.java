package com.wwrp2.feature.replay.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Los registros que se mandan al cliente al entrar (hacen falta para el "Join Game" del replay). */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("synchronizedRegistries")
    RegistryAccess.Frozen wwrp2$getSynchronizedRegistries();
}
