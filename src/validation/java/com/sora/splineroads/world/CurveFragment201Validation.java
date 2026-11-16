package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import net.minecraft.core.BlockPos;import java.util.*;
public class CurveFragment201Validation {
 static int checks;static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[]args){for(boolean through:new boolean[]{false,true}){
 var a=new Node(new V(0,3,0),0,0);var b=new Node(new V(0,3,300),0,0);
 var s=RoadLanes.configure(new Settings(Mode.CURVE,Style.O2_YELLOW,9,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);
 s=s.options(s.options().route(s.options().routing().fit(false).controls(List.of(new RoadProfile.Control(UUID.randomUUID(),new V(45,3,100)),new RoadProfile.Control(UUID.randomUUID(),new V(45,3,200)))).through(through)));
 var road=new RoadRecord(UUID.randomUUID(),UUID.randomUUID(),new BlockPos(0,3,0),new BlockPos(0,3,300),a,b,s,false,4);
 for(boolean junction:new boolean[]{false,true}){
 double d=road.mesh().length()*.3;var node=RoadSplitGeometry.node(road.mesh(),d);var pos=BlockPos.containing(node.position().x(),node.position().y(),node.position().z());
 var first=junction?RoadSplitGeometry.junctionRange(road,0,d,road.id(),road.a(),pos,road.start(),node,RoadSlicing.Connection.JUNCTION):RoadSplitGeometry.split(road,d,RoadSlicing.Connection.CONTINUOUS,UUID.randomUUID()).first();
 var second=RoadSplitGeometry.range(road,d,road.mesh().length(),UUID.randomUUID(),pos,road.b(),node,road.end(),RoadSlicing.Connection.CONTINUOUS);
 for(var half:List.of(first,second)){
 var before=half.rawMesh();double origin=half.fragment().origin();for(var p:before.samples())require(p.center().distance(RoadSlicing.at(road.rawMesh(),origin+p.distance()).center())<1e-7,"split changed path");
 var controls=half.settings().options().routing().controls();require(!controls.isEmpty(),"no editable local handle");
 for(var c:controls){double at=RoadAttachments.station(before,c.position());require(at>1&&at<before.length()-1,"whole-road control leaked outside fragment");}
 var cs=new ArrayList<>(controls);var c=cs.get(0);cs.set(0,new RoadProfile.Control(c.id(),c.position().add(new V(.1,0,0))));
 var changed=half.settings().options(half.settings().options().route(half.settings().options().routing().controls(cs)));
 var plan=half.retainedPlan(changed,RoadPlanner.Hint.free(half.start()),RoadPlanner.Hint.free(half.end()),half.start(),half.end());require(plan!=null,"local edit fell through to whole-curve refit");
 var proposal=new RoadRecord(half.id(),half.owner(),half.a(),half.b(),half.start(),half.end(),changed,false,4);
 var edited=half.retainAlignment(proposal);var mesh=edited.rawMesh();require(mesh.first().center().distance(before.first().center())<1e-8,"start moved");require(mesh.last().center().distance(before.last().center())<1e-8,"end moved");require(mesh.first().left().distance(before.first().left())<1e-8,"start tangent moved");require(mesh.last().left().distance(before.last().left())<1e-8,"end tangent moved");
 var loaded=RoadRecord.load(edited.save());require(loaded.rawMesh().samples().equals(mesh.samples()),"reload shape drift");require(loaded.fragment().curveAnchors().equals(edited.fragment().curveAnchors()),"lost anchors");
 require(loaded.retainAlignment(loaded.settings(changed)).rawMesh().samples().equals(mesh.samples()),"resave shape drift");
 }
 }
 }System.out.println("CurveFragment201Validation PASS checks="+checks);}
}
