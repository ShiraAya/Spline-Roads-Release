package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;import java.util.*;
public final class FlatSurface211Validation {
 record Read(int x,int y,int z,boolean packed){}
 static class World implements RoadLighting.Access {int phase;Set<Read> reads=new HashSet<>();public boolean opaque(int x,int y,int z){reads.add(new Read(x,y,z,false));return phase==3||Math.floorMod(x*31+y*17+z*7+phase,11)<3;}public int packed(int x,int y,int z){reads.add(new Read(x,y,z,true));return (Math.floorMod(x+y*3+phase,16)<<4)|(Math.floorMod(z-y+phase,16)<<20);}}
 public static void main(String[] args){var expected=new World();var actual=new World();var cache=new RoadLighting.SurfaceCache();var random=new Random(2111116);long checks=0,oldReads=0,newReads=0;
  for(int frame=0;frame<10;frame++){cache.clear();actual.reads.clear();expected.phase=actual.phase=frame;
   for(int i=0;i<20000;i++){double x=random.nextInt(30)-15,y=random.nextInt(12)-6,z=random.nextInt(30)-15;double r=i%2==0?.65:1.05;double offset=switch(i%6){case 0->r;case 1->-r;case 2->.12;case 3->.65;case 4->1.15;default->random.nextDouble();};x+=offset;y-=offset;z+=offset;if(i%3==0){x=Math.nextDown(x);y=Math.nextUp(y);}if(i%3==1){z=Math.nextUp(z);y=Math.nextDown(y);}var v=new V(x,y,z);var n=i%17==0?new V(0,.8,.6):new V(i%7==0?-0.0:0,i%4<2?1:-1,0);
    expected.reads.clear();int wanted=expected.surface(v,n,r);int before=actual.reads.size();int got=cache.sample(v,n,r,actual);if(got!=wanted||!actual.reads.containsAll(expected.reads))throw new AssertionError("value/dependency mismatch at "+i+" frame="+frame+" v="+v+" n="+n);oldReads+=expected.reads.size();newReads+=actual.reads.size()-before;checks++;
    if(i%10==0){actual.reads.clear();cache.clear();}
   }
  }
  // Nearby, distinct vertices deliberately exercise cache hits and misses on
  // both sides of integer sample boundaries, with mixed normals and reaches.
  for(int frame=0;frame<6;frame++){
   cache.clear();actual.reads.clear();expected.phase=actual.phase=frame;
   for(int i=0;i<10000;i++){
    if(i%40==0){cache.clear();actual.reads.clear();}
    var base=new V(random.nextInt(24)-12+random.nextDouble(),random.nextInt(12)-6+random.nextDouble(),random.nextInt(24)-12+random.nextDouble());
    double reach=i%2==0?.65:1.05;var normal=new V(0,i%4<2?1:-1,0);
    for(int j=-4;j<=4;j++){
     var v=base.add(new V(j*.00001,j*.00003,j*.00007));expected.reads.clear();int wanted=expected.surface(v,normal,reach),got=cache.sample(v,normal,reach,actual);
     if(got!=wanted||!actual.reads.containsAll(expected.reads))throw new AssertionError("neighbor value/dependency mismatch "+v);checks++;
    }
   }
  }
  // Dense horizontal seam: different vertices can have an identical footprint.
  cache.clear();actual.reads.clear();long h=cache.hits();for(int i=0;i<1000;i++){var v=new V(100+i*.0001,10.2,200+i*.0001);if(cache.sample(v,new V(0,1,0),.65,actual)!=expected.surface(v,new V(0,1,0),.65))throw new AssertionError("dense seam mismatch");}
  if(cache.hits()-h<990)throw new AssertionError("footprints were not reused");
  System.out.println("PASS flat lighting values/dependency unions="+checks+" dense_hits="+(cache.hits()-h)+" total_hits="+cache.hits()+" misses="+cache.misses()+" original_reads="+oldReads+" observed_unique_reads="+newReads);
 }
}
