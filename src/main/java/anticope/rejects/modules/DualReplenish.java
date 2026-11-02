package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.ItemStack;

public class DualReplenish extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> firstSlot = sgGeneral.add(new IntSetting.Builder()
            .name("first-slot")
            .description("First hotbar slot to alternate between (1-9).")
            .defaultValue(1)
            .range(1, 9)
            .sliderRange(1, 9)
            .build()
    );

    private final Setting<Integer> secondSlot = sgGeneral.add(new IntSetting.Builder()
            .name("second-slot")
            .description("Second hotbar slot to alternate between (1-9).")
            .defaultValue(2)
            .range(1, 9)
            .sliderRange(1, 9)
            .build()
    );

    private final Setting<Integer> refillThreshold = sgGeneral.add(new IntSetting.Builder()
            .name("refill-threshold")
            .description("Switch to the other stack and refill the inactive one when it reaches this count.")
            .defaultValue(8)
            .min(1)
            .sliderRange(1, 32)
            .max(63)
            .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Ticks between refill checks.")
            .defaultValue(2)
            .min(1)
            .sliderRange(1, 10)
            .build()
    );

    private int ticksUntilCheck;

    public DualReplenish() {
        super(MeteorRejectsAddon.CATEGORY, "dual-replenish", "Alternates between two hotbar stacks and replenishes the inactive stack.");
    }

    @Override
    public void onActivate() {
        ticksUntilCheck = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        int first = firstSlot.get() - 1;
        int second = secondSlot.get() - 1;
        if (first == second) return;

        int active = mc.player.getInventory().getSelectedSlot();
        if (active != first && active != second) return;

        if (ticksUntilCheck > 0) {
            ticksUntilCheck--;
            return;
        }
        ticksUntilCheck = delay.get();

        int inactive = active == first ? second : first;
        ItemStack activeStack = mc.player.getInventory().getItem(active);
        ItemStack inactiveStack = mc.player.getInventory().getItem(inactive);

        if (shouldSwitch(activeStack, inactiveStack)) {
            mc.player.getInventory().setSelectedSlot(inactive);
            activeStack = mc.player.getInventory().getItem(inactive);
            inactiveStack = mc.player.getInventory().getItem(active);
            int previousActive = active;
            active = inactive;
            inactive = previousActive;
        }

        ItemStack refillItem = inactiveStack.isEmpty() ? activeStack : inactiveStack;
        if (refillItem.isEmpty() || refillItem.getCount() > refillThreshold.get()) return;
        if (!activeStack.isEmpty() && !ItemStack.isSameItemSameComponents(activeStack, refillItem)) return;

        FindItemResult source = InvUtils.find(
                stack -> ItemStack.isSameItemSameComponents(stack, refillItem),
                9,
                35
        );
        if (source.found()) InvUtils.move().from(source.slot()).toHotbar(inactive);
    }

    private boolean shouldSwitch(ItemStack active, ItemStack inactive) {
        if (inactive.isEmpty()) return false;
        if (active.isEmpty()) return true;
        return active.getCount() <= refillThreshold.get()
                && inactive.getCount() > refillThreshold.get()
                && ItemStack.isSameItemSameComponents(active, inactive);
    }
}
