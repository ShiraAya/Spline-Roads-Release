package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;

/** Exact solids and paint regressions from the second submitted save. */
public final class FeedbackFix2Core {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static double volume(List<List<V>> faces){double v=0;for(var f:faces)for(int i=1;i+1<f.size();i++){
  var a=f.get(0);var b=f.get(i);var c=f.get(i+1);
  v+=a.x()*(b.y()*c.z()-b.z()*c.y())+a.y()*(b.z()*c.x()-b.x()*c.z())+a.z()*(b.x()*c.y()-b.y()*c.x());
 }return v/6;}
 static void solid(String name,double expected,double cap,List<Part> cuts){
  var faces=TerrainSolidGeometry.retain(new V(0,0,0),cap,List.of(),cuts);
  double actual=volume(faces)*cap;
  check(Math.abs(actual-expected)<1e-7,name+" volume="+actual+" expected="+expected);
  for(var f:faces)for(var p:f)check(p.x()>=0&&p.y()>=0&&p.z()>=0&&p.x()<=1&&p.y()<=1&&p.z()<=1,"cell bounds");
  System.out.println("FIX2_SOLID_PASS "+name+" faces="+faces.size()+" volume="+actual);
 }
 public static void main(String[] args)throws Exception{
  var random=new Random(2122);int maskCases=0;
  for(double cap:new double[]{.25,.5,1})for(int trial=0;trial<20;trial++){
   var cuts=new ArrayList<RoadRaster.Box>();
   var expected=net.minecraft.world.phys.shapes.Shapes.box(0,0,0,1,cap,1);
   for(int n=0;n<24;n++){
    double x=random.nextInt(8)/8.0,y=random.nextInt(8)/8.0,z=random.nextInt(8)/8.0;
    var box=new RoadRaster.Box(x,y,z,Math.min(1,x+.125),Math.min(1,y+.375),Math.min(1,z+.25));cuts.add(box);
    expected=net.minecraft.world.phys.shapes.Shapes.joinUnoptimized(expected,net.minecraft.world.phys.shapes.Shapes.box(box.x0(),box.y0(),box.z0(),box.x1(),box.y1(),box.z1()),net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST);
   }
   var mask=RoadInfillMask.cutBoxes(cap,List.of(),cuts);
   var actual=mask.clipped()?RoadCollisionShape.build(mask.boxes().stream().map(b->new RoadRaster.Box(b.x0(),b.y0()*cap,b.z0(),b.x1(),b.y1()*cap,b.z1())).toList()):net.minecraft.world.phys.shapes.Shapes.box(0,0,0,1,cap,1);
   check(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(expected,actual,net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME),"mask collision changed");maskCases++;
  }
  System.out.println("FIX2_MASK_EQUIVALENCE_PASS cases="+maskCases);
  var half=new Part(new V(.75,-1,-1),new V(.75,-1,2),.5,3,false,Material.TUNNEL);
  solid("uncut",1,1,List.of());solid("half",.5,1,List.of(half));
  solid("duplicate cut",.5,1,List.of(half,half));
  solid("cap",.25,.5,List.of(half));
  solid("diagonal",.25,1,List.of(new Part(new V(-2,-1,-2),new V(3,-1,3),Math.sqrt(.5),3,false,Material.TUNNEL)));
  solid("partial height",.75,1,List.of(new Part(new V(.75,.5,-1),new V(.75,.5,2),.5,2,false,Material.TUNNEL)));
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var roads=new LinkedHashMap<UUID,RoadIndex.Built>();
  for(var tag:root.getList("Roads",Tag.TAG_COMPOUND)){var b=new RoadIndex.Built(RoadRecord.load((CompoundTag)tag),true);roads.put(b.record.id(),b);}
  var main=roads.get(UUID.fromString("2c0e4c31-4bcd-4877-9c24-96a38665b5e2"));
  var nearby=roads.values().stream().filter(r->r!=main&&RoadIndex.overlapXZ(main.mesh,r.mesh,3)).map(r->r.mesh).toList();
  var paint=RoadJunction.markings(main.mesh,List.of(),nearby,true);
  int guides=0;
  for(var p:paint){var center=p.points().stream().reduce(new V(0,0,0),V::add).mul(1.0/p.points().size());
   var q=RoadQueries.horizontal(main.mesh,center);var layout=RoadProfile.layout(main.mesh,q.sample());int side=q.lateral()<0?-1:1;
   if(center.z()>1400&&center.z()<1510&&Math.abs(q.lateral()-layout.outer(side))<.2)guides++;
  }
  check(guides>10,"ordinary interchange motor guides missing: "+guides);
  System.out.println("FIX2_GUIDES_PASS count="+guides+" total="+paint.size());
  var tunnel=roads.get(UUID.fromString("50716f67-d317-49c8-9982-2006341a502a"));
  var cover=new RoadTerrainCover(chunk->List.of(tunnel),true);int recovered=0;
  for(var open:RoadAutoTunnels.openRegions(tunnel.record.structures())){
   var at=RoadStructures.sample(tunnel.mesh,(open.from()+open.to())/2);
   if(cover.surface(at.center().x(),at.center().z(),at.center().y(),p->Double.NaN)>=at.center().y()+2)recovered++;
  }
  check(recovered>0,"cleared open-cut cover not recovered");System.out.println("FIX2_OPEN_CUT_PASS recovered="+recovered);
  var bridge=roads.get(UUID.fromString("2618586e-ceb0-4a05-839d-cd6080c8d27c"));var at=bridge.mesh.first().center();
  check(Double.isNaN(new RoadTerrainCover(c->List.of(bridge),true).surface(at.x(),at.z(),at.y(),p->Double.NaN)),"bridge misclassified as a cut");
  var road=roads.get(UUID.fromString("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8"));
  var sample=RoadStructures.sample(road.mesh,100);var a=sample.at(0,-3);var b=sample.at(1,-3);
  var walk=new Part(a,b,1,.5,false,Material.WALK_STONE_BRICKS);
  check(new SidewalkJoins(List.of(road.mesh),List.of()).clip(List.of(walk)).isEmpty(),"low footway roof blocks traffic");
  var high=new Part(a.add(new V(0,6,0)),b.add(new V(0,6,0)),1,.5,false,Material.WALK_STONE_BRICKS);
  check(!new SidewalkJoins(List.of(road.mesh),List.of()).clip(List.of(high)).isEmpty(),"high crossing footway removed");
  System.out.println("FIX2_WALK_CLEARANCE_PASS");
  var closure=road.mesh.settings().options().lanePoints().cuts().stream().filter(c->c.temporary()&&c.rectangular()).findFirst().orElseThrow();
  double d=(closure.begin()+closure.end())/2;
  var closed=LanePoints.lane(LaneSections.reference(road.mesh),d,closure.lane()).position();
  var wall=new Part(closed.add(new V(0,-.4,-.1)),closed.add(new V(0,-.4,.1)),.2,1,false,Material.TUNNEL);
  var liveAir=RoadShellOpening.travel(road.mesh,0,road.mesh.length(),x->4.25);
  check(liveAir.cut(wall).equals(List.of(wall)),"closed lane cuts neighboring retaining wall");
  var live=LanePoints.lane(road.mesh,d,0).position();
  var obstacle=new Part(live.add(new V(0,0,-.1)),live.add(new V(0,0,.1)),.2,1,false,Material.TUNNEL);
  check(liveAir.cut(obstacle).isEmpty(),"live lane clearance lost");
  System.out.println("FIX2_LIVE_APERTURE_PASS");
  var ramp=roads.get(UUID.fromString("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688")).record;
  long intersections=ramp.structures().stream().filter(RoadRailSlabClipping::guardrail).filter(p->RoadClearance.structureInDeck(p,road.mesh)).count();
  var clippedRails=RoadRailSlabClipping.clip(ramp.structures(),List.of(road.mesh));
  check(clippedRails.stream().filter(RoadRailSlabClipping::guardrail).noneMatch(p->RoadClearance.structureInDeck(p,road.mesh)),"guardrail remains inside a physical lane footing");
  check(clippedRails.stream().filter(RoadRailSlabClipping::guardrail).count()>100,"visible guardrail removed wholesale");
  int preservedPosts=0;
  for(var post:ramp.structures())if(RoadRailSlabClipping.guardrail(post)&&post.material()==Material.DARK_STEEL&&post.height()>.4){
   double top=post.top().stream().mapToDouble(V::y).max().orElseThrow();
   if(top<=road.mesh.max().y()+.3||!RoadClearance.structureInDeck(post,road.mesh))continue;
   var remains=RoadRailSlabClipping.clip(List.of(post),List.of(road.mesh));
   check(!remains.isEmpty()&&Math.abs(remains.stream().flatMap(p->p.top().stream()).mapToDouble(V::y).max().orElseThrow()-top)<1e-7,"slab cut deleted the exposed post top");
   preservedPosts++;
  }
  // New feedback worlds may already contain the repaired posts. Always retain a
  // positive embedded-foot control instead of depending on old damaged geometry.
  var position=LanePoints.lane(road.mesh,100,0).position().add(new V(0,-.5,0));
  var embedded=new Part(position,position.add(new V(0,0,.16)),.16,1.08,false,Material.DARK_STEEL);
  check(RoadClearance.structureInDeck(embedded,road.mesh),"embedded post control misses deck");
  var retained=RoadRailSlabClipping.clip(List.of(embedded),List.of(road.mesh));
  check(!retained.isEmpty()&&retained.stream().noneMatch(p->RoadClearance.structureInDeck(p,road.mesh)),"embedded post control not clipped");
  check(Math.abs(retained.stream().flatMap(p->p.top().stream()).mapToDouble(V::y).max().orElseThrow()-position.y()-1.08)<1e-7,"embedded post control loses exposed top");
  System.out.println("FIX2_POST_RETENTION_PASS count="+preservedPosts);
  System.out.println("FIX2_RAIL_SLAB_PASS before="+intersections+" after=0");
  var midpoint=RoadStructures.sample(ramp.mesh(),ramp.mesh().length()/2);var left=midpoint.left();
  var aNode=new Node(midpoint.center().add(left.mul(-30)),RoadPlanner.yaw(left),0);
  var bNode=new Node(midpoint.center().add(left.mul(30)),RoadPlanner.yaw(left),0);
  var crossing=new RoadRecord(UUID.randomUUID(),ramp.owner(),net.minecraft.core.BlockPos.containing(aNode.position().x(),aNode.position().y(),aNode.position().z()),net.minecraft.core.BlockPos.containing(bNode.position().x(),bNode.position().y(),bNode.position().z()),aNode,bNode,
      new Settings(Mode.STRAIGHT,Style.O2_DASHED,9,1,.35,0,9,9,Structure.GROUND,3,RampTurn.AUTO));
  var obstacles=new LinkedHashMap<UUID,RoadRecord>();roads.forEach((id,built)->obstacles.put(id,built.record));obstacles.put(crossing.id(),crossing);
  boolean rejected=false;try{LaneRamps.validateChanges(ramp.mesh(),obstacles,ramp.id(),LaneTopology.metadata(ramp).link(),Set.of(crossing.id()));}catch(IllegalArgumentException conflict){rejected=true;}
  check(rejected,"new crossing road bypassed retained-route validation");
  var s=crossing.settings();var thicker=crossing.settings(new Settings(s.mode(),s.style(),s.width(),s.thickness()+1,s.tension(),s.arcDegrees(),s.startWidth(),s.endWidth(),s.structure(),s.taperVersion(),s.rampTurn(),s.options()));
  check(LaneTopology.rawObstacleChanged(crossing,thicker),"thickness change omitted from obstacle validation");
  System.out.println("FIX2_NEW_CONFLICT_REJECTED");
  for(var id:List.of("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688","680cdaf5-5dfb-4521-ab08-6319d0ba7bd8","50716f67-d317-49c8-9982-2006341a502a")){
   var r=roads.get(UUID.fromString(id));System.out.println("FIX2_FIXTURE "+id+" style="+r.mesh.settings().style()+" structure="+r.mesh.settings().structure()+" parts="+r.record.structures().size());
  }
  System.out.println("FIX2_CORE_PASS roads="+roads.size());
 }
}
