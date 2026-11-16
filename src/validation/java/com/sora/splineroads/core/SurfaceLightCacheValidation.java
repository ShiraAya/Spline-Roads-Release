package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import java.util.Random;

/** Compare cached seam lighting to uncached world reads, including frame changes. */
public final class SurfaceLightCacheValidation {
  static final class World implements RoadLighting.Access {
    int phase,reads;
    public boolean opaque(int x,int y,int z){reads++;return phase==3||Math.floorMod(x*31+y*17+z*7+phase,11)<3;}
    public int packed(int x,int y,int z){reads++;return (Math.floorMod(x+y*3+phase,16)<<4)|(Math.floorMod(z-y+phase,16)<<20);}
  }
  public static void main(String[] args){
    var world=new World();var cache=new RoadLighting.SurfaceCache();var random=new Random(211);
    int checks=0;
    for(int frame=0;frame<8;frame++){
      world.phase=frame;cache.clear();
      for(int i=0;i<6000;i++){
        var v=new V(random.nextInt(32)/2.0,random.nextInt(10)/2.0,random.nextInt(32)/2.0);
        var n=switch(i%4){case 0->new V(0,1,0);case 1->new V(0,-1,0);case 2->new V(1,0,0);default->new V(0,.8,.6);};
        double reach=i%2==0?.65:1.05;
        int expected=world.surface(v,n,reach);
        if(cache.sample(v,n,reach,world)!=expected)throw new AssertionError("value mismatch");
        int reads=world.reads;
        if(cache.sample(v,n,reach,world)!=expected||world.reads!=reads)throw new AssertionError("shared vertex not reused");
        checks+=2;
      }
    }
    // Exactly the same position must resample changed opacity/light next frame.
    var v=new V(.25,2.25,.25);var n=new V(0,1,0);
    for(int phase=0;phase<8;phase++){
      world.phase=phase;cache.clear();
      if(cache.sample(v,n,.65,world)!=world.surface(v,n,.65))throw new AssertionError("stale frame");
      checks++;
    }
    System.out.println("PASS surface lighting checks="+checks+" hits="+cache.hits()+" misses="+cache.misses());
  }
}
