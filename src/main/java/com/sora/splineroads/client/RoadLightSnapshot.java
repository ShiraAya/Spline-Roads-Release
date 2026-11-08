package com.sora.splineroads.client;
import java.util.Arrays;
import net.minecraft.world.level.chunk.DataLayer;

/** Immutable content stamps also detect light engines that update a DataLayer in place. */
public record RoadLightSnapshot(int sky,int block) {
  public static RoadLightSnapshot capture(DataLayer sky,DataLayer block){return new RoadLightSnapshot(hash(sky),hash(block));}
  private static int hash(DataLayer layer){return layer==null?0:Arrays.hashCode(layer.getData());}
}
