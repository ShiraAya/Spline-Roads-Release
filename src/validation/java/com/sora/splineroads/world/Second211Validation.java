package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import net.minecraft.core.BlockPos;
public final class Second211Validation {
 static int checks;static void check(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  var d=Second211Inspection.load();
  var seam=new BlockPos(-1601,56,1123);check(d.index.internalInterchangeNode(seam),"internal double trumpet seam visible");
  check(!d.index.internalInterchangeNode(new BlockPos(-16,56,558)),"independent endpoint hidden");
  for(var t:d.interchanges.values())for(long pos:t.getLongArray("Points"))check(!d.index.internalInterchangeNode(BlockPos.of(pos)),"external interchange end hidden "+BlockPos.of(pos));
  var main=d.index.roads.get(UUID.fromString("a7dd524c-6876-479e-9d20-1a0f6116ce99")).mesh;
  var neighbors=d.index.roads.values().stream().filter(r->r.mesh!=main&&RoadIndex.overlapXZ(main,r.mesh,3)).map(r->r.mesh).toList();
  var paint=RoadJunction.markings(main,neighbors,neighbors,true);
  for(double station:new double[]{238.75,241.75}){
   var at=RoadStructures.sample(main,station);var point=at.at(at.halfWidth(),0);
   check(paint.stream().anyMatch(p->p.points().stream().anyMatch(v->v.distance(point)<1.4)),"dash missing beneath loop crossing at "+station);
  }
  var upper=d.index.roads.get(UUID.fromString("3e39ef94-5b45-471e-b067-6188b26fd29a")).mesh;
  var context=d.index.roads.values().stream().filter(r->r.mesh!=upper&&RoadIndex.overlapXZ(upper,r.mesh,3)).map(r->r.mesh).toList();
  var upperPaint=RoadJunction.markings(upper,context,context,true);
  for(double distance:new double[]{937.75,940.75,943.75,946.75})for(int side:new int[]{-1,1}){
    var at=RoadStructures.sample(upper,distance);var point=at.at(side*at.halfWidth(),0);
    check(upperPaint.stream().anyMatch(p->p.points().stream().anyMatch(v->v.distance(point)<1.4)),"SPUI shallow merge dash missing at "+distance+" side="+side);
  }
  var lower=d.index.roads.get(UUID.fromString("b82e1f44-eebe-3d18-947b-892dd6d94985")).record;
  var candidate=new RoadIndex.Built(new RoadRecord(UUID.randomUUID(),lower.owner(),lower.b(),new BlockPos(1206,56,1217),lower.end(),new Node(new V(1206.5,56,1217.5),lower.end().yaw(),0),new Settings(Mode.STRAIGHT,Style.O8_GREEN,42,1,.4,90),false,4));
  var ramp=d.index.roads.get(UUID.fromString("c3802cbe-8ab8-3d57-920f-1defe718edee"));
  check(RoadInteractions.influences(candidate,ramp),"generated pier owner not enlisted");
  var authored=new RoadIndex.Built(ramp.record.alignment(null,ramp.mesh));boolean rejected=false;
  try{RoadInteractions.influences(candidate,authored);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected,"independent authored ramp collision ignored");
  var pier=ramp.record.structures().stream().filter(p->p.pier()&&RoadClearance.structureContacts(p,candidate.mesh,4.25).stream().anyMatch(RoadClearance.Contact::blocked)).findFirst().orElseThrow();
  var fixed=new RoadIndex.Built(ramp.record.structures(List.of(new RoadStructures.Part(pier.a(),pier.b(),pier.width(),pier.height(),true,RoadStructures.Material.STEEL))));rejected=false;
  try{RoadInteractions.influences(candidate,fixed);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected,"generated fixed fitting collision ignored");
  var cut=RoadGeometry.build(new Node(new V(0,56,0),90,0),new Node(new V(40,56,0),90,0),new Settings(Mode.STRAIGHT,Style.UNMARKED,5,1,.4,90));
  var cuts=List.of(cut);var reuse=new SidewalkJoins(cuts,List.of());int clipped=0;
  for(int i=0;i<40;i++){
   var proxy=new RoadStructures.Part(new V(-5,56,-8+i*.4),new V(45,56,4+i*.1),1.2,.4,false,RoadStructures.Material.WALK_STONE_BRICKS);
   var expected=new SidewalkJoins(cuts,List.of()).clip(List.of(proxy));
   check(expected.equals(reuse.clipIndependent(List.of(proxy))),"cached independent clipping differs");if(!expected.equals(List.of(proxy)))clipped++;
  }
  check(clipped>10,"insufficient intersecting clipping controls");
  System.out.println("Second211Validation PASS checks="+checks);
 }
}
