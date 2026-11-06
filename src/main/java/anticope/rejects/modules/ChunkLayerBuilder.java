package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.schematic.ISchematic;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ChunkLayerBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Block> block = sgGeneral.add(new BlockSetting.Builder()
            .name("block")
            .description("Block to use for the layers.")
            .defaultValue(Blocks.COBBLESTONE)
            .filter(value -> value.asItem() != Items.AIR)
            .build()
    );

    private final Setting<Boolean> disableWhenOutOfBlocks = sgGeneral.add(new BoolSetting.Builder()
            .name("disable-when-out-of-blocks")
            .description("Turn off the module if no selected blocks arrive before the wait timeout.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> continueAfterRejoin = sgGeneral.add(new BoolSetting.Builder()
            .name("continue-after-rejoin")
            .description("Keep the current build stage and resume it after reconnecting to a server.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> outOfBlocksTimeout = sgGeneral.add(new IntSetting.Builder()
            .name("out-of-blocks-timeout")
            .description("How many seconds to wait for blocks before disabling the module.")
            .defaultValue(120)
            .min(1)
            .sliderMax(300)
            .visible(disableWhenOutOfBlocks::get)
            .build()
    );

    private final Setting<Integer> resumeDelay = sgGeneral.add(new IntSetting.Builder()
            .name("resume-delay")
            .description("How many seconds to wait after blocks are ready before resuming the build.")
            .defaultValue(5)
            .min(0)
            .sliderMax(30)
            .build()
    );

    private final Setting<Integer> layerSpacing = sgGeneral.add(new IntSetting.Builder()
            .name("gap-blocks")
            .description("Number of empty blocks between layer floors.")
            .defaultValue(2)
            .min(0)
            .sliderMax(8)
            .build()
    );

    private final Setting<Integer> maximumY = sgGeneral.add(new IntSetting.Builder()
            .name("maximum-y")
            .description("Highest Y level to build a layer at.")
            .defaultValue(100)
            .min(-64)
            .max(320)
            .sliderMin(-64)
            .sliderMax(320)
            .build()
    );

    private IBaritone baritone;
    private BuildStage buildStage = BuildStage.IDLE;
    private BlockPos chunkOrigin;
    private Block selectedBlock;
    private int startY;
    private int maximumBuildY;
    private int nextLayerY;
    private int currentLayerY;
    private int detectedSpacing;
    private int blockHotbarSlot = -1;
    private boolean outOfBlocksNotified;
    private boolean waitingForBlocks;
    private boolean waitingForRefill;
    private boolean reconnectPending;
    private boolean recoveryWarningNotified;
    private Boolean allowBreakBeforeRecovery;
    private List<Block> allowBreakAnywayBeforeRecovery;
    private RecoveryPhase recoveryPhase = RecoveryPhase.NONE;
    private List<BlockPos> recoveryBases = List.of();
    private int recoveryCandidateIndex;
    private int recoveryPathTicks;
    private boolean blockSwapPending;
    private int blockSwapWaitTicks;
    private int outOfBlocksTicks;
    private int resumeDelayTicks;
    private BlockPos recoveryTowerBase;
    private int recoveryTowerTargetFeetY;

    private static final int BLOCK_SWAP_TIMEOUT_TICKS = 20;
    private static final int RECOVERY_PATH_TIMEOUT_TICKS = 600;
    public ChunkLayerBuilder() {
        super(MeteorRejectsAddon.CATEGORY, "chunk-layer-builder", "Uses Baritone to build block layers in the current chunk.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) {
            error("You must be in a world to start building.");
            toggle();
            return;
        }

        selectedBlock = block.get();
        startY = mc.player.blockPosition().getY() - 1;
        maximumBuildY = maximumY.get();
        if (maximumBuildY < startY || maximumBuildY >= mc.level.getMaxY()) {
            error("Maximum Y must be at or above your current Y and below the world's build limit.");
            toggle();
            return;
        }

        detectedSpacing = layerSpacing.get();

        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone.getBuilderProcess().isActive()) {
            error("Baritone is already running a build. Stop it before starting this module.");
            toggle();
            return;
        }

        int chunkX = mc.player.chunkPosition().x << 4;
        int chunkZ = mc.player.chunkPosition().z << 4;
        chunkOrigin = new BlockPos(chunkX, startY, chunkZ);
        nextLayerY = startY;
        buildStage = BuildStage.IDLE;
        waitingForBlocks = !hasSelectedBlock() || !refillBlockHotbar();
        waitingForRefill = false;
        reconnectPending = false;
        recoveryPhase = RecoveryPhase.NONE;
        recoveryWarningNotified = false;
        allowBreakBeforeRecovery = null;
        allowBreakAnywayBeforeRecovery = null;
        recoveryBases = List.of();
        recoveryCandidateIndex = 0;
        recoveryPathTicks = 0;
        recoveryTowerBase = null;
        resumeDelayTicks = 0;
        outOfBlocksTicks = 0;
        outOfBlocksNotified = false;
        if (waitingForBlocks) {
            info("Waiting for " + selectedBlock.getName().getString() + " in your inventory before starting.");
            return;
        }

        beginBuild();
    }

    @Override
    public void onDeactivate() {
        if (buildStage != BuildStage.IDLE && buildStage != BuildStage.COMPLETE
                && baritone != null && baritone.getBuilderProcess().isActive()) {
            baritone.getPathingBehavior().cancelEverything();
        }
        restoreAllowBreakAfterRecovery();
        buildStage = BuildStage.IDLE;
        baritone = null;
        chunkOrigin = null;
        selectedBlock = null;
        blockHotbarSlot = -1;
        outOfBlocksNotified = false;
        waitingForBlocks = false;
        waitingForRefill = false;
        reconnectPending = false;
        recoveryPhase = RecoveryPhase.NONE;
        recoveryWarningNotified = false;
        recoveryBases = List.of();
        recoveryCandidateIndex = 0;
        recoveryPathTicks = 0;
        recoveryTowerBase = null;
        blockSwapPending = false;
        blockSwapWaitTicks = 0;
        outOfBlocksTicks = 0;
        resumeDelayTicks = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        if (!continueAfterRejoin.get()) {
            toggle();
            return;
        }

        if (buildStage != BuildStage.IDLE && buildStage != BuildStage.COMPLETE) {
            if (baritone != null) baritone.getPathingBehavior().cancelEverything();
            restoreAllowBreakAfterRecovery();
            baritone = null;
            reconnectPending = true;
            resumeDelayTicks = 0;
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        if (!continueAfterRejoin.get()) {
            toggle();
            return;
        }

        if (reconnectPending) {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            resumeDelayTicks = 0;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null || selectedBlock == null) return;

        if (buildStage == BuildStage.IDLE && waitingForBlocks) {
            if (!refillBlockHotbar()) {
                if (hasSelectedBlock()) {
                    outOfBlocksTicks = 0;
                    return;
                }
                handleMissingBlocks();
                return;
            }
            waitingForBlocks = false;
            outOfBlocksNotified = false;
            outOfBlocksTicks = 0;
            beginBuild();
            return;
        }

        if (reconnectPending) {
            if (baritone == null) baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (!refillBlockHotbar()) {
                if (!hasSelectedBlock()) handleMissingBlocks();
                return;
            }
            if (++resumeDelayTicks < resumeDelay.get() * 20) return;

            resumeDelayTicks = 0;
            reconnectPending = false;
            waitingForRefill = false;
            baritone.getPathingBehavior().cancelEverything();
            if (recoveryPhase == RecoveryPhase.PATHING_TO_CORNER && recoveryTowerBase != null) {
                preventBreakingForRecovery();
                recoveryPathTicks = 0;
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(recoveryTowerBase.above()));
            } else if (recoveryPhase == RecoveryPhase.BUILDING_TOWER && recoveryTowerBase != null) {
                preventBreakingForRecovery();
                startRecoveryTower();
            } else if (recoveryPhase == RecoveryPhase.CLIMBING_TOWER && recoveryTowerBase != null) {
                preventBreakingForRecovery();
                recoveryPathTicks = 0;
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(recoveryTowerTarget()));
            } else {
                resumeCurrentStage();
            }
            return;
        }

        if (baritone == null || buildStage == BuildStage.IDLE || buildStage == BuildStage.COMPLETE) return;

        boolean resumeDelayElapsed = false;
        if (refillBlockHotbar()) {
            outOfBlocksNotified = false;
            outOfBlocksTicks = 0;
            if (waitingForRefill) {
                if (++resumeDelayTicks < resumeDelay.get() * 20) return;
                waitingForRefill = false;
                resumeDelayTicks = 0;
                resumeDelayElapsed = true;
                if (baritone.getBuilderProcess().isActive()) {
                    baritone.getBuilderProcess().resume();
                    return;
                }
            }
        } else if (!hasSelectedBlock()) {
            waitForRefill();
            handleMissingBlocks();
            return;
        } else {
            waitForRefill();
            outOfBlocksTicks = 0;
            return;
        }

        if (baritone.getBuilderProcess().isActive()) {
            if (!baritone.getBuilderProcess().isPaused()) return;

            if (recoveryPhase != RecoveryPhase.NONE) {
                restoreAllowBreakAfterRecovery();
                if (!recoveryWarningNotified) {
                    warning("Could not finish the recovery tower without breaking blocks. Check that the selected blocks can be placed here.");
                    recoveryWarningNotified = true;
                }
                return;
            }

            int targetFeetY = getCurrentStageTargetFeetY();
            if (mc.player.onGround() && mc.player.blockPosition().getY() < targetFeetY) {
                startFallRecovery(targetFeetY);
            } else if (!recoveryWarningNotified) {
                warning("Baritone is paused and cannot continue this stage. Move to a safe position with selected blocks available.");
                recoveryWarningNotified = true;
            }
            return;
        }

        if (recoveryPhase != RecoveryPhase.NONE) {
            tickFallRecovery();
            return;
        }

        if (!isCurrentStageComplete()) {
            if (!resumeDelayElapsed) {
                if (!waitingForRefill) {
                    waitingForRefill = true;
                    resumeDelayTicks = 0;
                }
                if (++resumeDelayTicks < resumeDelay.get() * 20) return;
                waitingForRefill = false;
                resumeDelayTicks = 0;
            }
            resumeCurrentStage();
            return;
        }

        if (buildStage == BuildStage.LAYER) {
            nextLayerY = currentLayerY + detectedSpacing + 1;
            if (nextLayerY <= maximumBuildY) {
                if (detectedSpacing > 0) startTowerSegment();
                else startLayerBuild(nextLayerY);
            } else {
                buildStage = BuildStage.COMPLETE;
                info("Finished all chunk layers.");
            }
        } else if (buildStage == BuildStage.TOWER_SEGMENT) {
            startLayerBuild(nextLayerY);
        }
    }

    private void beginBuild() {
        startLayerBuild(nextLayerY);
        info("Started building layers using " + selectedBlock.getName().getString() + " in chunk "
                + (chunkOrigin.getX() >> 4) + ", " + (chunkOrigin.getZ() >> 4) + ".");
    }

    private void handleMissingBlocks() {
        outOfBlocksTicks++;
        if (outOfBlocksNotified) return;

        if (disableWhenOutOfBlocks.get()) {
            if (outOfBlocksTicks >= outOfBlocksTimeout.get() * 20) {
                outOfBlocksNotified = true;
                error("No " + selectedBlock.getName().getString() + " arrived within " + outOfBlocksTimeout.get() + " seconds. Disabling.");
                toggle();
            }
        } else if (outOfBlocksTicks >= 40) {
            outOfBlocksNotified = true;
            warning("Out of " + selectedBlock.getName().getString() + ". Add more to your inventory to continue building.");
        }
    }

    private void waitForRefill() {
        if (waitingForRefill) return;
        waitingForRefill = true;
        resumeDelayTicks = 0;
        if (baritone != null && baritone.getBuilderProcess().isActive()) {
            baritone.getBuilderProcess().pause();
        }
    }

    private int getCurrentStageTargetFeetY() {
        if (buildStage == BuildStage.TOWER_SEGMENT) return nextLayerY;
        return currentLayerY + 1;
    }

    private void startFallRecovery(int targetFeetY) {
        recoveryTowerTargetFeetY = targetFeetY;
        recoveryBases = findRecoveryBases(targetFeetY);
        recoveryCandidateIndex = 0;
        recoveryWarningNotified = false;
        if (recoveryBases.isEmpty()) {
            failFallRecovery("No clear, supported corner was found inside this chunk.");
            return;
        }

        preventBreakingForRecovery();
        if (baritone.getBuilderProcess().isActive()) baritone.getPathingBehavior().cancelEverything();
        tryNextRecoveryCorner();
    }

    private List<BlockPos> findRecoveryBases(int targetFeetY) {
        if (mc.player == null || mc.level == null || chunkOrigin == null) return List.of();

        int[] cornerXs = { chunkOrigin.getX() + 1, chunkOrigin.getX() + 14 };
        int[] cornerZs = { chunkOrigin.getZ() + 1, chunkOrigin.getZ() + 14 };
        int playerFeetY = mc.player.blockPosition().getY();
        int lowestBaseY = Math.max(mc.level.getMinY(), playerFeetY - 8);
        List<BlockPos> bases = new ArrayList<>();

        for (int x : cornerXs) {
            for (int z : cornerZs) {
                for (int baseY = playerFeetY - 1; baseY >= lowestBaseY; baseY--) {
                    BlockPos base = new BlockPos(x, baseY, z);
                    if (targetFeetY - baseY <= 1) continue;
                    if (!mc.level.getBlockState(base).isFaceSturdy(mc.level, base, Direction.UP)) continue;

                    boolean clearColumn = true;
                    boolean needsBlocks = false;
                    for (int y = baseY + 1; y < targetFeetY; y++) {
                        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
                        if (!state.isAir() && !state.is(selectedBlock)) {
                            clearColumn = false;
                            break;
                        }
                        if (state.isAir()) needsBlocks = true;
                    }
                    if (clearColumn && needsBlocks) bases.add(base);
                }
            }
        }

        BlockPos playerPos = mc.player.blockPosition();
        bases.sort(Comparator.comparingDouble(base -> {
            double dx = base.getX() - playerPos.getX();
            double dy = base.getY() + 1 - playerPos.getY();
            double dz = base.getZ() - playerPos.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));
        return bases;
    }

    private void tryNextRecoveryCorner() {
        if (recoveryCandidateIndex >= recoveryBases.size()) {
            failFallRecovery("Could not path to any clear corner inside this chunk without breaking blocks.");
            return;
        }

        recoveryTowerBase = recoveryBases.get(recoveryCandidateIndex++);
        recoveryPathTicks = 0;
        recoveryPhase = RecoveryPhase.PATHING_TO_CORNER;
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(recoveryTowerBase.above()));
        info("Pathing to a clear corner inside the chunk for a recovery tower.");
    }

    private void tickFallRecovery() {
        if (mc.player == null || baritone == null) return;

        if (recoveryPhase == RecoveryPhase.FAILED) {
            if (mc.player.blockPosition().getY() >= recoveryTowerTargetFeetY) resetFallRecovery();
            return;
        }

        if (recoveryPhase == RecoveryPhase.PATHING_TO_CORNER) {
            if (mc.player.blockPosition().equals(recoveryTowerBase.above())) {
                baritone.getPathingBehavior().cancelEverything();
                recoveryPhase = RecoveryPhase.BUILDING_TOWER;
                startRecoveryTower();
                info("Building a recovery tower inside the chunk without breaking blocks.");
                return;
            }

            recoveryPathTicks++;
            if (recoveryPathTicks >= RECOVERY_PATH_TIMEOUT_TICKS
                    || (recoveryPathTicks >= 20 && !baritone.getCustomGoalProcess().isActive())) {
                baritone.getPathingBehavior().cancelEverything();
                tryNextRecoveryCorner();
            }
            return;
        }

        if (recoveryPhase == RecoveryPhase.CLIMBING_TOWER) {
            if (mc.player.blockPosition().getY() >= recoveryTowerTargetFeetY && mc.player.onGround()) {
                baritone.getPathingBehavior().cancelEverything();
                resetFallRecovery();
                resumeCurrentStage();
                return;
            }

            recoveryPathTicks++;
            if (recoveryPathTicks >= RECOVERY_PATH_TIMEOUT_TICKS
                    || (recoveryPathTicks >= 20 && !baritone.getCustomGoalProcess().isActive())) {
                failFallRecovery("Could not climb the recovery tower to the unfinished layer without breaking blocks.");
            }
            return;
        }

        if (recoveryPhase == RecoveryPhase.BUILDING_TOWER) {
            if (baritone.getBuilderProcess().isActive()) {
                if (!baritone.getBuilderProcess().isPaused()) return;
                failFallRecovery("Baritone could not finish the recovery tower. Check block availability and placement access.");
                return;
            }

            if (!isRecoveryTowerComplete()) {
                failFallRecovery("The recovery tower is incomplete; no further path was found.");
                return;
            }

            recoveryPhase = RecoveryPhase.CLIMBING_TOWER;
            recoveryPathTicks = 0;
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(recoveryTowerTarget()));
        }
    }

    private BlockPos recoveryTowerTarget() {
        return new BlockPos(recoveryTowerBase.getX(), recoveryTowerTargetFeetY, recoveryTowerBase.getZ());
    }

    private void startRecoveryTower() {
        int height = recoveryTowerTargetFeetY - recoveryTowerBase.getY();
        baritone.getBuilderProcess().build(
                "Chunk layer recovery tower to Y " + recoveryTowerTargetFeetY,
                new TowerSegmentSchematic(selectedBlock, height),
                recoveryTowerBase
        );
    }

    private void preventBreakingForRecovery() {
        if (allowBreakBeforeRecovery == null) {
            allowBreakBeforeRecovery = BaritoneAPI.getSettings().allowBreak.value;
            allowBreakAnywayBeforeRecovery = new ArrayList<>(BaritoneAPI.getSettings().allowBreakAnyway.value);
        }
        BaritoneAPI.getSettings().allowBreak.value = false;
        BaritoneAPI.getSettings().allowBreakAnyway.value = new ArrayList<>();
    }

    private void failFallRecovery(String message) {
        restoreAllowBreakAfterRecovery();
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        recoveryPhase = RecoveryPhase.FAILED;
        if (!recoveryWarningNotified) {
            warning(message);
            recoveryWarningNotified = true;
        }
    }

    private void resetFallRecovery() {
        restoreAllowBreakAfterRecovery();
        recoveryPhase = RecoveryPhase.NONE;
        recoveryWarningNotified = false;
        recoveryTowerBase = null;
    }

    private void restoreAllowBreakAfterRecovery() {
        if (allowBreakBeforeRecovery == null) return;
        BaritoneAPI.getSettings().allowBreak.value = allowBreakBeforeRecovery;
        BaritoneAPI.getSettings().allowBreakAnyway.value = allowBreakAnywayBeforeRecovery;
        allowBreakBeforeRecovery = null;
        allowBreakAnywayBeforeRecovery = null;
    }

    private boolean isRecoveryTowerComplete() {
        if (mc.level == null || recoveryTowerBase == null) return false;
        for (int y = recoveryTowerBase.getY() + 1; y < recoveryTowerTargetFeetY; y++) {
            if (!mc.level.getBlockState(new BlockPos(recoveryTowerBase.getX(), y, recoveryTowerBase.getZ())).is(selectedBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSelectedBlock() {
        if (mc.player == null || selectedBlock == null) return false;
        for (int i = 0; i < 36; i++) {
            if (isSelectedBlock(mc.player.getInventory().getItem(i))) return true;
        }
        return false;
    }

    private int findBlockHotbarSlot() {
        if (mc.player == null || selectedBlock == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isSelectedBlock(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean isSelectedBlock(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == selectedBlock;
    }

    private boolean refillBlockHotbar() {
        int existingSlot = findBlockHotbarSlot();
        if (existingSlot >= 0) {
            blockHotbarSlot = existingSlot;
            if (mc.player != null && mc.player.getInventory().getSelectedSlot() != existingSlot) {
                mc.player.getInventory().setSelectedSlot(existingSlot);
            }
            blockSwapPending = false;
            blockSwapWaitTicks = 0;
            return true;
        }
        if (mc.player == null || mc.gameMode == null || !(mc.player.containerMenu instanceof InventoryMenu)) return false;

        if (blockSwapPending) {
            if (++blockSwapWaitTicks < BLOCK_SWAP_TIMEOUT_TICKS) return false;
            blockSwapPending = false;
            blockSwapWaitTicks = 0;
        }

        int inventorySlot = -1;
        for (int i = 9; i < 36; i++) {
            if (isSelectedBlock(mc.player.getInventory().getItem(i))) {
                inventorySlot = i;
                break;
            }
        }
        if (inventorySlot < 0) return false;

        if (blockHotbarSlot < 0 || blockHotbarSlot > 8) {
            blockHotbarSlot = mc.player.getInventory().getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).isEmpty()) {
                    blockHotbarSlot = i;
                    break;
                }
            }
        }

        mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId,
                inventorySlot,
                blockHotbarSlot,
                ClickType.SWAP,
                mc.player
        );
        blockSwapPending = true;
        blockSwapWaitTicks = 0;
        return false;
    }

    private boolean isCurrentStageComplete() {
        if (mc.level == null || chunkOrigin == null || selectedBlock == null) return false;
        if (buildStage == BuildStage.LAYER) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (mc.level.getBlockState(new BlockPos(chunkOrigin.getX() + x, currentLayerY, chunkOrigin.getZ() + z)).isAir()) return false;
                }
            }
            return true;
        }

        if (buildStage == BuildStage.TOWER_SEGMENT) {
            for (int y = 1; y < detectedSpacing + 1; y++) {
                if (mc.level.getBlockState(new BlockPos(chunkOrigin.getX(), currentLayerY + y, chunkOrigin.getZ())).isAir()) return false;
            }
            return true;
        }
        return false;
    }

    private void resumeCurrentStage() {
        if (buildStage == BuildStage.LAYER) {
            startLayerBuild(currentLayerY);
        } else if (buildStage == BuildStage.TOWER_SEGMENT) {
            startTowerSegment();
        }
    }

    private void startTowerSegment() {
        ISchematic towerSegment = new TowerSegmentSchematic(selectedBlock, detectedSpacing + 1);
        BlockPos segmentOrigin = new BlockPos(chunkOrigin.getX(), currentLayerY, chunkOrigin.getZ());
        baritone.getBuilderProcess().build("Chunk corner tower to Y " + nextLayerY, towerSegment, segmentOrigin);
        buildStage = BuildStage.TOWER_SEGMENT;
    }

    private void startLayerBuild(int layerY) {
        currentLayerY = layerY;
        BlockPos layerOrigin = new BlockPos(chunkOrigin.getX(), layerY, chunkOrigin.getZ());
        ISchematic layer = new LayerSchematic(selectedBlock);
        baritone.getBuilderProcess().build("Chunk layer at Y " + layerY, layer, layerOrigin);
        buildStage = BuildStage.LAYER;
    }

    private enum BuildStage {
        IDLE,
        LAYER,
        TOWER_SEGMENT,
        COMPLETE
    }

    private enum RecoveryPhase {
        NONE,
        PATHING_TO_CORNER,
        BUILDING_TOWER,
        CLIMBING_TOWER,
        FAILED
    }

    private static class TowerSegmentSchematic implements ISchematic {
        private final Block block;
        private final int spacing;

        private TowerSegmentSchematic(Block block, int spacing) {
            this.block = block;
            this.spacing = spacing;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            if (x != 0 || z != 0 || y <= 0 || y >= spacing) return false;
            return currentState == null || currentState.isAir() || currentState.is(block);
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            return block.defaultBlockState();
        }

        @Override
        public int widthX() {
            return 1;
        }

        @Override
        public int heightY() {
            return spacing;
        }

        @Override
        public int lengthZ() {
            return 1;
        }
    }

    private static class LayerSchematic implements ISchematic {
        private final Block block;

        private LayerSchematic(Block block) {
            this.block = block;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            if (x < 0 || x >= 16 || z < 0 || z >= 16 || y != 0) return false;
            return currentState == null || currentState.isAir() || currentState.is(block);
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            return block.defaultBlockState();
        }

        @Override
        public int widthX() {
            return 16;
        }

        @Override
        public int heightY() {
            return 1;
        }

        @Override
        public int lengthZ() {
            return 16;
        }
    }
}
