package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.Ground;
import java.util.*;
/** Optional earth avoidance is independent of the road-crossing layer preference. */
public final class LaneRampTerrain {
 public static boolean allowed(Mesh mesh,LanePoints.Options options,Ground ground){
  return ground==null||options.allowTunnel()||RoadAutoTunnels.below(mesh.first(),ground)||RoadAutoTunnels.below(mesh.last(),ground);
 }
 public static double[] floors(Mesh mesh,LanePoints.Options options,Ground ground){
  if(allowed(mesh,options,ground))return null;
  double[] sampled=new double[mesh.samples().size()];
  for(int i=0;i<sampled.length;i++){RoadPlanningBudget.check();sampled[i]=floor(mesh.samples().get(i),ground);}
  double[] floors=sampled.clone();
  for(int i=1;i<floors.length;i++){
   var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);
   double bound=Math.max(Math.max(sampled[i-1],sampled[i]),floor(RoadStructures.sample(mesh,(a.distance()+b.distance())/2),ground));
   // A voxel step may begin between samples. Constrain BOTH ends of this
   // segment to its highest terrain, so interpolation cannot enter the step.
   floors[i-1]=Math.max(floors[i-1],bound);floors[i]=Math.max(floors[i],bound);
  }
  return floors;
 }
 private static double floor(Sample at,Ground ground){
  double floor=Double.NEGATIVE_INFINITY;
  for(double u:new double[]{-.8,0,.8}){
   var p=at.at(u*at.halfWidth(),0);double top=ground.surface(p.x(),p.z(),p.y());
   if(Double.isFinite(top))floor=Math.max(floor,top-(p.y()-at.center().y()));
  }return floor;
 }
 public static boolean clear(Mesh mesh,double[] floors){
  if(floors==null)return true;
  for(int i=0;i<floors.length;i++)if(mesh.samples().get(i).center().y()<floors[i]-1e-5)return false;
  return true;
 }
 public static void validate(Mesh mesh,LanePoints.Options options,Ground ground){
  if(!clear(mesh,floors(mesh,options,ground)))throw new IllegalArgumentException("允许隧道已关闭：候选匝道进入地面以下；请开启允许隧道或调整接头/避障关系（地下接头不受此限制）");
 }
 private LaneRampTerrain(){}
}
