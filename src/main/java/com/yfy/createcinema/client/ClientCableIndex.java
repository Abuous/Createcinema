package com.yfy.createcinema.client;

import com.yfy.createcinema.audio.CinemaAudioNetwork;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Event-driven reverse index of the projector/cable/speaker topology. */
public final class ClientCableIndex {
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final Map<BlockPos, Set<String>> CABLE_TO_KEYS = new HashMap<>();
    private static final Set<String> DIRTY = new HashSet<>();
    private static final Set<BlockPos> PROJECTOR_ADJACENT = new HashSet<>();

    private ClientCableIndex() {
    }

    public enum Kind {
        FILM, NETWORK
    }

    public static void ensure(Level level, BlockPos projector, Kind kind) {
        String key = key(level, projector);
        Entry entry = ENTRIES.get(key);
        if (entry != null) return;
        rescan(key, level, projector, kind);
    }

    public static List<BlockPos> speakersOf(Level level, BlockPos projector) {
        String key = key(level, projector);
        Entry entry = ENTRIES.get(key);
        if (entry == null) return List.of();
        return entry.speakers;
    }

    public static void onBlockChanged(LevelAccessor level, BlockPos pos) {
        if (level == null || !level.isClientSide()) return;
        if (level.getClass().getName().startsWith("net.createmod.ponder.")) return;
        BlockPos immutable = pos.immutable();
        Set<String> keys = CABLE_TO_KEYS.get(immutable);
        if (keys != null && !keys.isEmpty()) {
            DIRTY.addAll(keys);
            CreateCinema.LOGGER.info("Cinema audio topology marked dirty at {} for {} projector(s)",
                    immutable, keys.size());
            return;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = immutable.relative(direction);
            BlockState state = level.getBlockState(neighbor);
            if (state.is(ModRegistry.PROJECTOR.get()) || state.is(ModRegistry.NETWORK_PROJECTOR.get())) {
                if (PROJECTOR_ADJACENT.add(neighbor)) {
                    CreateCinema.LOGGER.info("Cinema audio topology found new cable-adjacent projector {} via {}",
                            neighbor, immutable);
                }
            }
        }
    }

    public static void onChunkUnloaded(Level level, ChunkPos chunkPos) {
        if (level == null) return;
        Set<String> affected = new HashSet<>();
        for (Entry entry : ENTRIES.values()) {
            boolean inChunk = entry.projector.getX() >> 4 == chunkPos.x && entry.projector.getZ() >> 4 == chunkPos.z;
            if (!inChunk) {
                for (BlockPos cable : entry.cables) {
                    if (cable.getX() >> 4 == chunkPos.x && cable.getZ() >> 4 == chunkPos.z) {
                        inChunk = true;
                        break;
                    }
                }
            }
            if (inChunk) affected.add(key(level, entry.projector));
        }
        DIRTY.addAll(affected);
    }

    public static void tick(Level level) {
        if (level == null) return;
        if (!PROJECTOR_ADJACENT.isEmpty()) {
            for (BlockPos pos : PROJECTOR_ADJACENT) DIRTY.add(keyFor(pos, level.dimension()));
            PROJECTOR_ADJACENT.clear();
        }
        if (DIRTY.isEmpty()) return;
        Set<String> dirty = new HashSet<>(DIRTY);
        DIRTY.clear();
        for (String key : dirty) {
            Entry entry = ENTRIES.get(key);
            if (entry == null) continue;
            if (level.getBlockEntity(entry.projector) == null) {
                remove(key);
                continue;
            }
            rescan(key, level, entry.projector, entry.kind);
        }
    }

    public static void remove(Level level, BlockPos projector) {
        if (level == null) return;
        remove(keyFor(projector, level.dimension()));
    }

    public static void removeAll() {
        ENTRIES.clear();
        CABLE_TO_KEYS.clear();
        DIRTY.clear();
        PROJECTOR_ADJACENT.clear();
    }

    private static void remove(String key) {
        Entry entry = ENTRIES.remove(key);
        if (entry == null) return;
        for (BlockPos cable : entry.cables) {
            Set<String> keys = CABLE_TO_KEYS.get(cable);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) CABLE_TO_KEYS.remove(cable);
            }
        }
        DIRTY.remove(key);
    }

    private static void rescan(String key, Level level, BlockPos projector, Kind kind) {
        Entry previous = ENTRIES.get(key);
        CinemaAudioNetwork.Topology topology = CinemaAudioNetwork.scan(level, projector);
        Entry entry = new Entry(kind, projector, topology.cables(), topology.speakers());
        Set<BlockPos> removed = new HashSet<>();
        Set<BlockPos> gained = new HashSet<>();
        if (previous != null) {
            removed.addAll(previous.speakers);
            removed.removeAll(entry.speakers);
            gained.addAll(entry.speakers);
            gained.removeAll(previous.speakers);
            for (BlockPos cable : previous.cables) {
                if (!entry.cables.contains(cable)) {
                    Set<String> keys = CABLE_TO_KEYS.get(cable);
                    if (keys != null) {
                        keys.remove(key);
                        if (keys.isEmpty()) CABLE_TO_KEYS.remove(cable);
                    }
                }
            }
        } else {
            gained.addAll(entry.speakers);
        }
        for (BlockPos cable : entry.cables) {
            CABLE_TO_KEYS.computeIfAbsent(cable, ignored -> new HashSet<>()).add(key);
        }
        ENTRIES.put(key, entry);
        if (!removed.isEmpty() || !gained.isEmpty()) {
            CreateCinema.LOGGER.info("Cinema audio topology {} at {} changed: removed={}, gained={}",
                    kind, projector, removed, gained);
        }
        notify(level, key, entry, removed, gained);
    }

    private static void notify(Level level, String key, Entry entry, Set<BlockPos> removed, Set<BlockPos> gained) {
        if (removed.isEmpty() && gained.isEmpty()) return;
        if (entry.kind == Kind.FILM) {
            ClientProjectorAudio.notifySpeakers(level, entry.projector, removed, gained);
        } else {
            ClientNetworkProjectorAudio.notifySpeakers(level, entry.projector, removed, gained);
        }
    }

    private static String key(Level level, BlockPos projector) {
        return keyFor(projector, level.dimension());
    }

    private static String keyFor(BlockPos projector, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return dimension.location() + "/" + projector.asLong();
    }

    private static final class Entry {
        private final Kind kind;
        private final BlockPos projector;
        private final Set<BlockPos> cables;
        private final List<BlockPos> speakers;

        private Entry(Kind kind, BlockPos projector, Set<BlockPos> cables, List<BlockPos> speakers) {
            this.kind = kind;
            this.projector = projector.immutable();
            this.cables = new HashSet<>(cables);
            this.speakers = new ArrayList<>(speakers);
        }
    }
}
