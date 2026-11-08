package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Server-thread, nested work leases. Tickets are removed on success and on every failure path. */
public final class RoadWorkChunks implements AutoCloseable {
  private static final TicketType<UUID> TICKET =
      TicketType.create("splineroads_work", UUID::compareTo);
  private static final Map<ServerLevel, State> ACTIVE = new IdentityHashMap<>();

  private static final class State {
    final UUID id = UUID.randomUUID();
    final Set<Long> held = new LinkedHashSet<>();
    int users;
    int limit=RoadLimits.MAX_WORK_CHUNKS;
  }

  private final ServerLevel level;
  private final State state;
  private boolean closed;

  private RoadWorkChunks(ServerLevel level) {
    if (!level.getServer().isSameThread())
      throw new IllegalStateException("Road work must run on the server thread");
    this.level = level;
    state = ACTIVE.computeIfAbsent(level, ignored -> new State());
    state.users++;
  }

  public static RoadWorkChunks open(ServerLevel level) {
    return new RoadWorkChunks(level);
  }

  static Set<Long> held(ServerLevel level){var state=ACTIVE.get(level);return state==null?Set.of():Set.copyOf(state.held);}

  public static int heldCount(ServerLevel level) {
    State state = ACTIVE.get(level);
    return state == null ? 0 : state.held.size();
  }

  /** Portal feet can lie beyond the road strip. Load only columns actually searched,
   * under the existing bounded transaction, and release them with its other tickets. */
  static boolean terrainAvailable(ServerLevel level,BlockPos pos) {
    if(!level.getWorldBorder().isWithinBounds(pos))return false;
    if(level.hasChunkAt(pos))return true;
    if(!ACTIVE.containsKey(level))return false;
    try(var lease=open(level)){lease.load(Set.of(new ChunkPos(pos).toLong()));}
    return level.hasChunkAt(pos);
  }

  /** Large directional assemblies have a measured, bounded footprint beyond the
   * ordinary road budget. The outer transaction owns and releases every ticket. */
  public void multiInterchange() {
    if(closed)throw new IllegalStateException("Road work lease is closed");
    state.limit=RoadLimits.MAX_MULTI_INTERCHANGE_WORK_CHUNKS;
  }

  public void node(BlockPos pos) {
    if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
      throw new IllegalArgumentException("端点超出世界高度或边界");
    load(Set.of(new ChunkPos(pos).toLong()));
  }

  public void roads(Collection<RoadIndex.Built> roads) {
    Set<Long> needed = new HashSet<>();
    for (var road : roads) {
      // Terrain probes reach at most .65 m outside the strip; saved furniture
      // coverage is included separately. Avoid an unused 8 m apron per road.
      needed.addAll(RoadCoverage.chunks(road.mesh, road.record.settings().structure()==com.sora.splineroads.core.RoadGeometry.Structure.BRIDGE?4:2));
      needed.addAll(road.chunks);
    }
    load(needed);
  }

  public void load(Collection<Long> requested) {
    if (closed) throw new IllegalStateException("Road work lease is closed");
    Set<Long> extra = new LinkedHashSet<>(requested);
    extra.removeAll(state.held);
    if (state.held.size() + extra.size() > state.limit)
      throw new IllegalArgumentException("一次道路操作涉及区块过多，请缩小范围或分段建造");
    for (long key : extra) {
      ChunkPos chunk = new ChunkPos(key);
      if (!level.getWorldBorder().isWithinBounds(chunk))
        throw new IllegalArgumentException("道路超出世界边界");
    }
    for (long key : extra) {
      ChunkPos chunk = new ChunkPos(key);
      level.getChunkSource().addRegionTicket(TICKET, chunk, 0, state.id);
      state.held.add(key);
      // FULL chunks expose real saved block entities; an unloaded node is never guessed or
      // recreated.
      level.getChunk(chunk.x, chunk.z);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (--state.users != 0) return;
    try {
      for (long key : state.held)
        level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(key), 0, state.id);
    } finally {
      ACTIVE.remove(level);
    }
  }
}
