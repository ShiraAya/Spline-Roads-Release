package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;

/** Differential check against the pre-optimization sampler, including every
 * world read. Matching colors alone could hide a lost relighting dependency. */
public final class ScalarLightValidation {
  record Read(int x,int y,int z,boolean light){}
  static class World {
    final List<Read> trace=new ArrayList<>();int phase;
    public boolean opaque(int x,int y,int z){trace.add(new Read(x,y,z,false));return phase==3||Math.floorMod(x*31+y*17+z*7+phase,11)<3;}
    public int packed(int x,int y,int z){trace.add(new Read(x,y,z,true));return (Math.floorMod(x+y*3+phase,16)<<4)|(Math.floorMod(z-y+phase,16)<<20);}
  }
  static final class Before extends World implements ReferenceRoadLighting.Access {}
  static final class After extends World implements RoadLighting.Access {}
  public static void main(String[] args){
    var before=new Before();var after=new After();var random=new Random(2111411);int checks=0;long reads=0;
    var textures=RoadSurface.Texture.values();
    for(int i=0;i<4000;i++){
      before.phase=after.phase=i%8;
      double x=random.nextInt(2000)-1000,y=random.nextInt(192)-64,z=random.nextInt(2000)-1000;
      var points=new ArrayList<V>();
      for(int j=0;j<3+i%4;j++){
        double px=x+random.nextDouble()*3,py=y+random.nextDouble()*2,pz=z+random.nextDouble()*3;
        if(i%3==0)py=y;
        if(i%7==0)px=Math.nextDown(x+.65);
        if(i%11==0)pz=Math.nextUp(z-1.05);
        if(i%19==0){px=x;py=y;pz=z;}
        points.add(new V(px,py,pz));
      }
      var face=new RoadSurface.Face(List.copyOf(points),0xabcdef,i%53==0,textures[i%textures.length]);
      if(!RoadLighting.center(face).equals(ReferenceRoadLighting.center(face)))throw new AssertionError("Center changed");
      for(var point:points)for(boolean forceUp:new boolean[]{false,true}){
        before.trace.clear();after.trace.clear();
        int expected=ReferenceRoadLighting.sample(face,point,before,forceUp),actual=RoadLighting.sample(face,point,after,forceUp);
        if(expected!=actual||!before.trace.equals(after.trace))throw new AssertionError("Sampler changed at case="+i+" vertex="+point+" upward="+forceUp);
        checks++;reads+=after.trace.size();
      }
    }
    System.out.println("PASS scalar light comparisons="+checks+" exact world reads="+reads);
  }
}
