package com.wwrp2.feature.replay.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Los registros que se mandan al cliente al entrar (hacen falta para el "Join Game" del replay) y la
 * lista real de jugadores ({@code getPlayers()} de Forge es de solo lectura; el visor saca y vuelve a
 * meter al espectador).
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("synchronizedRegistries")
    RegistryAccess.Frozen wwrp2$getSynchronizedRegistries();

    @Accessor("players")
    List<ServerPlayer> wwrp2$getPlayers();
}
