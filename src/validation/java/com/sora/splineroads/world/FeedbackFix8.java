package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
public final class FeedbackFix8 {
 static int checks;
 static RoadStructures.Ground ground(double top){return new RoadStructures.Ground(){
  public double top(double x,double z,double y){return Double.isNaN(top)?y:top;}
  public boolean blocked(RoadStructures.Part p){return false;}
  public boolean joined(V p){return false;}
 };}

 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
 static void rejects(Runnable r,String message){try{r.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");
  var roads=new ArrayList<RoadRecord>();
  for(var value:root.getList("Roads",Tag.TAG_COMPOUND)){
   var r=RoadRecord.load((CompoundTag)value);var p=r.start().position();
   if(p.x()>4900&&p.x()<5100&&p.z()>9900&&p.z()<10100)roads.add(r);
  }
  roads.sort(Comparator.comparingDouble((RoadRecord r)->r.start().position().y()).reversed());
  check(roads.size()==3,"three feedback roads");
  for(var r:roads.subList(0,2)){
   var mesh=r.caps(0).mesh();
   var rails=RoadStructures.roadsideSupports(mesh,ground(56));
   check(!rails.isEmpty(),"elevated GROUND road must have rails");
   var full=RoadStructures.plan(mesh,ground(56),RoadFurniture.Phase.DEFAULT);
   check(full.containsAll(rails),"full furniture planner preserves outside barriers");
   for(int side:new int[]{-1,1})check(rails.stream().anyMatch(p->p.a().sub(mesh.first().center()).dot(mesh.first().left())*side>mesh.first().halfWidth()-1),"both outside edges");
   check(RoadStructures.roadsideSupports(mesh,ground(Double.NaN)).isEmpty(),"supported GROUND road must not gain rails");
   var off=RoadRibbon.mesh(mesh.samples(),mesh.settings().options(mesh.settings().options().outerRail(RoadProfile.OuterRail.OFF)));
   check(RoadStructures.roadsideSupports(off,ground(56)).isEmpty(),"explicit OFF");
   System.out.println("FIX8_RAIL_PASS y="+r.start().position().y()+" parts="+rails.size());
  }
  for(int count:new int[]{6,5}){
   var t=new CompoundTag();var nodes=new ListTag();var points=new ArrayList<Long>();var sources=new ListTag();
   var ordered=count==6?roads:List.of(roads.get(1),roads.get(2),roads.get(0));
   for(int axis=0;axis<3;axis++){
    var r=ordered.get(axis);t.put("Main"+(axis+1),RoadRecord.writeSettings(RoadProfile.editable(r.settings())));
    nodes.add(RoadRecord.writeNode(r.start()));points.add(r.a().asLong());
    if(nodes.size()<count){nodes.add(RoadRecord.writeNode(r.end()));points.add(r.b().asLong());var item=new CompoundTag();item.putInt("Axis",axis);item.put("Road",r.header());sources.add(item);}
   }
   t.put("Nodes",nodes);t.putLongArray("Points",points);t.put("ContinuousSources",sources);t.put("ContinuousLimits",nodes.copy());t.putDouble("MultiBaseY",56);
   var options=new InterchangePlanner.Options(count==6?InterchangePlanner.Preset.DIRECTIONAL_SIX:InterchangePlanner.Preset.DIRECTIONAL_FIVE,false,1,96,20,5,2,0,true,true,0);
   t.put("Options",Interchanges.write(options));
   System.out.println("FIX8_PLAN_BEGIN arms="+count);
   try(var budget=RoadPlanningBudget.open("fix8 feedback",240)){
    var preview=Interchanges.plan(t);var command=Interchanges.command(t,preview,"build");
    check(ConfirmedInterchangeFit.eligible(t),"multi preview eligible");
    var confirmed=Interchanges.confirmedPlan(t,command);
    check(preview.anchors().equals(confirmed.anchors()),"exact accepted preview endpoints");
    check(preview.movements()==confirmed.movements(),"all movements retained");
    check(confirmed.legs().stream().allMatch(l->RoadGrades.maximum(l.mesh())<=.150001),"grade limits");
    var stale=command.copy();stale.getList("SourceNodes",Tag.TAG_COMPOUND).getCompound(0).putDouble("Y",999);
    rejects(()->ConfirmedInterchangeFit.candidate(t,stale),"reject changed source");
    var height=command.copy();height.getList("FittedNodes",Tag.TAG_COMPOUND).getCompound(0).putDouble("Y",999);
    rejects(()->ConfirmedInterchangeFit.candidate(t,height),"reject forged height");
    var sideways=command.copy();var q=sideways.getList("FittedNodes",Tag.TAG_COMPOUND).getCompound(0);q.putDouble("X",q.getDouble("X")+13);q.putDouble("Z",q.getDouble("Z")+7);
    rejects(()->ConfirmedInterchangeFit.candidate(t,sideways),"reject off-axis endpoint");
    System.out.println("FIX8_PLAN_PASS arms="+count+" movements="+confirmed.movements()+" legs="+confirmed.legs().size());
   }
  }
  System.out.println("FIX8_PASS checks="+checks);
 }
}
