package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.schematic.ISchematic;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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

    public ChunkLayerBuilder() {
        super(MeteorRejectsAddon.CATEGORY, "chunk-layer-builder", "Uses Baritone to build block layers in the current chunk.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) {
            error("You must be in a world to start building.");
            toggle();
            return;
        }

        selectedBlock = block.get();
        if (!InvUtils.find(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == selectedBlock).found()) {
            error("No " + selectedBlock.getName().getString() + " found in your inventory.");
            toggle();
            return;
        }

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
        startLayerBuild(nextLayerY);
        info("Started building layers using " + selectedBlock.getName().getString() + " in chunk "
                + mc.player.chunkPosition().x + ", " + mc.player.chunkPosition().z + ".");
    }

    @Override
    public void onDeactivate() {
        if (buildStage != BuildStage.IDLE && buildStage != BuildStage.COMPLETE
                && baritone != null && baritone.getBuilderProcess().isActive()) {
            baritone.getPathingBehavior().cancelEverything();
        }
        buildStage = BuildStage.IDLE;
        baritone = null;
        chunkOrigin = null;
        selectedBlock = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (baritone == null || buildStage == BuildStage.IDLE || buildStage == BuildStage.COMPLETE
                || baritone.getBuilderProcess().isActive()) return;

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
