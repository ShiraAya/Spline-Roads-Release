package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class NodeEntity extends BlockEntity {
  public double yaw, grade, offsetX, offsetY, offsetZ;
  public boolean headingLocked, gradeLocked, heightExplicit, plannerHeading;
  public UUID owner;
  public UUID junctionSource;

  public NodeEntity(BlockPos pos, BlockState state) {
    super(SplineRoads.NODE_ENTITY.get(), pos, state);
  }

  public Node node() {
    return new Node(
        new V(
            worldPosition.getX() + .5 + offsetX,
            worldPosition.getY() + offsetY,
            worldPosition.getZ() + .5 + offsetZ),
        yaw,
        grade);
  }

  /** 0.1 default markers used +0.25; normalize that default when a road is rebuilt. */
  public Node constructionNode() {
    Node n = node();
    return !heightExplicit && Math.abs(offsetY - .25) < 1e-8
        ? new Node(
            new V(n.position().x(), worldPosition.getY(), n.position().z()), n.yaw(), n.grade())
        : n;
  }

  public void apply(Node node) {
    offsetX = node.position().x() - worldPosition.getX() - .5;
    offsetY = node.position().y() - worldPosition.getY();
    offsetZ = node.position().z() - worldPosition.getZ() - .5;
    yaw = node.yaw();
    grade = node.grade();
    setChanged();
    if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
  }

  @Override
  protected void saveAdditional(CompoundTag t) {
    super.saveAdditional(t);
    t.put("Node", RoadRecord.writeNode(node()));
    t.putBoolean("HeightExplicit", heightExplicit);
    t.putBoolean("PlannerHeading",plannerHeading);
    t.putBoolean("HeadingLocked", headingLocked);
    t.putBoolean("GradeLocked", gradeLocked);
    if (owner != null) t.putUUID("Owner", owner);
    if(junctionSource!=null)t.putUUID("JunctionSource",junctionSource);
  }

  @Override
  public void load(CompoundTag t) {
    super.load(t);
    if (t.contains("Node")) {
      Node n = RoadRecord.readNode(t.getCompound("Node"));
      offsetX = n.position().x() - worldPosition.getX() - .5;
      offsetY = n.position().y() - worldPosition.getY();
      offsetZ = n.position().z() - worldPosition.getZ() - .5;
      yaw = n.yaw();
      grade = n.grade();
    }
    if (t.hasUUID("Owner")) owner = t.getUUID("Owner");
    junctionSource=t.hasUUID("JunctionSource")?t.getUUID("JunctionSource"):null;
    heightExplicit =
        t.contains("HeightExplicit")
            ? t.getBoolean("HeightExplicit")
            : (Math.abs(offsetY) > 1e-8 && Math.abs(offsetY - .25) > 1e-8);
    plannerHeading=t.getBoolean("PlannerHeading");
    headingLocked = t.getBoolean("HeadingLocked");
    gradeLocked = t.getBoolean("GradeLocked");
  }

  @Override
  public CompoundTag getUpdateTag() {
    return saveWithoutMetadata();
  }

  @Override
  public ClientboundBlockEntityDataPacket getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }
}
