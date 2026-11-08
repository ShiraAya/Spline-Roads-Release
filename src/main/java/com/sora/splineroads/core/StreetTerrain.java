package com.sora.splineroads.core;

import com.sora.splineroads.core.StreetPlan.*;
import java.util.*;

/** Immutable column snapshot. A missing chunk is unknown, never assumed flat. */
public final class StreetTerrain implements Terrain {
  public static final class Chunk {
    private final int[] height;private final byte[] ground;private final long[] fingerprint;
    public Chunk(int[] height,byte[] ground,long[] fingerprint){
      if(height.length!=256||ground.length!=256||fingerprint.length!=256)throw new IllegalArgumentException("地形快照尺寸无效");
      this.height=height.clone();this.ground=ground.clone();this.fingerprint=fingerprint.clone();
      for(int i=0;i<256;i++)if(ground[i]<0||ground[i]>=Ground.values().length||height[i]<-2048||height[i]>4096)throw new IllegalArgumentException("地形快照数据无效");
    }
    public int[] heights(){return height.clone();}public byte[] ground(){return ground.clone();}public long[] fingerprints(){return fingerprint.clone();}
    public Surface at(int column){return new Surface(height[column],Ground.values()[ground[column]]);}
    public long fingerprint(int column){return fingerprint[column];}
  }
  private final Map<Cell,Chunk> chunks;
  public StreetTerrain(Map<Cell,Chunk> chunks){if(chunks.size()>StreetPlan.MAX_CHUNKS)throw new IllegalArgumentException("地形快照区块过多");this.chunks=Collections.unmodifiableMap(new TreeMap<>(chunks));}
  public Map<Cell,Chunk> chunks(){return chunks;}
  public Surface at(double x,double z){var chunk=chunks.get(Cell.at(x,z));return chunk==null?new Surface(Double.NaN,Ground.UNKNOWN):chunk.at(column(x,z));}
  public static int column(double x,double z){return Math.floorMod((int)Math.floor(x),16)+16*Math.floorMod((int)Math.floor(z),16);}
  public boolean complete(Input input){return chunks.keySet().containsAll(input.captureArea());}
  public Terrain forPlan(Input input){return (x,z)->{var surface=at(x,z);if(surface.ground()==Ground.ROAD)for(var entry:input.entries())if(entry.existing()&&entry.point().distance(new Point(x,z))<=input.options().fullWidth(0)/2+1)return new Surface(entry.height(),Ground.LAND);return surface;};}
}
