package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnchantmentListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.List;
import java.util.Set;

public class AutoTrade extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Set<ResourceKey<Enchantment>>> targetEnchantments = sgGeneral.add(new EnchantmentListSetting.Builder()
            .name("target-enchantment")
            .description("Enchantments to buy. A book must only contain selected enchantments at the selected level.")
            .defaultValue()
            .build()
    );

    private final Setting<Boolean> tradeAll = sgGeneral.add(new BoolSetting.Builder()
            .name("trade-all")
            .description("Keep buying the matching trade while it is in stock and you can afford it.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> tradeLimit = sgGeneral.add(new IntSetting.Builder()
            .name("trade-limit")
            .description("Maximum matching trades to buy when trade-all is disabled.")
            .defaultValue(1)
            .min(1)
            .sliderMax(64)
            .visible(() -> !tradeAll.get())
            .build()
    );

    private static final int INTERACTION_TIMEOUT_TICKS = 40;
    private static final int RESULT_TIMEOUT_TICKS = 40;
    private static final int RESULT_CONFIRMATION_TICKS = 3;
    private static final int TRADE_TIMEOUT_TICKS = 40;

    private enum TradeState {
        IDLE,
        WAITING_FOR_RESULT,
        WAITING_FOR_TRADE,
        FINISHED
    }

    private TradeState tradeState = TradeState.IDLE;
    private MerchantMenu currentMenu;
    private AbstractVillager pendingVillager;
    private AbstractVillager currentVillager;
    private int pendingVillagerTicks;
    private int selectedOfferIndex = -1;
    private int selectedOfferUses;
    private int selectedLevel;
    private int matchingResultTicks;
    private int stateTicks;
    private int completedTrades;

    public AutoTrade() {
        super(MeteorRejectsAddon.CATEGORY, "auto-trade", "Automatically buys the selected enchantment from the villager you open.");
    }

    @Override
    public void onDeactivate() {
        currentMenu = null;
        pendingVillager = null;
        currentVillager = null;
        pendingVillagerTicks = 0;
        resetTradeState();
    }

    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        if (event.entity instanceof AbstractVillager villager) {
            pendingVillager = villager;
            pendingVillagerTicks = INTERACTION_TIMEOUT_TICKS;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (pendingVillagerTicks > 0) pendingVillagerTicks--;
        else pendingVillager = null;

        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            resetCurrentMenu();
            pendingVillager = null;
            pendingVillagerTicks = 0;
            return;
        }

        if (!(mc.player.containerMenu instanceof MerchantMenu menu)) {
            resetCurrentMenu();
            return;
        }

        if (menu != currentMenu) {
            currentMenu = menu;
            currentVillager = pendingVillagerTicks > 0 ? pendingVillager : null;
            resetTradeState();
        }

        AbstractVillager villager = currentVillager;
        if (villager == null || villager.isRemoved() || !villager.isAlive()) return;

        List<MerchantOffer> offers = menu.getOffers();
        if (offers.isEmpty() || targetEnchantments.get().isEmpty() || tradeState == TradeState.FINISHED) return;

        switch (tradeState) {
            case IDLE -> selectMatchingOffer(menu, offers);
            case WAITING_FOR_RESULT -> waitForSelectedResult(menu, offers);
            case WAITING_FOR_TRADE -> waitForTrade(menu, offers, villager);
            case FINISHED -> {
            }
        }
    }

    private void resetCurrentMenu() {
        currentMenu = null;
        currentVillager = null;
        resetTradeState();
    }

    private void resetTradeState() {
        tradeState = TradeState.IDLE;
        selectedOfferIndex = -1;
        selectedOfferUses = 0;
        selectedLevel = 0;
        matchingResultTicks = 0;
        stateTicks = 0;
        completedTrades = 0;
    }

    private void selectMatchingOffer(MerchantMenu menu, List<MerchantOffer> offers) {
        if (selectedLevel == 0) selectedLevel = findHighestMatchingLevel(offers);
        if (selectedLevel == 0) {
            tradeState = TradeState.FINISHED;
            return;
        }

        int matchingOffer = findMatchingOffer(offers, selectedLevel);
        if (matchingOffer < 0) {
            tradeState = TradeState.FINISHED;
            info("The highest-level matching book is out of stock; stopping instead of buying a lower-level book.");
            return;
        }

        MerchantOffer offer = offers.get(matchingOffer);
        if (!canAfford(offer, menu)) {
            tradeState = TradeState.FINISHED;
            info("Cannot afford the highest-level matching book; stopping without trading another offer.");
            return;
        }

        selectedOfferIndex = matchingOffer;
        selectedOfferUses = offer.getUses();
        // Let the server select the offer and populate payment/result slots; local selection
        // hints update the result optimistically and can leave a stale book available to click.
        mc.getConnection().send(new ServerboundSelectTradePacket(matchingOffer));
        tradeState = TradeState.WAITING_FOR_RESULT;
        stateTicks = 0;
        matchingResultTicks = 0;
    }

    private void waitForSelectedResult(MerchantMenu menu, List<MerchantOffer> offers) {
        stateTicks++;
        if (selectedOfferIndex < 0 || selectedOfferIndex >= offers.size()) {
            tradeState = TradeState.FINISHED;
            return;
        }

        MerchantOffer offer = offers.get(selectedOfferIndex);
        ItemStack result = menu.getSlot(2).getItem();
        if (offer.getUses() != selectedOfferUses) {
            tradeState = TradeState.FINISHED;
            info("The selected offer changed before confirmation; stopping without trading.");
            return;
        }

        boolean selectedResultIsReady = !offer.isOutOfStock()
                && offer.satisfiedBy(menu.getSlot(0).getItem(), menu.getSlot(1).getItem())
                && matchesTargetEnchantments(offer.getResult(), selectedLevel)
                && matchesTargetEnchantments(result, selectedLevel)
                && ItemStack.isSameItemSameComponents(result, offer.getResult());

        matchingResultTicks = selectedResultIsReady ? matchingResultTicks + 1 : 0;
        if (matchingResultTicks >= RESULT_CONFIRMATION_TICKS) {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, mc.player);
            tradeState = TradeState.WAITING_FOR_TRADE;
            stateTicks = 0;
            return;
        }

        if (stateTicks >= RESULT_TIMEOUT_TICKS) {
            tradeState = TradeState.FINISHED;
            info("The selected book and payment were not confirmed. No trade was clicked.");
        }
    }

    private void waitForTrade(MerchantMenu menu, List<MerchantOffer> offers, AbstractVillager villager) {
        stateTicks++;
        if (selectedOfferIndex >= 0 && selectedOfferIndex < offers.size()
                && offers.get(selectedOfferIndex).getUses() > selectedOfferUses) {
            completedTrades++;
            if (!tradeAll.get() && completedTrades >= tradeLimit.get()) {
                tradeState = TradeState.FINISHED;
                info("Completed " + completedTrades + " matching trade(s) with the villager at " + villager.blockPosition().toShortString() + ".");
                return;
            }

            tradeState = TradeState.IDLE;
            selectedOfferIndex = -1;
            stateTicks = 0;
            return;
        }

        if (stateTicks >= TRADE_TIMEOUT_TICKS) {
            tradeState = TradeState.FINISHED;
            info("The villager did not confirm the trade; stopping to avoid duplicate purchases.");
        }
    }

    private int findHighestMatchingLevel(List<MerchantOffer> offers) {
        int highest = 0;
        for (MerchantOffer offer : offers) {
            int level = getMatchingLevel(offer.getResult());
            if (level > highest) highest = level;
        }
        return highest;
    }

    private int findMatchingOffer(List<MerchantOffer> offers, int level) {
        for (int i = 0; i < offers.size(); i++) {
            if (!offers.get(i).isOutOfStock() && matchesTargetEnchantments(offers.get(i).getResult(), level)) return i;
        }
        return -1;
    }

    private int getMatchingLevel(ItemStack stack) {
        if (!stack.is(Items.ENCHANTED_BOOK)) return 0;
        ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        Set<ResourceKey<Enchantment>> targets = targetEnchantments.get();
        if (enchantments.size() != 1) return 0;

        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (enchantment.unwrapKey().map(targets::contains).orElse(false)) return enchantments.getLevel(enchantment);
        }
        return 0;
    }

    private boolean matchesTargetEnchantments(ItemStack stack, int level) {
        return getMatchingLevel(stack) == level;
    }

    private boolean canAfford(MerchantOffer offer, MerchantMenu menu) {
        ItemStack[] available = new ItemStack[mc.player.getInventory().getContainerSize() + 2];
        available[0] = menu.getSlot(0).getItem().copy();
        available[1] = menu.getSlot(1).getItem().copy();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            available[i + 2] = mc.player.getInventory().getItem(i).copy();
        }

        if (!consumeCost(offer.getCostA(), available)) return false;
        ItemStack secondCost = offer.getCostB();
        return secondCost.isEmpty() || consumeCost(secondCost, available);
    }

    private boolean consumeCost(ItemStack cost, ItemStack[] available) {
        int remaining = cost.getCount();
        for (ItemStack stack : available) {
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, cost)) continue;
            int used = Math.min(remaining, stack.getCount());
            stack.shrink(used);
            remaining -= used;
            if (remaining == 0) return true;
        }
        return false;
    }

}
