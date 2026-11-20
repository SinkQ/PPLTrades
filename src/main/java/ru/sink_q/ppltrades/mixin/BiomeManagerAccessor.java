package ru.sink_q.ppltrades.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Хэш сида мира, который сервер присылает клиенту (у каждого мира PepeLand свой). */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
    @Accessor("biomeZoomSeed")
    long ppltrades$getBiomeZoomSeed();
}
