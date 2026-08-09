package com.yfy.createcinema.display;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.yfy.createcinema.block.ProjectorBlock;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class ProjectorDisplayTarget extends DisplayTarget {
    @Override
    public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        BlockEntity target = context.getTargetBlockEntity();
        if (!(target instanceof ProjectorBlockEntity projector) || !projector.hasDisplayUpgrade()) return;
        if (line == 0) projector.clearDisplayLines();
        int maxRows = provideStats(context).maxRows();
        if (line < 0) return;
        for (int index = 0; index < text.size() && line + index < maxRows; index++) {
            projector.setDisplayLine(line + index, text.get(index));
        }
    }

    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        BlockEntity target = context.getTargetBlockEntity();
        if (!(target instanceof ProjectorBlockEntity projector) || !projector.hasDisplayUpgrade()
                || projector.getLevel() == null) return new DisplayTargetStats(1, 1, this);

        Direction facing = projector.getBlockState().getValue(ProjectorBlock.FACING);
        ProjectionScreenGeometry.ScreenMatrix matrix = ProjectionScreenGeometry.find(
                projector.getLevel(), projector.getBlockPos(), facing);
        return new DisplayTargetStats(Math.max(1, matrix.height() * 2),
                Math.max(1, matrix.width() * 3), this);
    }

}
