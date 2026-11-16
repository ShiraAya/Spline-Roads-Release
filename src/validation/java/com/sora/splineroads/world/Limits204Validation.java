package com.sora.splineroads.world;
import java.util.*;import net.minecraft.nbt.*;import net.minecraft.core.BlockPos;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.core.InterchangePlanner.*;
public final class Limits204Validation{
 static int checks;static void ok(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static ListTag tags(Node[] nodes){var list=new ListTag();for(var n:nodes)list.add(RoadRecord.writeNode(n));return list;}
 static BlockPos pos(Node n){var p=n.position();return BlockPos.containing(p.x(),p.y(),p.z());}
 static CompoundTag fixture(){
  V u=new V(Math.cos(.03610868),0,Math.sin(.03610868)),v=new V(-Math.sin(.11736355),0,Math.cos(.11736355));
  Node[] nodes={new Node(u.mul(-300),-87.9311249,0),new Node(u.mul(300),-87.9311249,0),new Node(v.mul(-300).add(new V(0,7,0)),6.7245123,0),new Node(v.mul(300).add(new V(0,7,0)),6.7245123,0)};
  var options=new Options(Preset.STACK,false,1,96,20,5,2,5,true,true,35);var s=new Settings(Mode.STRAIGHT,Style.H4_RAIL,28,1,.4,90);var t=new CompoundTag();t.put("Nodes",tags(nodes));t.put("Options",Interchanges.write(options));t.putLongArray("Points",Arrays.stream(nodes).mapToLong(n->pos(n).asLong()).toArray());t.put("Main1",RoadRecord.writeSettings(s));t.put("Main2",RoadRecord.writeSettings(s.structure(Structure.BRIDGE)));
  var sources=new ListTag();for(int axis=0;axis<2;axis++){Node a=nodes[axis*2],b=nodes[axis*2+1];var road=new RoadRecord(new UUID(204,axis),new UUID(204,204),pos(a),pos(b),a,b,axis==0?s:s.structure(Structure.BRIDGE),false,4);var item=new CompoundTag();item.putInt("Axis",axis);item.put("Road",road.header());sources.add(item);}t.put("ContinuousSources",sources);
  Node[] old={new Node(u.mul(-235),nodes[0].yaw(),0),new Node(u.mul(299.75),nodes[1].yaw(),0),new Node(v.mul(-146).add(new V(0,10,0)),nodes[2].yaw(),0),new Node(v.mul(104).add(new V(0,10,0)),nodes[3].yaw(),0)};t.put("ContinuousLimits",tags(old));return t;
 }
 public static void main(String[]args){
  var t=fixture();var original=HostAxes.nodes(t);var unchanged=t.copy();
  if(args.length>0&&args[0].equals("red")){boolean rejected=false;try{Interchanges.plan(t);}catch(IllegalArgumentException e){rejected=e.getMessage().contains("50%");e.printStackTrace();}ok(rejected,"baseline did not reproduce source seam failure");System.out.println("LIMITS204 RED reproduced 50% seam");return;}
  ok(Arrays.equals(ContinuousRoads.effectiveLimits(t),original),"stale inner limits beat authored endpoints");
  var plan=Interchanges.plan(t);ok(plan.anchors().size()==4,"missing anchors");for(int i=2;i<4;i++){ok(plan.anchors().get(i).position().sub(original[i].position()).horizontalLength()<1e-6,"raised road shortened across old-height tails");ok(Math.abs(plan.anchors().get(i).position().y()-19)<1e-6,"upper road not raised to required gap");}
  for(var leg:plan.legs()){ok(RoadGrades.maximum(leg.mesh())<=.15+1e-6,"grade criterion weakened");}
  var command=new CompoundTag();command.put("SourceNodes",t.getList("Nodes",Tag.TAG_COMPOUND).copy());command.putInt("SourceAxesHash",HostAxes.signature(t));command.put("FittedNodes",tags(plan.anchors().toArray(Node[]::new)));var confirmed=Interchanges.confirmedPlan(t,command);ok(confirmed.anchors().equals(plan.anchors()),"confirmed endpoints differ");for(int i=0;i<plan.legs().size();i++)ok(plan.legs().get(i).mesh().samples().equals(confirmed.legs().get(i).mesh().samples()),"confirmed geometry differs");
  ok(t.equals(unchanged),"planning changed original metadata");
  var same=t.copy();var sameLimits=original.clone();sameLimits[2]=new Node(original[2].position().add(new V(0,3,0)),original[2].yaw(),0);same.put("ContinuousLimits",tags(sameLimits));ok(ContinuousRoads.effectiveLimits(same)[2].equals(original[2]),"same-position stale height restored");
  V center=ContinuousFit.center(original,Interchanges.read(t.getCompound("Options")));V ray=original[0].position().sub(center).horizontalUnit();
  for(Node invalid:new Node[]{new Node(center.add(ray.mul(600)).add(ray.left().mul(2)),original[0].yaw(),0),new Node(center.sub(ray.mul(600)),original[0].yaw(),0),new Node(new V(Double.NaN,0,0),original[0].yaw(),0),new Node(center.add(ray.mul(600)),Double.NaN,0),new Node(center.add(ray.mul(600)),original[0].yaw(),Double.NaN)}){
   var malformed=t.copy();var invalidLimits=original.clone();invalidLimits[0]=invalid;malformed.put("ContinuousLimits",tags(invalidLimits));ok(ContinuousRoads.effectiveLimits(malformed)[0].equals(original[0]),"malformed or opposite limit granted more range: "+invalid);
  }
  var bad=command.copy();bad.getList("FittedNodes",Tag.TAG_COMPOUND).getCompound(0).putDouble("Z",original[0].position().z()+2);boolean badRejected=false;try{Interchanges.confirmedPlan(t,bad);}catch(IllegalArgumentException expected){badRejected=true;}ok(badRejected,"off-axis submitted endpoint authorized");
  var wrongHash=command.copy();wrongHash.putInt("SourceAxesHash",HostAxes.signature(t)+1);badRejected=false;try{Interchanges.confirmedPlan(t,wrongHash);}catch(IllegalArgumentException expected){badRejected=true;}ok(badRejected,"stale submitted axes authorized");
  var far=t.copy();var farLimits=original.clone();farLimits[1]=new Node(original[1].position().horizontalUnit().mul(4396),original[1].yaw(),0);far.put("ContinuousLimits",tags(farLimits));ok(ContinuousRoads.effectiveLimits(far)[1].equals(farLimits[1]),"actual farther 4096 cap discarded");boolean rejected=false;try{ContinuousRoads.physicalEnd(far,1,new Node(farLimits[1].position().horizontalUnit().mul(4400),original[1].yaw(),0));}catch(IllegalArgumentException e){rejected=e.getMessage().contains("4096");}ok(rejected,"cap inside uncaptured road authorized marker move");
  var cut=original[0];var inside=new Node(cut.position().mul(.9),cut.yaw(),cut.grade());ok(ContinuousRoads.virtual(t,0,inside),"cut inside current road not virtual");
  var off=t.copy();off.put("Options",Interchanges.write(Interchanges.read(t.getCompound("Options")).adjust(false)));rejected=false;try{Interchanges.plan(off);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,"endpoint adjustment disabled but level raised");
  boolean cancelled=false;try(var budget=RoadPlanningBudget.cancellable("cancel",()->true)){try{Interchanges.plan(t);}catch(RoadPlanningBudget.Aborted expected){cancelled=true;}}ok(cancelled,"cancellation swallowed");
  System.out.println("Limits204Validation PASS checks="+checks+" legs="+plan.legs().size()+" anchors="+plan.anchors());
 }
}
