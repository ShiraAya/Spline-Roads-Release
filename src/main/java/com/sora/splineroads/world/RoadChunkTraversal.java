package com.sora.splineroads.world;

import java.util.*;

/** Closed-segment chunk traversal, independent of block/world state. */
final class RoadChunkTraversal {
  private static long chunk(int x,int z){return (long)x&0xffffffffL|((long)z&0xffffffffL)<<32;}
  static Set<Long> trace(double ox,double oz,double vx,double vz,double range){
    if(range<0||!Double.isFinite(ox)||!Double.isFinite(oz)||!Double.isFinite(vx)||!Double.isFinite(vz)||!Double.isFinite(range))return Set.of();
    int x=(int)Math.floor(ox/16),z=(int)Math.floor(oz/16);
    int sx=vx>0?1:vx<0?-1:0,sz=vz>0?1:vz<0?-1:0;
    double dx=sx==0?Double.POSITIVE_INFINITY:16/Math.abs(vx),dz=sz==0?Double.POSITIVE_INFINITY:16/Math.abs(vz);
    double tx=sx==0?Double.POSITIVE_INFINITY:((sx>0?x+1:x)*16d-ox)/vx;
    double tz=sz==0?Double.POSITIVE_INFINITY:((sz>0?z+1:z)*16d-oz)/vz;
    boolean xBoundary=sx==0&&ox==x*16d,zBoundary=sz==0&&oz==z*16d;
    var chunks=new LinkedHashSet<Long>();
    if(ox==x*16d)chunks.add(chunk(x-1,z));
    if(oz==z*16d)chunks.add(chunk(x,z-1));
    if(ox==x*16d&&oz==z*16d)chunks.add(chunk(x-1,z-1));
    while(true){
      chunks.add(chunk(x,z));
      if(xBoundary)chunks.add(chunk(x-1,z));
      if(zBoundary)chunks.add(chunk(x,z-1));
      if(xBoundary&&zBoundary)chunks.add(chunk(x-1,z-1));
      double next=Math.min(tx,tz);if(!Double.isFinite(next)||next>range)break;
      if(Math.abs(tx-tz)<=1e-12*Math.max(1,Math.abs(next))){
        chunks.add(chunk(x+sx,z));chunks.add(chunk(x,z+sz));
        x+=sx;z+=sz;tx+=dx;tz+=dz;
      }else if(tx<tz){x+=sx;tx+=dx;}else{z+=sz;tz+=dz;}
    }
    return chunks;
  }
  private RoadChunkTraversal(){}
}
