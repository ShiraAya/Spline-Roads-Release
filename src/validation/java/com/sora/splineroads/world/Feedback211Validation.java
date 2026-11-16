package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Replays the supplied world's road snapshot without loading or editing its world. */
public final class Feedback211Validation {
  static int checks;
  static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
  static RoadData load()throws Exception{
    var root=NbtIo.readCompressed(Path.of("work/feedback-211-nov15/fps-roads.dat").toFile()).getCompound("data");
    var data=new RoadData();
    for(var t:root.getList("Roads",Tag.TAG_COMPOUND))data.index.put(new RoadIndex.Built(RoadRecord.load((CompoundTag)t)));
    for(var t:root.getList("Interchanges",Tag.TAG_COMPOUND)){var g=(CompoundTag)t;data.interchanges.put(g.getUUID("Id"),g);}
    for(var t:root.getList("Junctions",Tag.TAG_COMPOUND)){var g=(CompoundTag)t;data.junctions.put(g.getUUID("Id"),g);}
    return data;
  }
  public static void main(String[] args)throws Exception{
    AccessTerminal203Validation.main(args);
    var data=load();
    var receipt=data.interchanges.values().stream().filter(t->t.getBoolean("ElevatedAccess")).findFirst().orElseThrow();
    ElevatedAccessRoads.protect(data,List.of(),Set.of());checks++;
    var farHost=data.index.roads.get(UUID.fromString("b7017daa-18ee-417d-9fab-e96728670cf3")).record;
    var farEnds=farHost.settings().options().ends();
    var remote=farHost.settings(farHost.settings().options(farHost.settings().options().ends(farEnds.trim(0,24))));
    ElevatedAccessRoads.protect(data,List.of(new RoadIndex.Built(remote)),Set.of(farHost.id()));checks++;
    boolean nearRejected=false;
    var near=farHost.settings(farHost.settings().options(farHost.settings().options().ends(farEnds.trim(24,0))));
    try{ElevatedAccessRoads.protect(data,List.of(new RoadIndex.Built(near)),Set.of(farHost.id()));}catch(IllegalArgumentException e){nearRejected=true;}
    check(nearRejected,"junction trim erased occupied access zone");
    var copy=receipt.copy();var stale=UUID.randomUUID();copy.getList("Hosts",Tag.TAG_INT_ARRAY).add(NbtUtils.createUUID(stale));data.interchanges.put(copy.getUUID("Id"),copy);
    ElevatedAccessRoads.protect(data,List.of(),Set.of());checks++;
    UUID host=NbtUtils.loadUUID(receipt.getList("Hosts",Tag.TAG_INT_ARRAY).get(0));
    boolean rejected=false;try{ElevatedAccessRoads.protect(data,List.of(),Set.of(host));}catch(IllegalArgumentException e){rejected=true;}check(rejected,"real host deletion escaped protection");
    var captured=RoadRecord.load(NbtIo.readCompressed(Path.of("work/feedback-211-nov15/client/feedback211/access-after.nbt").toFile()));
    ElevatedAccessRoads.protect(data,List.of(new RoadIndex.Built(captured)),Set.of(captured.id()));checks++;
    var ordinary=data.index.roads.values().stream().filter(b->b.record.junction()!=null&&b.record.junction().spec().center().distance(new V(580,56,802))<45).findFirst().orElseThrow().record.junction();
    var old=plan(ordinary.spec(),55);var next=plan(ordinary.spec(),56);
    double oldArea=area(old),nextArea=area(next);check(nextArea>oldArea+5,"unpaired sidewalk corner has not gained missing pavement");
    check(nextArea<oldArea+500,"corner unexpectedly filled beyond its local sidewalk");
    System.out.println("CORNER PASS oldArea="+oldArea+" newArea="+nextArea);
    var support=data.index.roads.values().stream().filter(b->b.record.junction()!=null&&b.record.junction().spec().center().distance(new V(572,56,1200))<3).findFirst().orElseThrow().record.junction();
    var center=support.spec().center();check(JunctionSupportSpace.contains(pier(center),support),"intersection median-axis centre is unavailable");
    // Observed bad placements from the first native build: these followed a
    // constant widened-mouth divider instead of the actual moving median.
    check(!JunctionSupportSpace.contains(pier(new V(502.270778,56,1199.63)),support),"old left pier still accepted outside planting");
    check(!JunctionSupportSpace.contains(pier(new V(646.324617,56,1200.62)),support),"old right pier still accepted outside planting");
    for(int i=0;i<support.spec().arms().size();i++){
      var arm=support.spec().arms().get(i);var mesh=support.furnitureApproach(i);var sample=RoadStructures.sample(mesh,mesh.length()/2);
      if(arm.medianKind()==RoadProfile.Median.GREEN){check(JunctionSupportSpace.contains(pier(sample.at(JunctionPlanner.approachMedian(arm,mesh,sample,true,support.spec().leftTraffic()),0)),support),"green median rejected arm="+i);}
      check(!JunctionSupportSpace.contains(pier(sample.at(arm.divider(support.spec().leftTraffic())+arm.median()/2+3,0)),support),"live lane accepted arm="+i);
      if(arm.crosswalk()){var crossing=RoadStructures.sample(mesh,mesh.length()-arm.crossingSetback()-arm.crossingWidth()/2);check(!JunctionSupportSpace.contains(pier(crossing.at(arm.divider(support.spec().leftTraffic()),0)),support),"crosswalk accepted arm="+i);}
    }
    for(var style:TrumpetStyle.values()){
      var options=new Options(Preset.TRUMPET,false,1,96,20,5,2).trumpet(style);
      check(Interchanges.read(Interchanges.write(options)).equals(options),"style NBT roundtrip");
      check(options.above(false).connector(false,0).junction(1).trumpetStyle()==style,"option copies reset style");
    }
    var legacy=Interchanges.write(new Options(Preset.TRUMPET,false,1,96,20,5,2));legacy.remove("TrumpetStyle");check(Interchanges.read(legacy).trumpetStyle()==TrumpetStyle.A,"legacy default is not A");
    var input=data.interchanges.values().stream().filter(ParallelSpui::active).findFirst().orElseThrow().copy();
    ParallelSpui.capture(data,input,null);
    var plan=Interchanges.plan(input);int ramps=0;
    StringBuilder csv=new StringBuilder("leg,x,y,z\n");
    for(var leg:plan.legs())if(leg.mesh().settings().style().ramp()){
      ramps++;var m=leg.mesh();var a=m.first().center();var b=m.last().center();var direction=b.sub(a).horizontalUnit();
      double length=b.sub(a).horizontalLength(),min=0,max=0;
      for(var p:m.samples()){double along=p.center().sub(a).dot(direction);check(along>=-.01&&along<=length+.01,"parallel ramp doubles back");double side=p.center().sub(a).dot(direction.left());min=Math.min(min,side);max=Math.max(max,side);csv.append(leg.name()).append(',').append(p.center().x()).append(',').append(p.center().y()).append(',').append(p.center().z()).append('\n');}
      System.out.println("SPUI "+leg.name()+" chord="+length+" path="+m.length()+" lateral="+min+".."+max);
      check(Math.max(-min,max)<6,"parallel ramp unnecessarily bows away from direct corridor");
    }
    check(ramps==4,"SPUI does not contain four ramps");Files.writeString(Path.of("work/feedback-211-nov15/spui.csv"),csv);
    System.out.println("Feedback211Validation PASS checks="+checks);
  }
  static JunctionPlanner.Plan plan(JunctionSpec spec,int version)throws Exception{var m=JunctionPlanner.class.getDeclaredMethod("plan",JunctionSpec.class,int.class);m.setAccessible(true);return (JunctionPlanner.Plan)m.invoke(null,spec,version);}
  static RoadStructures.Part pier(V p){return new RoadStructures.Part(p,p,1.5,8,true,RoadStructures.Material.CONCRETE);}
  static double area(JunctionPlanner.Plan p){return p.pieces().stream().flatMap(x->x.structures().stream()).filter(x->x.material().name().startsWith("WALK_")).mapToDouble(x->Math.abs(JunctionPaint.area(x.base()))).sum();}
}
