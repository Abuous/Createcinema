package com.yfy.createcinema.client;

import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.block.CableBlock;
import com.yfy.createcinema.block.NetworkProjectorBlock;
import com.yfy.createcinema.block.ProjectorBlock;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class CreateCinemaPonderScenes {
    private CreateCinemaPonderScenes() {
    }

    public static void filmBurning(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("film_burning", "刻录机和胶卷");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        BlockPos burner = util.grid().at(1, 1, 2);
        scene.world().setBlock(burner, ModRegistry.BURNER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(burner), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(70).text("刻录机把本地视频写入空胶卷。先放入空胶卷，再输入视频路径。")
                .pointAt(util.vector().topOf(burner)).placeNearTarget();
        scene.overlay().showControls(util.vector().topOf(burner), Pointing.DOWN, 45)
                .rightClick().withItem(new ItemStack(ModRegistry.FILM.get()));
        scene.idle(80);
        scene.overlay().showText(80).text("选择刻录画质并开始处理。刻录期间胶卷会留在刻录机中。")
                .pointAt(util.vector().centerOf(burner)).placeNearTarget();
        scene.idle(90);
        scene.overlay().showText(70).text("完成后取出胶卷。刻录机只制作胶卷，胶卷需要放入动力放映机播放。")
                .colored(PonderPalette.BLUE).pointAt(util.vector().topOf(burner)).placeNearTarget();
        scene.idle(80);
    }

    public static void projectorSetup(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("projector_setup", "动力放映机和幕布");
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        BlockPos projector = util.grid().at(1, 1, 3);
        scene.world().setBlock(projector, projector(Direction.EAST), false);
        scene.world().showSection(util.select().position(projector), Direction.DOWN);
        for (int z = 2; z <= 4; z++) {
            for (int y = 1; y <= 3; y++) {
                scene.world().setBlock(util.grid().at(5, y, z), ModRegistry.SCREEN.get().defaultBlockState(), false);
            }
        }
        scene.world().showSection(util.select().fromTo(5, 1, 2, 5, 3, 4), Direction.DOWN);
        scene.idle(20);
        scene.overlay().showText(80).text("动力放映机会沿朝向寻找前方幕布。有效距离是 1 到 16 格。")
                .pointAt(util.vector().centerOf(4, 2, 3)).placeNearTarget();
        scene.overlay().showLine(PonderPalette.BLUE, util.vector().centerOf(projector), util.vector().centerOf(5, 2, 3), 60);
        scene.idle(90);
        scene.overlay().showText(90).text("幕布可以由白幕和黑幕混合组成。放映机会识别连成一片的幕布矩阵。")
                .pointAt(util.vector().centerOf(5, 2, 3)).placeNearTarget();
        scene.idle(100);
        scene.overlay().showText(80).text("放映机需要有效转速。64 rpm 为 1 倍速，过载或 0 rpm 会停止播放。")
                .colored(PonderPalette.MEDIUM).pointAt(util.vector().centerOf(6, 1, 3)).placeNearTarget();
        scene.idle(90);
        scene.overlay().showText(70).text("右侧升级槽每槽仅安装一张升级卡，供放映机扩展功能使用。")
                .colored(PonderPalette.BLUE).pointAt(util.vector().topOf(projector)).placeNearTarget();
        scene.idle(80);
    }

    public static void networkAudio(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("network_audio", "网络放映机和幕布");
        scene.configureBasePlate(0, 0, 10);
        scene.showBasePlate();
        BlockPos projector = util.grid().at(1, 1, 3);
        scene.world().setBlock(projector, networkProjector(Direction.EAST), false);
        scene.world().showSection(util.select().position(projector), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(70).text("右键打开网络放映机，输入视频网址并保存。")
                .pointAt(util.vector().topOf(projector)).placeNearTarget();
        scene.overlay().showControls(util.vector().topOf(projector), Pointing.DOWN, 45)
                .rightClick();
        scene.idle(80);
        for (int z = 2; z <= 4; z++) {
            for (int y = 1; y <= 3; y++) {
                scene.world().setBlock(util.grid().at(6, y, z), ModRegistry.SCREEN.get().defaultBlockState(), false);
            }
        }
        scene.world().showSection(util.select().fromTo(6, 1, 2, 6, 3, 4), Direction.DOWN);
        scene.overlay().showText(80).text("在前方搭建幕布矩阵，白幕和黑幕可以混合连接。网络放映机也支持画面投射。")
                .pointAt(util.vector().centerOf(7, 2, 3)).placeNearTarget();
        scene.overlay().showLine(PonderPalette.BLUE, util.vector().centerOf(projector),
                util.vector().centerOf(6, 2, 3), 60);
        scene.idle(90);
        scene.world().setBlock(util.grid().at(1, 1, 4), cable(Direction.NORTH, Direction.EAST), false);
        scene.world().setBlock(util.grid().at(2, 1, 4), cable(Direction.WEST, Direction.EAST), false);
        scene.world().setBlock(util.grid().at(3, 1, 4), cable(Direction.WEST, Direction.EAST), false);
        BlockPos speaker = util.grid().at(4, 1, 4);
        scene.world().setBlock(speaker, ModRegistry.SPEAKER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(1, 1, 4, 4, 1, 4), Direction.DOWN);
        scene.overlay().showText(80).text("用线缆连接音箱到网络放映机。音箱所在位置会播放空间音频。")
                .pointAt(util.vector().topOf(speaker)).placeNearTarget();
        scene.idle(90);
        scene.world().setBlock(util.grid().at(4, 1, 5), Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        scene.world().showSection(util.select().position(4, 1, 5), Direction.DOWN);
        scene.overlay().showText(90).text("音箱音量由红石强度 0 到 15 线性控制。0 是静音，但音频流继续推进。")
                .colored(PonderPalette.RED).pointAt(util.vector().centerOf(4, 1, 5)).placeNearTarget();
        scene.idle(100);
        scene.overlay().showText(80).text("装入连播升级后，可识别分 P 或剧集目录，并依次播放到最后一项。")
                .colored(PonderPalette.BLUE).pointAt(util.vector().topOf(projector)).placeNearTarget();
        scene.overlay().showControls(util.vector().topOf(projector), Pointing.DOWN, 45)
                .rightClick().withItem(new ItemStack(ModRegistry.CONTINUOUS_PLAY_UPGRADE.get()));
        scene.idle(90);
    }

    private static BlockState projector(Direction facing) {
        return ModRegistry.PROJECTOR.get().defaultBlockState().setValue(ProjectorBlock.FACING, facing);
    }

    private static BlockState networkProjector(Direction facing) {
        return ModRegistry.NETWORK_PROJECTOR.get().defaultBlockState().setValue(NetworkProjectorBlock.FACING, facing);
    }

    private static BlockState cable(Direction... connections) {
        BlockState state = ModRegistry.CABLE.get().defaultBlockState();
        for (Direction direction : connections) {
            state = state.setValue(CableBlock.PROPERTY_BY_DIRECTION.get(direction), true);
        }
        return state;
    }
}
