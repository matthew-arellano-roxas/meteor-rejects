package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public class EventListener extends Module {
    private final SettingGroup sgRefill = settings.createGroup("Refill");
    private final SettingGroup sgTools = settings.createGroup("Tool break");
    private final SettingGroup sgHunger = settings.createGroup("Hunger");
    private final SettingGroup sgCommands = settings.createGroup("Commands");

    private final Setting<Boolean> refillEnabled = sgRefill.add(new BoolSetting.Builder()
            .name("enabled")
            .description("Run commands when selected inventory items are running low.")
            .defaultValue(true)
            .build()
    );

    private final Setting<List<Item>> monitoredItems = sgRefill.add(new ItemListSetting.Builder()
            .name("items")
            .description("Items to count in your inventory for the refill trigger.")
            .defaultValue()
            .build()
    );

    private final Setting<Integer> refillThreshold = sgRefill.add(new IntSetting.Builder()
            .name("threshold")
            .description("Run refill commands when the total selected items in your inventory is at or below this count.")
            .defaultValue(16)
            .min(0)
            .sliderMax(64)
            .visible(refillEnabled::get)
            .build()
    );

    private final Setting<Boolean> toolBreakEnabled = sgTools.add(new BoolSetting.Builder()
            .name("enabled")
            .description("Run commands when a monitored held tool is close to breaking.")
            .defaultValue(true)
            .build()
    );

    private final Setting<List<Item>> monitoredTools = sgTools.add(new ItemListSetting.Builder()
            .name("tools")
            .description("Tools to monitor. Leave empty to monitor any damageable item held in your main hand.")
            .defaultValue()
            .visible(toolBreakEnabled::get)
            .build()
    );

    private final Setting<Integer> toolDurabilityThreshold = sgTools.add(new IntSetting.Builder()
            .name("durability-percent")
            .description("Run the tool commands when remaining durability is at or below this percentage.")
            .defaultValue(10)
            .min(1)
            .max(100)
            .sliderMax(50)
            .visible(toolBreakEnabled::get)
            .build()
    );

    private final Setting<Boolean> hungerEnabled = sgHunger.add(new BoolSetting.Builder()
            .name("enabled")
            .description("Run commands when hunger reaches the configured threshold.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> hungerThreshold = sgHunger.add(new IntSetting.Builder()
            .name("hunger-threshold")
            .description("Run the hunger commands when hunger is at or below this level (out of 20).")
            .defaultValue(6)
            .min(0)
            .max(20)
            .sliderMax(20)
            .visible(hungerEnabled::get)
            .build()
    );

    private final Setting<List<String>> refillCommands = sgCommands.add(new StringListSetting.Builder()
            .name("refill-commands")
            .description("Commands to run when selected item supply is low. Every non-empty entry is sent in order.")
            .defaultValue()
            .build()
    );

    private final Setting<List<String>> toolCommands = sgCommands.add(new StringListSetting.Builder()
            .name("tool-break-commands")
            .description("Commands to run when the held tool is low on durability. Every non-empty entry is sent in order.")
            .defaultValue()
            .build()
    );

    private final Setting<List<String>> hungerCommands = sgCommands.add(new StringListSetting.Builder()
            .name("hunger-commands")
            .description("Commands to run when hunger is low. Every non-empty entry is sent in order.")
            .defaultValue()
            .build()
    );

    private final Queue<String> pendingCommands = new ArrayDeque<>();
    private boolean refillLow;
    private boolean toolLow;
    private boolean hungerLow;
    private int commandDelay;

    public EventListener() {
        super(MeteorRejectsAddon.CATEGORY, "event-listener", "Runs configured chat commands when item supply, tool durability, or hunger reaches a threshold.");
    }

    @Override
    public void onActivate() {
        pendingCommands.clear();
        refillLow = false;
        toolLow = false;
        hungerLow = false;
        commandDelay = 0;
    }

    @Override
    public void onDeactivate() {
        pendingCommands.clear();
        refillLow = false;
        toolLow = false;
        hungerLow = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getConnection() == null) return;

        boolean lowRefill = refillEnabled.get() && isItemSupplyLow();
        boolean lowTool = toolBreakEnabled.get() && isHeldToolLow();
        boolean lowHunger = hungerEnabled.get() && mc.player.getFoodData().getFoodLevel() <= hungerThreshold.get();

        if (lowRefill && !refillLow) enqueue(refillCommands.get());
        if (lowTool && !toolLow) enqueue(toolCommands.get());
        if (lowHunger && !hungerLow) enqueue(hungerCommands.get());

        refillLow = lowRefill;
        toolLow = lowTool;
        hungerLow = lowHunger;

        if (commandDelay > 0) {
            commandDelay--;
            return;
        }

        String command = pendingCommands.poll();
        if (command != null) {
            ChatUtils.sendPlayerMsg(command);
            commandDelay = 20;
        }
    }

    private boolean isItemSupplyLow() {
        List<Item> items = monitoredItems.get();
        if (items.isEmpty()) return false;

        int count = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (items.contains(stack.getItem())) count += stack.getCount();
        }
        return count <= refillThreshold.get();
    }

    private boolean isHeldToolLow() {
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !held.isDamageableItem()) return false;
        if (!monitoredTools.get().isEmpty() && !monitoredTools.get().contains(held.getItem())) return false;

        int remainingDurability = held.getMaxDamage() - held.getDamageValue();
        int remainingPercent = remainingDurability * 100 / held.getMaxDamage();
        return remainingPercent <= toolDurabilityThreshold.get();
    }

    private void enqueue(List<String> commands) {
        for (String command : commands) {
            if (command != null && !command.isBlank()) pendingCommands.add(command.trim());
        }
    }
}
