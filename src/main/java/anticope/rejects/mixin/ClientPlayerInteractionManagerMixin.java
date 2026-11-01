package anticope.rejects.mixin;

import anticope.rejects.events.StopUsingItemEvent;
import anticope.rejects.modules.NoContainerInteract;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void rejectContainerInteraction(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        NoContainerInteract module = Modules.get().get(NoContainerInteract.class);
        Block block = player.level().getBlockState(hit.getBlockPos()).getBlock();
        if (module != null && module.isActive() && module.shouldBlockInteraction(block)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    public void onStopUsingItem(Player player, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(StopUsingItemEvent.get(player.getInventory().getSelectedItem()));
    }
}