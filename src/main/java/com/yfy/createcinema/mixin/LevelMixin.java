package com.yfy.createcinema.mixin;

import com.yfy.createcinema.block.ScreenBlock;
import com.yfy.createcinema.client.ClientNetworkProjectorAudio;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void createcinema$screenBlockRemoved(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                 CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!level.isClientSide) return;
        BlockState previous = level.getBlockState(pos);
        if (!(previous.getBlock() instanceof ScreenBlock) || state.is(previous.getBlock())) return;
        ClientNetworkProjectorAudio.screensMayHaveChanged();
    }
}