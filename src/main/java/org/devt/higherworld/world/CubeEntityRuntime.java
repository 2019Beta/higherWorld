package org.devt.higherworld.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LoadedEntityProcessor;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.Entity.RemovalReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.world.entity.EntityChangeListener;
import org.devt.higherworld.storage.CubeEntityIndex;
import org.devt.higherworld.storage.CubeEntityRecord;
import org.devt.higherworld.storage.CubeEntityStorage;
import org.devt.higherworld.storage.CubePos;

/**
 * Server-thread owner of ordinary entities outside the vanilla height band.
 *
 * <p>The runtime keeps serialized records for both pending and live entities.
 * A pending record is only materialized when the caller proves that its cube is
 * FULL and ticketed; this prevents world-start restoration from creating a
 * second copy while the vanilla/cube lifecycle is still converging.</p>
 */
public final class CubeEntityRuntime implements AutoCloseable {
    private final ServerWorld world;
    private final CubeEntityStorage storage;
    private CubeEntityIndex index = new CubeEntityIndex();
    private final Map<UUID, Entity> roots = new HashMap<>();
    private final Map<UUID, Entity> entitiesByUuid = new HashMap<>();
    private final Map<UUID, Tracker> trackers = new HashMap<>();
    private boolean loaded;
    private boolean dirty;
    private boolean closed;
    private boolean internalRemoval;

    public CubeEntityRuntime(ServerWorld world, CubeEntityStorage storage) {
        this.world = world;
        this.storage = storage;
    }

    /** Loads HWE1 records but deliberately leaves entity construction pending. */
    public synchronized void load() throws IOException {
        ensureOpen();
        index = storage.load();
        roots.clear();
        entitiesByUuid.clear();
        trackers.clear();
        loaded = true;
        dirty = false;
    }

    /** Adds a newly spawned non-player entity to this runtime. */
    public synchronized boolean add(Entity entity) {
        ensureLoaded();
        if (!isManaged(entity)) return false;
        Entity root = entity.getRootVehicle();
        if (root.isPlayer()) return false;
        if (!root.streamSelfAndPassengers().allMatch(candidate -> !candidate.isPlayer())) return false;
        UUID rootUuid = root.getUuid();
        Entity existingRoot = roots.get(rootUuid);
        if (existingRoot != null) {
            // spawnEntity and spawnNewEntityAndPassengers may both be reached
            // for one family.  The cube runtime is the sole owner, so the
            // second admission is an idempotent success for the same object.
            return existingRoot == root;
        }
        if (index.contains(rootUuid)) return false;
        List<Entity> family = root.streamSelfAndPassengers().toList();
        for (Entity member : family) {
            if (entitiesByUuid.containsKey(member.getUuid()) || index.contains(member.getUuid())) return false;
        }
        byte[] payload;
        try {
            payload = serialize(root);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
        CubePos owner = ownerOf(root);
        try {
            index.add(new CubeEntityRecord(owner, rootUuid, payload));
        } catch (RuntimeException exception) {
            return false;
        }
        install(root, family);
        dirty = true;
        return true;
    }

    /**
     * Materializes records only after the Cube DAG has reached FULL and retained
     * a ticket.  The two booleans are intentionally explicit at this boundary.
     */
    public synchronized int restoreCube(CubePos owner, boolean full, boolean ticketed) {
        ensureLoaded();
        if (!full || !ticketed) return 0;
        int restored = 0;
        for (CubeEntityRecord record : index.snapshot(owner)) {
            if (roots.containsKey(record.uuid())) continue;
            Entity entity;
            try {
                entity = deserialize(record.nbtBytes());
            } catch (IOException | RuntimeException exception) {
                // Keep the record for a later retry. A malformed entity must
                // not make the rest of the cube disappear.
                continue;
            }
            if (entity == null || entity.isPlayer() || !isManaged(entity)) continue;
            Entity root = entity.getRootVehicle();
            if (root != entity && !root.getUuid().equals(entity.getUuid())) {
                // loadEntityWithPassengers returns the root for a valid family;
                // reject an unexpected shape rather than creating a partial tree.
                continue;
            }
            CubePos actualOwner = ownerOf(root);
            if (!actualOwner.equals(owner)) {
                index.move(record.uuid(), actualOwner);
                dirty = true;
            }
            List<Entity> family = root.streamSelfAndPassengers().toList();
            boolean conflict = family.stream().anyMatch(member ->
                    entitiesByUuid.containsKey(member.getUuid()));
            if (conflict) continue;
            install(root, family);
            restored++;
        }
        return restored;
    }

    /** Called by EntityMixin after a managed entity changes position. */
    public synchronized void onEntityPositionChanged(Entity entity) {
        if (!loaded || closed || internalRemoval) return;
        Entity root = roots.get(entity.getUuid());
        if (root == null) root = entity.getRootVehicle();
        if (root == null || roots.get(root.getUuid()) != root) return;
        CubeEntityRecord current = index.get(root.getUuid()).orElse(null);
        if (current == null) return;
        CubePos owner = ownerOf(root);
        if (!owner.equals(current.owner())) {
            index.move(root.getUuid(), owner);
            dirty = true;
        }
    }

    /** Called by EntityMixin before a managed entity is removed. */
    public synchronized void onEntityRemoved(Entity entity, RemovalReason reason) {
        if (!loaded || closed || internalRemoval) return;
        Entity root = roots.get(entity.getUuid());
        if (root == null) root = entity.getRootVehicle();
        if (root == null || roots.get(root.getUuid()) != root) return;
        detach(root, reason);
    }

    /** Ticks only materialized entities in active simulation cubes. */
    public synchronized void tick() {
        if (!loaded || closed) return;
        List<Entity> active = List.copyOf(roots.values());
        for (Entity root : active) {
            if (root.isRemoved() || roots.get(root.getUuid()) != root) continue;
            CubePos owner = ownerOf(root);
            if (CubeWatchManager.shouldTick(world, owner)) {
                world.tickEntity(root);
                onEntityPositionChanged(root);
            }
            updateTracking(root);
        }
    }

    /** Unloads entities whose owner cube is no longer retained. */
    public synchronized void unloadExcept(Set<CubePos> retained) {
        if (!loaded || closed) return;
        for (Entity root : List.copyOf(roots.values())) {
            CubeEntityRecord record = index.get(root.getUuid()).orElse(null);
            if (record != null && !retained.contains(record.owner())) {
                internalRemoval = true;
                try {
                    root.remove(RemovalReason.UNLOADED_TO_CHUNK);
                } finally {
                    internalRemoval = false;
                }
                detach(root, RemovalReason.UNLOADED_TO_CHUNK);
            }
        }
    }

    /** Serializes live roots and atomically persists the complete HWE1 index. */
    public synchronized void save() throws IOException {
        ensureLoaded();
        CubeEntityIndex updated = new CubeEntityIndex();
        for (CubeEntityRecord record : index.snapshotAll()) {
            Entity root = roots.get(record.uuid());
            if (root == null || root.isRemoved()) {
                updated.add(record);
                continue;
            }
            onEntityPositionChanged(root);
            updated.add(new CubeEntityRecord(ownerOf(root), root.getUuid(), serialize(root)));
        }
        index = updated;
        storage.save(index);
        dirty = false;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * World-save barriers must serialize live roots even when their owner did
     * not move.  Entity age, health, inventory and other state can change
     * without touching the position listener, so dirty-only maintenance
     * saves are not sufficient for a complete save decision.
     */
    static boolean shouldSaveAtWorldSave(boolean dirty, int liveCount) {
        if (liveCount < 0) throw new IllegalArgumentException("liveCount cannot be negative");
        return dirty || liveCount > 0;
    }

    public synchronized int liveCount() {
        return roots.size();
    }

    public synchronized int pendingCount() {
        return Math.max(0, index.size() - roots.size());
    }

    /**
     * Returns owners which still have a durable record but no live root.  The
     * manager uses this bounded set to attach FULL futures only for cubes that
     * can actually restore an entity; it never scans or instantiates every
     * watched cube.
     */
    synchronized Set<CubePos> pendingOwners() {
        Set<CubePos> owners = new HashSet<>();
        for (CubeEntityRecord record : index.snapshotAll()) {
            if (!roots.containsKey(record.uuid())) owners.add(record.owner());
        }
        return Set.copyOf(owners);
    }

    public static boolean isManaged(ServerWorld world, Entity entity) {
        return entity != null && !entity.isPlayer()
                && isOutsideVanillaHeight(world, entity.getBlockPos());
    }

    private boolean isManaged(Entity entity) {
        return isManaged(world, entity);
    }

    private static boolean isOutsideVanillaHeight(ServerWorld world, BlockPos pos) {
        return pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive();
    }

    private void install(Entity root, List<Entity> family) {
        roots.put(root.getUuid(), root);
        for (Entity member : family) {
            entitiesByUuid.put(member.getUuid(), root);
            member.setChangeListener(new Listener(member));
        }
        trackers.put(root.getUuid(), new Tracker(root));
    }

    private void detach(Entity root, RemovalReason reason) {
        Tracker tracker = trackers.remove(root.getUuid());
        if (tracker != null) tracker.stopAll();
        List<Entity> family = root.streamSelfAndPassengers().toList();
        for (Entity member : family) {
            entitiesByUuid.remove(member.getUuid(), root);
            member.setChangeListener(EntityChangeListener.NONE);
        }
        roots.remove(root.getUuid(), root);
        CubeEntityRecord current = index.get(root.getUuid()).orElse(null);
        if (current == null) return;
        if (reason.shouldSave()) {
            try {
                index.remove(root.getUuid());
                index.add(new CubeEntityRecord(ownerOf(root), root.getUuid(), serialize(root)));
            } catch (IOException | RuntimeException exception) {
                // Keep the last durable payload when an entity fails to save.
                index.remove(root.getUuid());
                index.add(current);
            }
        } else {
            index.remove(root.getUuid());
        }
        dirty = true;
    }

    private void updateTracking(Entity root) {
        Tracker tracker = trackers.get(root.getUuid());
        if (tracker != null) tracker.update();
    }

    private byte[] serialize(Entity root) throws IOException {
        NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, world.getRegistryManager());
        if (!root.saveData(view)) throw new IOException("Entity is not saveable: " + root.getUuid());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(view.getNbt(), bytes);
        byte[] payload = bytes.toByteArray();
        if (payload.length > CubeEntityRecord.MAX_NBT_BYTES) {
            throw new IOException("Entity NBT exceeds limit: " + payload.length);
        }
        return payload;
    }

    private Entity deserialize(byte[] payload) throws IOException {
        NbtCompound nbt = NbtIo.readCompressed(new ByteArrayInputStream(payload),
                NbtSizeTracker.of(CubeEntityRecord.MAX_NBT_BYTES));
        return EntityType.loadEntityWithPassengers(
                nbt, world, SpawnReason.LOAD, LoadedEntityProcessor.NOOP);
    }

    private static CubePos ownerOf(Entity entity) {
        BlockPos pos = entity.getBlockPos();
        return CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    private void ensureLoaded() {
        ensureOpen();
        if (!loaded) throw new IllegalStateException("Cube entity runtime is not loaded");
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Cube entity runtime is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        for (Entity root : List.copyOf(roots.values())) {
            internalRemoval = true;
            try {
                detach(root, RemovalReason.UNLOADED_TO_CHUNK);
            } finally {
                internalRemoval = false;
            }
        }
        // Detach serializes each still-live root and updates the in-memory
        // index.  Persist only after all detachments, otherwise the old save
        // barrier can be followed by a newer in-memory NBT snapshot which is
        // never written before the storage is closed.
        if (loaded && dirty) save();
        storage.close();
        closed = true;
    }

    private final class Listener implements EntityChangeListener {
        private final Entity entity;

        private Listener(Entity entity) {
            this.entity = entity;
        }

        @Override
        public void updateEntityPosition() {
            onEntityPositionChanged(entity);
        }

        @Override
        public void remove(RemovalReason reason) {
            onEntityRemoved(entity, reason);
        }
    }

    private final class Tracker {
        private final Entity entity;
        private final EntityTrackerEntry entry;
        private final Set<ServerPlayerEntity> players = new HashSet<>();

        private Tracker(Entity entity) {
            this.entity = entity;
            this.entry = new EntityTrackerEntry(world, entity,
                    entity.getType().getTrackTickInterval(), entity.getType().alwaysUpdateVelocity(),
                    new EntityTrackerEntry.TrackerPacketSender() {
                        @Override
                        public void sendToListeners(Packet<? super ClientPlayPacketListener> packet) {
                            for (ServerPlayerEntity player : Set.copyOf(players)) player.networkHandler.send(packet, null);
                        }

                        @Override
                        public void sendToSelfAndListeners(Packet<? super ClientPlayPacketListener> packet) {
                            sendToListeners(packet);
                        }

                        @Override
                        public void sendToListenersIf(Packet<? super ClientPlayPacketListener> packet,
                                java.util.function.Predicate<ServerPlayerEntity> predicate) {
                            for (ServerPlayerEntity player : Set.copyOf(players)) {
                                if (predicate.test(player)) player.networkHandler.send(packet, null);
                            }
                        }
                    });
        }

        private void update() {
            if (entity.isRemoved()) return;
            double maxDistance = Math.max(32.0, entity.getType().getMaxTrackDistance() * 16.0);
            double maxSquared = maxDistance * maxDistance;
            Set<ServerPlayerEntity> desired = new HashSet<>();
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (entity.squaredDistanceTo(player) <= maxSquared) desired.add(player);
            }
            for (ServerPlayerEntity player : Set.copyOf(players)) {
                if (!desired.contains(player)) {
                    entry.stopTracking(player);
                    players.remove(player);
                }
            }
            for (ServerPlayerEntity player : desired) {
                if (players.add(player)) entry.startTracking(player);
            }
            entry.tick();
        }

        private void stopAll() {
            for (ServerPlayerEntity player : Set.copyOf(players)) entry.stopTracking(player);
            players.clear();
        }
    }
}
