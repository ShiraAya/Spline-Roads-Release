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

  private InterchangeEndpoints() {}
}
