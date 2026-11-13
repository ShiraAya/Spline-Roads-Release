package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry.Node;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Existing descriptors are authoritative; merely opening an editor never writes a marker. */
final class InterchangeEndpoints {
  record Endpoint(Node node, NodeEntity marker, CompoundTag snapshot) {}

  static Endpoint read(ServerLevel level, ServerPlayer player, CompoundTag saved, int i) {
    long[] points = saved.getLongArray("Points");
    BlockPos pos = BlockPos.of(points[i]);
    try (var chunks = RoadWorkChunks.open(level)) {
      chunks.node(pos);
      if (level.getBlockEntity(pos) instanceof NodeEntity) {
        var marker = RoadData.requireNode(level, pos, player);
        return new Endpoint(marker.constructionNode(), marker, marker.saveWithoutMetadata());
      }
      var nodes = saved.getList("Nodes", Tag.TAG_COMPOUND);
      var data = RoadData.get(level);
      if (!saved.hasUUID("Id") || !saved.hasUUID("Owner") || nodes.size() != points.length
          || data.index.roads.values().stream().noneMatch(r -> saved.getUUID("Id").equals(r.record.assembly())))
        throw new IllegalArgumentException("端点 " + (char)('A' + i) + " 缺失，且没有完整的立交端点记录");
      if (player != null) {
        RoadData.requireOwner(player, saved.getUUID("Owner"));
        if (!player.mayBuild() || !level.mayInteract(player, pos))
          throw new IllegalArgumentException("此处没有建设权限");
      }
      var state = level.getBlockState(pos);
      boolean ownRoad = RoadBlocks.isCollider(state) && !data.index.at(pos.asLong()).isEmpty()
          && data.index.at(pos.asLong()).stream().allMatch(id -> saved.getUUID("Id").equals(data.index.roads.get(id).record.assembly()));
      if (level.getBlockEntity(pos) != null || !(TunnelAir.isAirSpace(state) || state.is(SplineRoads.NODE.get()) || ownRoad))
        throw new IllegalArgumentException("立交端点 " + (char)('A' + i) + " 缺失，原位置被占用：" + pos.toShortString());
      Node node = RoadRecord.readNode(nodes.getCompound(i));
      var p = node.position();
      if (!Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())
          || !Double.isFinite(node.yaw()) || !Double.isFinite(node.grade()))
        throw new IllegalArgumentException("立交端点记录无效，请恢复存档备份");
      CompoundTag snapshot = new CompoundTag();
      snapshot.putUUID("Owner", saved.getUUID("Owner"));
      snapshot.put("Node", RoadRecord.writeNode(node));
      snapshot.putBoolean("HeightExplicit", true);
      return new Endpoint(node, null, snapshot);
    }
  }

  /** Keep the confirmed node when a newly created range marker was derived from a sliced mesh.
   * Existing markers never use this path. Matching a block address alone is insufficient: another
   * real endpoint in that block must retain its own position and orientation. */
  static Node generated(long[] points, java.util.List<Node> anchors, BlockPos pos, Node fallback) {
    for (int i = 0; i < points.length && i < anchors.size(); i++) {
      if (points[i] != pos.asLong()) continue;
      Node anchor = anchors.get(i);
      var a = anchor.position(); var b = fallback.position();
      if (sameCoordinate(a.x(), b.x()) && sameCoordinate(a.y(), b.y())
          && sameCoordinate(a.z(), b.z())
          && Math.abs(Math.IEEEremainder(anchor.yaw() - fallback.yaw(), 360)) <= 1e-8
          && Math.abs(anchor.grade() - fallback.grade()) <= 1e-8) return anchor;
    }
    return fallback;
  }

  private static boolean sameCoordinate(double a, double b) {
    return Double.isFinite(a) && Double.isFinite(b)
        && Math.abs(a - b) <= Math.max(1e-12, 8 * Math.max(Math.ulp(a), Math.ulp(b)));
  }

  private InterchangeEndpoints() {}
}
