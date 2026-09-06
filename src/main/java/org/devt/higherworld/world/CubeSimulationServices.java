package org.devt.higherworld.world;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;

/**
 * Per-world runtime seams for sparse-cube POI, path and natural-spawn logic.
 *
 * <p>The service deliberately owns only version-neutral data and lifecycle
 * decisions. Vanilla POI storage, {@code SpawnHelper}, path node makers and
 * villager brains still use finite-height chunk APIs, so version-specific
 * adapters can consume these snapshots later without forcing a synchronous
 * cube load from an AI callback.</p>
 */
public final class CubeSimulationServices implements AutoCloseable {
    private static final int MAX_PERSISTED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CANDIDATES_PER_BATCH = 2_048;
    private static final Comparator<CubePos> CUBE_ORDER = Comparator
            .comparingInt(CubePos::x)
            .thenComparingInt(CubePos::y)
            .thenComparingInt(CubePos::z);
    private static final Comparator<CubeSpawnPolicy.SimulationWindow> WINDOW_ORDER = Comparator
            .comparing(CubeSpawnPolicy.SimulationWindow::center, CUBE_ORDER)
            .thenComparingInt(CubeSpawnPolicy.SimulationWindow::horizontalRadius)
            .thenComparingInt(CubeSpawnPolicy.SimulationWindow::verticalRadius);
    private static final EntityTicketSink NO_TICKET_SINK = new EntityTicketSink() {
        @Override
        public void replace(CubeTicket ticket) {
        }

        @Override
        public void remove(Object owner) {
        }
    };

    private final Path persistenceFile;
    private final CubePathfindingAccess pathfindingAccess;
    private final Supplier<? extends Collection<CubeSpawnPolicy.SimulationWindow>> windowSupplier;
    private final EntityTicketSink entityTicketSink;
    private final CubePoiIndex poiIndex = new CubePoiIndex();
    private final Map<Object, CubeSpawnPolicy.Ticket> entityTickets = new HashMap<>();
    private final NavigableSet<CubePos> fullCubes = new TreeSet<>(CUBE_ORDER);
    private List<CubeSpawnPolicy.SimulationWindow> simulationWindows = List.of();
    private boolean opened;
    private boolean closed;
    private boolean dirty;

    /** Creates a service with no natural-spawn ticket callback. */
    public CubeSimulationServices(
            Path persistenceFile,
            CubePathfindingAccess pathfindingAccess,
            Supplier<? extends Collection<CubeSpawnPolicy.SimulationWindow>> windowSupplier) {
        this(persistenceFile, pathfindingAccess, windowSupplier, NO_TICKET_SINK);
    }

    /** Creates a service with the supplied scheduler callback for entity tickets. */
    public CubeSimulationServices(
            Path persistenceFile,
            CubePathfindingAccess pathfindingAccess,
            Supplier<? extends Collection<CubeSpawnPolicy.SimulationWindow>> windowSupplier,
            EntityTicketSink entityTicketSink) {
        this.persistenceFile = Objects.requireNonNull(persistenceFile, "persistenceFile");
        this.pathfindingAccess = Objects.requireNonNull(pathfindingAccess, "pathfindingAccess");
        this.windowSupplier = Objects.requireNonNull(windowSupplier, "windowSupplier");
        this.entityTicketSink = Objects.requireNonNull(entityTicketSink, "entityTicketSink");
    }

    /** Creates a service whose spawn pass is disabled until a window is supplied. */
    public CubeSimulationServices(Path persistenceFile, CubePathfindingAccess pathfindingAccess) {
        this(persistenceFile, pathfindingAccess, () -> List.of(), NO_TICKET_SINK);
    }

    /** Loads this world's POI index without loading any cube. */
    public synchronized void open() {
        if (opened) return;
        if (closed) throw new IllegalStateException("simulation services are already closed");
        opened = true;
        if (!Files.exists(persistenceFile)) return;
        try {
            long size = Files.size(persistenceFile);
            if (size > MAX_PERSISTED_BYTES) throw new IOException("POI index is too large");
            byte[] encoded = Files.readAllBytes(persistenceFile);
            if (encoded.length > MAX_PERSISTED_BYTES) throw new IOException("POI index is too large");
            CubePoiIndex restored = CubePoiIndex.decode(encoded);
            for (CubePoiRecord poi : restored.snapshot()) poiIndex.add(poi);
        } catch (IOException | IllegalArgumentException exception) {
            // A corrupt auxiliary index must not prevent the world itself from
            // opening. Rebuild it from future sparse block-state changes.
            dirty = true;
            Higherworld.LOGGER.warn("Ignoring invalid sparse POI index {}", persistenceFile, exception);
        }
    }

    /** Returns the read-only path seam; it never performs a synchronous load. */
    public CubePathfindingAccess pathfindingAccess() {
        return pathfindingAccess;
    }

    /** Returns a deterministic defensive POI snapshot. */
    public synchronized List<CubePoiRecord> poiSnapshot() {
        ensureOpen();
        return poiIndex.snapshot();
    }

    /** Performs a bounded POI query without consulting vanilla chunk storage. */
    public synchronized List<CubePoiRecord> queryPois(
            CubePos center, CubeDependencyRadius radius, Predicate<CubePoiRecord> predicate) {
        ensureOpen();
        return poiIndex.query(center, radius, predicate);
    }

    /** Inserts a POI and marks the auxiliary index dirty for the next close. */
    public synchronized Optional<CubePoiRecord> addPoi(CubePoiRecord poi) {
        ensureOpen();
        Optional<CubePoiRecord> previous = poiIndex.add(poi);
        if (previous.isEmpty() || !previous.get().equals(poi)) dirty = true;
        return previous;
    }

    /** Removes one POI by its vanilla-facing absolute position and type id. */
    public synchronized Optional<CubePoiRecord> removePoi(BlockPos position, String type) {
        ensureOpen();
        Optional<CubePoiRecord> removed = poiIndex.remove(position, type);
        if (removed.isPresent()) dirty = true;
        return removed;
    }

    /**
     * Rebuilds one cube's POI entries from its committed 16^3 block-state
     * section. Existing free-ticket counts are retained by stable POI key, so
     * a FULL unload/reload does not make occupied homes or job sites available
     * again. This method is package-private because LoadedCube is an internal
     * runtime type and is called only after the FULL commit boundary.
     */
    synchronized void indexFullCube(LoadedCube cube) {
        ensureOpen();
        Objects.requireNonNull(cube, "cube");
        indexFullCube(cube.pos(), cube.section()::getBlockState);
    }

    /** Scans a cube through a read-only local block-state source. */
    synchronized void indexFullCube(CubePos position, CubeBlockStateSource source) {
        ensureOpen();
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(source, "source");
        if (!position.isBlockRangeRepresentable()) return;

        Map<CubePoiIndex.Key, CubePoiRecord> previous = new HashMap<>();
        for (CubePoiRecord poi : poiIndex.removeCube(position)) {
            previous.put(CubePoiIndex.Key.of(poi), poi);
        }
        Map<CubePoiIndex.Key, CubePoiRecord> current = new HashMap<>();
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    Optional<PoiType> type = poiType(source.get(localX, localY, localZ));
                    if (type.isEmpty()) continue;
                    PoiType poiType = type.get();
                    CubePoiIndex.Key key = new CubePoiIndex.Key(
                            position, localX, localY, localZ, poiType.id());
                    CubePoiRecord old = previous.get(key);
                    int freeTickets = old == null
                            ? poiType.ticketCount()
                            : Math.min(poiType.ticketCount(), old.freeTickets());
                    CubePoiRecord record = new CubePoiRecord(
                            position, localX, localY, localZ, poiType.id(),
                            poiType.ticketCount(), freeTickets);
                    current.put(key, record);
                    poiIndex.add(record);
                }
            }
        }
        if (!previous.equals(current)) dirty = true;
    }

    /**
     * Updates the cube-owned index from a sparse block-state transition.
     * PointOfInterestTypes#getTypeForState is the Yarn 1.21.11 public API;
     * unbound registry entries are ignored until a version-specific registry
     * adapter can provide a stable identifier.
     */
    public synchronized void onBlockStateChanged(
            BlockPos position, BlockState previous, BlockState current) {
        ensureOpen();
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Optional<PoiType> oldType = poiType(previous);
        Optional<PoiType> newType = poiType(current);
        if (oldType.equals(newType)) return;

        boolean changed = oldType
                .map(type -> poiIndex.remove(position, type.id()).isPresent())
                .orElse(false);
        if (newType.isPresent()) {
            PoiType type = newType.get();
            CubePos cube = CubePos.fromBlock(position.getX(), position.getY(), position.getZ());
            int localX = Math.floorMod(position.getX(), CubePos.SIZE);
            int localY = Math.floorMod(position.getY(), CubePos.SIZE);
            int localZ = Math.floorMod(position.getZ(), CubePos.SIZE);
            Optional<CubePoiRecord> existing = poiIndex.get(position, type.id());
            int freeTickets = existing.map(CubePoiRecord::freeTickets).orElse(type.ticketCount());
            freeTickets = Math.min(type.ticketCount(), freeTickets);
            CubePoiRecord replacement = new CubePoiRecord(
                    cube, localX, localY, localZ, type.id(), type.ticketCount(), freeTickets);
            Optional<CubePoiRecord> replaced = poiIndex.add(replacement);
            changed |= replaced.isEmpty() || !replacement.equals(replaced.get());
        }
        if (changed) dirty = true;
    }

    /**
     * Publishes the set of FULL cubes and current simulation windows for one
     * world tick. Invalid entity tickets are withdrawn through the scheduler
     * callback instead of leaving stale FULL demand behind.
     */
    public synchronized void tick(Collection<CubePos> loadedFullCubes, long gameTime) {
        ensureOpen();
        Objects.requireNonNull(loadedFullCubes, "loadedFullCubes");
        fullCubes.clear();
        for (CubePos cube : loadedFullCubes) {
            if (cube != null && cube.isBlockRangeRepresentable()) fullCubes.add(cube);
        }
        tick(gameTime);
    }

    /** World lifecycle hooks keep the spawn index current without a cache scan. */
    synchronized void fullCubeLoaded(CubePos cube) {
        ensureOpen();
        if (cube.isBlockRangeRepresentable()) fullCubes.add(cube);
    }

    synchronized void fullCubeUnloaded(CubePos cube) {
        fullCubes.remove(cube);
    }

    synchronized void tick(long gameTime) {
        ensureOpen();
        simulationWindows = readWindows();

        var iterator = entityTickets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Object, CubeSpawnPolicy.Ticket> entry = iterator.next();
            CubeSpawnPolicy.Ticket ticket = entry.getValue();
            if (!isEligible(ticket.cube())) {
                iterator.remove();
                entityTicketSink.remove(entry.getKey());
            }
        }
    }

    /**
     * Creates a deterministic, bounded natural-spawn candidate batch from
     * already FULL cubes. The passability predicate is deliberately
     * conservative: a version-specific adapter can add biome/entity rules.
     */
    public synchronized List<CubeSpawnPolicy.Candidate> spawnCandidates(
            long worldSeed, long gameTime, int attemptsPerCube) {
        ensureOpen();
        if (attemptsPerCube < 0 || attemptsPerCube > CubeSpawnPolicy.MAX_ATTEMPTS) {
            throw new IllegalArgumentException("spawn attempts are out of bounds");
        }
        List<CubeSpawnPolicy.Candidate> result = new ArrayList<>();
        for (CubePos cube : fullCubes) {
            CubeSpawnPolicy.SimulationWindow window = windowFor(cube);
            if (window == null || status(cube) != CubeStatus.FULL) continue;
            List<CubeSpawnPolicy.Candidate> candidates = CubeSpawnPolicy.candidates(
                    worldSeed, gameTime, cube, CubeStatus.FULL, window, attemptsPerCube,
                    candidate -> pathfindingAccess.isPassable(new CubePathNode(
                            candidate.blockX(), candidate.blockY(), candidate.blockZ())));
            for (CubeSpawnPolicy.Candidate candidate : candidates) {
                result.add(candidate);
                if (result.size() >= MAX_CANDIDATES_PER_BATCH) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    /** Acquires/replaces one ENTITY ticket only for a FULL simulating cube. */
    public synchronized Optional<CubeSpawnPolicy.Ticket> acquireEntityTicket(
            Object owner, CubePos cube) {
        ensureOpen();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(cube, "cube");
        CubeSpawnPolicy.SimulationWindow window = windowFor(cube);
        if (window == null) return Optional.empty();
        Optional<CubeSpawnPolicy.Ticket> ticket = CubeSpawnPolicy.ticketFor(
                owner, cube, status(cube), window);
        if (ticket.isEmpty()) return Optional.empty();
        entityTickets.put(owner, ticket.get());
        entityTicketSink.replace(ticket.get().asCubeTicket());
        return ticket;
    }

    /** Releases an owner ticket and removes its FULL demand from the DAG. */
    public synchronized boolean releaseEntityTicket(Object owner) {
        ensureOpen();
        Objects.requireNonNull(owner, "owner");
        CubeSpawnPolicy.Ticket removed = entityTickets.remove(owner);
        if (removed == null) return false;
        entityTicketSink.remove(owner);
        return true;
    }

    /** Returns active entity tickets in stable cube/owner order. */
    public synchronized List<CubeSpawnPolicy.Ticket> activeEntityTickets() {
        ensureOpen();
        List<CubeSpawnPolicy.Ticket> result = new ArrayList<>(entityTickets.values());
        result.sort(Comparator
                .comparing(CubeSpawnPolicy.Ticket::cube, CUBE_ORDER)
                .thenComparing(ticket -> String.valueOf(ticket.owner())));
        return List.copyOf(result);
    }

    private boolean isEligible(CubePos cube) {
        return status(cube) == CubeStatus.FULL && windowFor(cube) != null;
    }

    private CubeStatus status(CubePos cube) {
        CubeStatus current = pathfindingAccess.status(cube);
        return current == null ? CubeStatus.EMPTY : current;
    }

    private CubeSpawnPolicy.SimulationWindow windowFor(CubePos cube) {
        for (CubeSpawnPolicy.SimulationWindow window : simulationWindows) {
            if (window.contains(cube)) return window;
        }
        return null;
    }

    private List<CubeSpawnPolicy.SimulationWindow> readWindows() {
        Collection<CubeSpawnPolicy.SimulationWindow> supplied;
        try {
            supplied = windowSupplier.get();
        } catch (RuntimeException exception) {
            Higherworld.LOGGER.warn("Cannot read sparse simulation windows", exception);
            return List.of();
        }
        if (supplied == null || supplied.isEmpty()) return List.of();
        List<CubeSpawnPolicy.SimulationWindow> result = new ArrayList<>();
        for (CubeSpawnPolicy.SimulationWindow window : supplied) {
            if (window != null) result.add(window);
        }
        result.sort(WINDOW_ORDER);
        return List.copyOf(result);
    }

    private static Optional<PoiType> poiType(BlockState state) {
        return PointOfInterestTypes.getTypeForState(state).flatMap(entry -> entry.getKey()
                .map(key -> new PoiType(
                        key.getValue().toString(),
                        Math.max(1, Math.min(255, entry.value().ticketCount())))));
    }

    private void ensureOpen() {
        if (!opened || closed) throw new IllegalStateException("simulation services are not open");
    }

    private void save() throws IOException {
        byte[] encoded;
        try {
            encoded = poiIndex.encode();
        } catch (RuntimeException exception) {
            throw new IOException("Cannot encode sparse POI index", exception);
        }
        Path parent = persistenceFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = persistenceFile.resolveSibling(persistenceFile.getFileName() + ".tmp");
        Files.write(temporary, encoded, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, persistenceFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, persistenceFile, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        try {
            if (opened && dirty) save();
        } catch (IOException exception) {
            failure = exception;
        } finally {
            for (Object owner : List.copyOf(entityTickets.keySet())) {
                entityTicketSink.remove(owner);
            }
            entityTickets.clear();
            fullCubes.clear();
            closed = true;
        }
        if (failure != null) throw failure;
    }

    private record PoiType(String id, int ticketCount) {
    }

    @FunctionalInterface
    interface CubeBlockStateSource {
        BlockState get(int localX, int localY, int localZ);
    }

    /** Receives entity-ticket lifecycle changes from the world scheduler. */
    @FunctionalInterface
    public interface EntityTicketSink {
        void replace(CubeTicket ticket);

        default void remove(Object owner) {
        }
    }
}
