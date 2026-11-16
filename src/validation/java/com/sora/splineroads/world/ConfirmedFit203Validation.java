package com.sora.splineroads.world;
import net.minecraft.nbt.*;import java.io.*;import java.util.*;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
public final class ConfirmedFit203Validation {
 static int checks;static void ok(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static CompoundTag command(CompoundTag t,com.sora.splineroads.core.InterchangePlanner.Plan p){var c=new CompoundTag();c.put("SourceNodes",t.getList("Nodes",Tag.TAG_COMPOUND).copy());c.putInt("SourceAxesHash",HostAxes.signature(t));var n=new ListTag();p.anchors().forEach(v->n.add(RoadRecord.writeNode(v)));c.put("FittedNodes",n);return c;}
 static void reject(CompoundTag t,CompoundTag c,String name){boolean rejected=false;try{Interchanges.confirmedPlan(t,c);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,"accepted "+name);}
 public static void main(String[]args)throws Exception{
  var original=NbtIo.readCompressed(new File(args[0]));CompoundTag firstSource=null,firstCommand=null;
  for(String preset:List.of("TURBINE","STACK","HYBRID","CLOVERLEAF","DOUBLE_TRUMPET","ROUNDABOUT")){
   var t=original.copy();t.getCompound("Options").putString("Preset",preset);t.getCompound("Options").putBoolean("AllowAbove",true);
   var plan=Interchanges.plan(t);var c=command(t,plan);long started=System.nanoTime();var verified=Interchanges.confirmedPlan(t,c);
   ok(verified.anchors().equals(plan.anchors()),"confirmed endpoints drifted "+preset);
   ok(verified.legs().size()==plan.legs().size(),"leg count changed "+preset);
   for(int i=0;i<plan.legs().size();i++)ok(plan.legs().get(i).mesh().samples().equals(verified.legs().get(i).mesh().samples()),"sample geometry changed "+preset+" leg="+i);
   System.out.println("CONFIRMED "+preset+" seconds="+(System.nanoTime()-started)/1e9+" legs="+plan.legs().size());
   if(firstSource==null){firstSource=t;firstCommand=c;}
  }
  for(String field:List.of("X","Y","Yaw","Grade")){var c=firstCommand.copy();var n=c.getList("FittedNodes",Tag.TAG_COMPOUND).getCompound(0);n.putDouble(field,n.getDouble(field)+1);reject(firstSource,c,"altered "+field);}
  var c=firstCommand.copy();c.getList("FittedNodes",Tag.TAG_COMPOUND).getCompound(0).putDouble("X",Double.NaN);reject(firstSource,c,"NaN");
  c=firstCommand.copy();c.getList("SourceNodes",Tag.TAG_COMPOUND).getCompound(0).putDouble("X",1);reject(firstSource,c,"stale source");
  c=firstCommand.copy();c.putInt("SourceAxesHash",c.getInt("SourceAxesHash")+1);reject(firstSource,c,"stale source axis");
  c=firstCommand.copy();c.remove("FittedNodes");reject(firstSource,c,"missing fitted nodes");
  c=firstCommand.copy();c.remove("SourceAxesHash");reject(firstSource,c,"missing axis witness");
  var fixed=firstSource.copy();fixed.put("Options",Interchanges.write(Interchanges.read(fixed.getCompound("Options")).adjust(false)));reject(fixed,firstCommand,"unapproved movement");
  ok(ConfirmedInterchangeFit.eligible(firstSource),"straight interchange lost confirmed route");
  var special=firstSource.copy();special.put("ParallelJunction",new CompoundTag());ok(!ConfirmedInterchangeFit.eligible(special),"parallel SPUI hijacked");
  special=firstSource.copy();special.put("Corridor",new CompoundTag());ok(!ConfirmedInterchangeFit.eligible(special),"corridor hijacked");
  special=firstSource.copy();var axes=new ArrayList<RoadAxis>(HostAxes.read(special));var axis=axes.get(0);var start=axis.at(0);var end=axis.at(axis.length());axes.set(0,new RoadAxis(List.of(start,start.add(end).mul(.5).add(new V(10,0,0)),end)));HostAxes.write(special,axes);ok(!ConfirmedInterchangeFit.eligible(special),"curved/retired host hijacked");
  special=firstSource.copy();axes=new ArrayList<>(HostAxes.read(special));axis=axes.get(0);axes.set(0,RoadAxis.straight(axis.at(0),axis.at(axis.length()).add(new V(0,2,0))));HostAxes.write(special,axes);ok(!ConfirmedInterchangeFit.eligible(special),"sloped host hijacked");
  special=firstSource.copy();special.getList("Nodes",Tag.TAG_COMPOUND).add(special.getList("Nodes",Tag.TAG_COMPOUND).getCompound(0).copy());ok(!ConfirmedInterchangeFit.eligible(special),"multi-way host hijacked");
  var domain=firstSource.copy();domain.put("Options",Interchanges.write(Interchanges.read(domain.getCompound("Options")).shrink(false)));var target=domain.getList("Nodes",Tag.TAG_COMPOUND).copy();var first=RoadRecord.readNode(target.getCompound(0));var center=ContinuousFit.center(HostAxes.nodes(domain),Interchanges.read(domain.getCompound("Options")));target.set(0,RoadRecord.writeNode(new Node(center.add(first.position().sub(center).mul(.9)),first.yaw(),first.grade())));c=firstCommand.copy();c.put("FittedNodes",target);
  ConfirmedInterchangeFit.candidate(domain,c);ok(true,"captured interior cut should remain permitted");
  domain.remove("ContinuousSources");domain.remove("ContinuousLimits");boolean rejected=false;try{ConfirmedInterchangeFit.candidate(domain,c);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,"allowShrink false accepted physical endpoint contraction");
  domain.put("Options",Interchanges.write(Interchanges.read(domain.getCompound("Options")).shrink(true)));ConfirmedInterchangeFit.candidate(domain,c);ok(true,"explicit allowShrink rejected");
  System.out.println("ConfirmedFit203Validation PASS checks="+checks);
 }
}
