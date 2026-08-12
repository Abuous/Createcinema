package com.yfy.createcinema.client.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ClientPhysicalAudioCompat {
    private static final Object SABLE_HELPER;
    private static final Method GET_CONTAINING_CLIENT;
    private static final Method LOGICAL_POSE;
    private static final Method TRANSFORM_POSITION;

    static {
        Object helper = null;
        Method getContainingClient = null;
        Method logicalPose = null;
        Method transformPosition = null;
        try {
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            Field helperField = sable.getField("HELPER");
            helper = helperField.get(null);
            getContainingClient = helper.getClass().getMethod("getContainingClient", BlockEntity.class);
            Class<?> clientSubLevel = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            logicalPose = clientSubLevel.getMethod("logicalPose");
            Class<?> pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
            transformPosition = pose.getMethod("transformPosition", Vector3d.class);
        } catch (ReflectiveOperationException ignored) {
            helper = null;
            getContainingClient = null;
            logicalPose = null;
            transformPosition = null;
        }
        SABLE_HELPER = helper;
        GET_CONTAINING_CLIENT = getContainingClient;
        LOGICAL_POSE = logicalPose;
        TRANSFORM_POSITION = transformPosition;
    }

    private ClientPhysicalAudioCompat() {
    }

    static Vec3 worldPosition(BlockEntity owner, BlockPos pos) {
        Vec3 fallback = Vec3.atCenterOf(pos);
        if (SABLE_HELPER == null) return fallback;
        try {
            Object subLevel = GET_CONTAINING_CLIENT.invoke(SABLE_HELPER, owner);
            if (subLevel == null) return fallback;
            Object pose = LOGICAL_POSE.invoke(subLevel);
            Vector3d transformed = new Vector3d(fallback.x, fallback.y, fallback.z);
            TRANSFORM_POSITION.invoke(pose, transformed);
            return new Vec3(transformed.x, transformed.y, transformed.z);
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }
}
