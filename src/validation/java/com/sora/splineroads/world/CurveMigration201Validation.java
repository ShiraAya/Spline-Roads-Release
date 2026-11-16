package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import net.minecraft.core.BlockPos;import java.util.*;
public class CurveMigration201Validation {
 static int checks;static void require(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static RoadRecord proposal(RoadRecord r,Settings s){return new RoadRecord(r.id(),r.owner(),r.a(),r.b(),r.start(),r.end(),s,false,4);}
 static void samePath(Mesh a,Mesh b){for(var p:a.samples())require(p.center().distance(RoadSlicing.at(b,p.distance()).center())<1e-7,"shape changed at "+p.distance());}
 static Settings control(RoadRecord r,List<RoadProfile.Control> cs){return r.settings().options(r.settings().options().route(r.settings().options().routing().controls(cs)));}
 public static void main(String[]args){shortSliceRetainsAlignment();for(boolean through:new boolean[]{false,true}){
  var a=new Node(new V(0,3,0),0,0);var b=new Node(new V(0,3,300),0,0);
  var s=RoadLanes.configure(new Settings(Mode.CURVE,Style.O2_YELLOW,9,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);
  s=s.options(s.options().route(s.options().routing().fit(false).controls(List.of(new RoadProfile.Control(UUID.randomUUID(),new V(45,3,100)),new RoadProfile.Control(UUID.randomUUID(),new V(45,3,200)))).through(through)));
  var road=new RoadRecord(UUID.randomUUID(),UUID.randomUUID(),new BlockPos(0,3,0),new BlockPos(0,3,300),a,b,s,false,4);
  var half=RoadSplitGeometry.split(road,road.mesh().length()*.3,RoadSlicing.Connection.CONTINUOUS,UUID.randomUUID()).first();
  var f=half.fragment();var old=half.settings(half.settings().options(half.settings().options().route(s.options().routing()))).fragment(new RoadSlicing.Fragment(f.source(),f.origin(),f.total(),f.connection(),f.applied(),f.geometry(),f.supports()));
  var tag=old.save();tag.getCompound("Slice").putInt("Version",1);tag.getCompound("Slice").remove("CurveAnchors");var legacy=RoadRecord.load(tag);var original=legacy.rawMesh();
  require(legacy.fragment().curveAnchors().isEmpty(),"load silently migrated old road");
  var local=legacy.localCurveForEditing();require(!local.fragment().curveAnchors().isEmpty(),"explicit editor failed to localize");samePath(original,local.rawMesh());require(local.localCurveForEditing()==local,"migration is not idempotent");
  require(legacy.retainedPlan(legacy.settings(),RoadPlanner.Hint.free(legacy.start()),RoadPlanner.Hint.free(legacy.end()),legacy.start(),legacy.end())!=null,"unchanged old request lost alignment");
  samePath(original,legacy.retainAlignment(proposal(legacy,legacy.settings())).rawMesh());
  var cs=new ArrayList<>(local.settings().options().routing().controls());var c=cs.get(0);cs.set(0,new RoadProfile.Control(c.id(),c.position().add(new V(.1,0,0))));
  var changed=control(local,cs);var edited=legacy.retainAlignment(proposal(local,changed));require(edited.fragment()!=null,"normalized UI request fell through on old server record");var before=edited.rawMesh();
  var loaded=RoadRecord.load(edited.save());require(loaded.rawMesh().samples().equals(before.samples()),"v2 save/load drift");
  var resized=RoadLanes.configure(changed,RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4.1);var wider=local.retainAlignment(proposal(local,resized));require(wider.fragment()!=null,"combined width/control edit dropped fragment");require(Math.abs(wider.rawMesh().first().halfWidth()-local.rawMesh().first().halfWidth())>.01,"width edit ignored");
  var extra=new ArrayList<>(cs);extra.add(new RoadProfile.Control(UUID.randomUUID(),RoadSlicing.at(local.rawMesh(),local.rawMesh().length()*.7).center().add(new V(.05,0,0))));
  var added=local.retainAlignment(proposal(local,control(local,extra)));require(added.fragment()!=null,"added local handle refit whole curve");added.rawMesh();
  var removed=local.retainAlignment(proposal(local,control(local,List.of())));require(removed.fragment()!=null,"removing handles refit whole curve");samePath(local.rawMesh(),removed.rawMesh());
  var second=RoadSplitGeometry.split(edited,edited.mesh().length()*.55,RoadSlicing.Connection.CONTINUOUS,UUID.randomUUID());
  for(var piece:List.of(second.first(),second.second())){for(var p:piece.rawMesh().samples())require(p.center().distance(RoadSlicing.at(before,piece.fragment().origin()+p.distance()).center())<1e-7,"second split double-applied deformation");require(piece.retainedPlan(piece.settings(),RoadPlanner.Hint.free(piece.start()),RoadPlanner.Hint.free(piece.end()),piece.start(),piece.end())!=null,"second split cannot save");}
  var moved=new Node(local.start().position().add(new V(1,0,0)),local.start().yaw(),local.start().grade());require(local.retainedPlan(local.settings(),RoadPlanner.Hint.free(moved),RoadPlanner.Hint.free(local.end()),moved,local.end())==null,"endpoint move incorrectly reused anchored fragment");
 }System.out.println("CurveMigration201Validation PASS checks="+checks);}

 static void shortSliceRetainsAlignment(){
  var a=new Node(new V(.5,3,.5),0,0);var b=new Node(new V(.5,3,80.5),0,0);
  var s=new Settings(Mode.CURVE,Style.O2_YELLOW,9,1,.35,90);
  var source=new RoadRecord(UUID.randomUUID(),UUID.randomUUID(),new BlockPos(0,3,0),new BlockPos(0,3,80),a,b,s,false,4).caps(0);
  var end=RoadSplitGeometry.node(source.mesh(),3);
  var shortRoad=RoadSplitGeometry.range(source,0,3,source.id(),source.a(),new BlockPos(0,3,3),a,end,RoadSlicing.Connection.CONTINUOUS);
  var saved=RoadRecord.load(shortRoad.save());
  require(saved.fragment().curveAnchors().isEmpty(),"fixture must have no space for curve handles");
  require(saved.retainedPlan(saved.settings(),RoadPlanner.Hint.free(saved.start()),RoadPlanner.Hint.free(saved.end()),saved.start(),saved.end())!=null,"short split preview lost saved path");
  require(saved.localCurveForEditing()==saved,"short split localization churns");
  samePath(saved.rawMesh(),saved.retainAlignment(proposal(saved,saved.settings())).rawMesh());
 }}
