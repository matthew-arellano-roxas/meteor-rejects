package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;

public class NoContainerInteract extends Module {
    private boolean closeContainerNextTick;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> closeContainerScreens = sgGeneral.add(new BoolSetting.Builder()
            .name("close-container-screens")
            .description("Close server-opened container screens, including virtual chest menus.")
            .defaultValue(true)
            .build()
    );

    public NoContainerInteract() {
        super(MeteorRejectsAddon.CATEGORY, "no-container-interact", "Prevents opening container blocks and server-opened container screens.");
    }

    public boolean shouldBlockInteraction(Block block) {
        return block instanceof BaseEntityBlock || block instanceof CraftingTableBlock;
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!closeContainerScreens.get()
                || !(event.screen instanceof AbstractContainerScreen<?>)
                || event.screen instanceof InventoryScreen
                || mc.player == null
                || mc.player.connection == null
                || mc.player.containerMenu == mc.player.inventoryMenu) {
            return;
        }

        event.cancel();
        closeContainerNextTick = true;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!closeContainerNextTick) return;
        closeContainerNextTick = false;

        if (mc.player == null || mc.player.connection == null || mc.player.containerMenu == mc.player.inventoryMenu) return;
        mc.player.closeContainer();
    }

    @Override
    public void onDeactivate() {
        closeContainerNextTick = false;
    }
}
