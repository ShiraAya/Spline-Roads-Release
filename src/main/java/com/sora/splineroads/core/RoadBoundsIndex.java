package com.sora.splineroads.core;

import java.util.*;

/** Mutable transaction-local broad phase. Results are exact AABB matches in input order.
 * Large rectangles fall back to a scan; they never create unbounded bucket fan-out. */
public final class RoadBoundsIndex {
  private static final int MAX_BUCKETS=1024;
  private final int tile;
  public record Bounds(double minX,double minZ,double maxX,double maxZ) {
    public Bounds {
      if(!Double.isFinite(minX)||!Double.isFinite(minZ)||!Double.isFinite(maxX)||!Double.isFinite(maxZ)||minX>maxX||minZ>maxZ)
        throw new IllegalArgumentException("Invalid road bounds");
    }
    public boolean overlaps(Bounds other,double margin) {
      return minX<=other.maxX+margin&&maxX+margin>=other.minX&&minZ<=other.maxZ+margin&&maxZ+margin>=other.minZ;
    }
  }
  private record Grid(int x0,int z0,int x1,int z1) {
    boolean small(){long x=(long)x1-x0+1,z=(long)z1-z0+1;return x>0&&z>0&&x<=MAX_BUCKETS&&z<=MAX_BUCKETS&&x*z<=MAX_BUCKETS;}
  }
  private final List<Bounds> entries=new ArrayList<>();
  private final Map<Long,Set<Integer>> buckets=new HashMap<>();
  private final Set<Integer> large=new HashSet<>();
  private long examined,queries;
  private final boolean spatial;
  public RoadBoundsIndex(List<Bounds> bounds){this(bounds,64);}
  public RoadBoundsIndex(List<Bounds> bounds,int tile){
    if(tile<1)throw new IllegalArgumentException("Invalid spatial tile size");
    this.tile=tile;spatial=bounds.size()>64;entries.addAll(bounds);if(spatial)for(int i=0;i<entries.size();i++)add(i,entries.get(i));
  }
  private Grid grid(Bounds b,double margin){return new Grid((int)Math.floor(Math.nextDown(b.minX-margin)/tile),(int)Math.floor(Math.nextDown(b.minZ-margin)/tile),(int)Math.floor(Math.nextUp(b.maxX+margin)/tile),(int)Math.floor(Math.nextUp(b.maxZ+margin)/tile));}
  private static long key(int x,int z){return (x&0xffffffffL)|((long)z<<32);}
  private void add(int slot,Bounds b){var g=grid(b,0);if(!g.small()){large.add(slot);return;}for(long x=g.x0;x<=g.x1;x++)for(long z=g.z0;z<=g.z1;z++)buckets.computeIfAbsent(key((int)x,(int)z),k->new HashSet<>()).add(slot);}
  public void replace(int slot,Bounds next){
    Objects.requireNonNull(next);var before=entries.get(slot);if(before.equals(next))return;
    if(!spatial){entries.set(slot,next);return;}
    var g=grid(before,0);if(g.small())for(long x=g.x0;x<=g.x1;x++)for(long z=g.z0;z<=g.z1;z++){long k=key((int)x,(int)z);var set=buckets.get(k);set.remove(slot);if(set.isEmpty())buckets.remove(k);}else large.remove(slot);
    entries.set(slot,next);add(slot,next);
  }
  public List<Integer> query(Bounds bounds,double margin){
    if(!Double.isFinite(margin)||margin<0)throw new IllegalArgumentException("Invalid road query margin");
    queries++;var g=grid(bounds,margin);
    if(!spatial||!g.small()||large.size()>entries.size()/2)return scan(bounds,margin);
    var candidates=new BitSet(entries.size());int count=0;
    for(int i:large){candidates.set(i);count++;}
    for(long x=g.x0;x<=g.x1;x++)for(long z=g.z0;z<=g.z1;z++){
      var found=buckets.get(key((int)x,(int)z));if(found==null)continue;
      if(found.size()>entries.size()/2)return scan(bounds,margin);
      for(int i:found)if(!candidates.get(i)){candidates.set(i);count++;}
      if(count>entries.size()/2)return scan(bounds,margin);
    }
    var out=new ArrayList<Integer>();
    for(int i=candidates.nextSetBit(0);i>=0;i=candidates.nextSetBit(i+1)){examined++;if(entries.get(i).overlaps(bounds,margin))out.add(i);}
    return out;
  }
  private List<Integer> scan(Bounds bounds,double margin){var out=new ArrayList<Integer>();for(int i=0;i<entries.size();i++){examined++;if(entries.get(i).overlaps(bounds,margin))out.add(i);}return out;}
  public long examined(){return examined;}
  public long queries(){return queries;}
  public int bucketCount(){return buckets.size();}
}
