package com.sora.splineroads.world;

import java.util.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import net.minecraft.core.BlockPos;

/** AUTO tunnel mouths must plan successfully without exempting remote lining. */
public final class AutoTunnelPortal211Validation {
 static RoadRecord road(long id,V a,V b){
  var s=new Settings(Mode.STRAIGHT,Style.O6_GREEN,32,1,.4,90).structure(Structure.AUTO);
  s=s.options(s.options().extras(false,false,false).route(s.options().routing().fit(false)));
  var n=new Node(a,RoadPlanner.yaw(b.sub(a)),0);return new RoadRecord(new UUID(0,id),new UUID(0,0),BlockPos.containing(a.x(),a.y(),a.z()),BlockPos.containing(b.x(),b.y(),b.z()),n,new Node(b,n.yaw(),0),s,false,4);
 }
 static LanePoints.Ref point(Map<UUID,RoadRecord> all,RoadRecord r,double station,int sign,long id){
  var m=r.mesh();var sample=RoadStructures.sample(m,station);double outer=-1;int slot=-1;
  for(int i=0;i<RoadProfile.catalog(m.settings()).lanes();i++){var lane=LanePoints.lane(m,station,i);double d=Math.abs(lane.position().sub(sample.center()).dot(sample.left()));if(lane.sign()==sign&&d>outer){slot=i;outer=d;}}
  var p=LanePoints.point(new UUID(1,id),LanePoints.Origin.MANUAL,m,station,slot);all.put(r.id(),r.withLanePoints(LaneTopology.metadata(r).points(List.of(p))));return LanePoints.Ref.lane(r.id(),p.id());
 }
 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
  var ground=new Ground(){public double top(double x,double z,double y){return 56;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};
  var host=road(1,new V(-600,32,400),new V(600,32,400));host=host.structures(RoadAutoTunnels.enclose(host.mesh(),ground,List.of()));
  if(RoadAutoTunnels.regions(host.structures()).isEmpty())throw new AssertionError("no AUTO tunnel");
  var source=road(2,new V(300,56,-600),new V(300,56,600));var all=new LinkedHashMap<UUID,RoadRecord>();all.put(host.id(),host);all.put(source.id(),source);
  var from=point(all,source,480,1,1);var to=point(all,host,360,-1,2);
  var options=new LanePoints.Options(LanePoints.Path.AUTO,LanePoints.Departure.BRANCH,LanePoints.Arrival.EXTRA,32,32,LanePoints.Elevation.AUTO,LanePoints.Landing.EXACT,false,true);
  var ramp=LaneRamps.generate(null,all,new UUID(0,3),new UUID(0,0),new LanePoints.Link(from,to,options,null));
  LaneRamps.validate(ramp.mesh(),all,ramp.id(),LaneTopology.metadata(ramp).link());
  var method=LaneRamps.class.getDeclaredMethod("routingStructures",Mesh.class,RoadRecord.class,Map.class,Set.class,Set.class);method.setAccessible(true);
  var unrelated=(List<Part>)method.invoke(null,ramp.mesh(),host,all,Set.of(),Set.of());
  if(!unrelated.equals(host.structures()))throw new AssertionError("unrelated lining exempted");
  var opened=(List<Part>)method.invoke(null,ramp.mesh(),host,all,Set.of(),Set.of(host.id()));
  int retained=0;var m=ramp.mesh();
  for(var p:host.structures()){double pad=p.halfExtent();boolean remote=Math.max(p.a().x(),p.b().x())+pad<m.min().x()-8||Math.min(p.a().x(),p.b().x())-pad>m.max().x()+8||Math.max(p.a().z(),p.b().z())+pad<m.min().z()-8||Math.min(p.a().z(),p.b().z())-pad>m.max().z()+8;
   if(remote){if(!opened.contains(p))throw new AssertionError("remote lining removed");retained++;}
  }
  if(retained<10||opened.equals(host.structures()))throw new AssertionError("ineffective portal fixture");
  System.out.println("PASS AUTO tunnel route, unrelated shell equality, remote parts retained="+retained+" original="+host.structures().size()+" opened="+opened.size());
 }
}
