package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import com.sora.splineroads.net.RoadWire;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Regressions use the November 15 reported world, plus mirrored geometry. */
public final class Followup211Validation {
  static int checks;
  static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  static void wire(CompoundTag tag){var assembler=new RoadWire.Assembler();CompoundTag result=null;var parts=RoadWire.split(tag);for(var part:parts){var next=assembler.accept(part,System.nanoTime());if(next!=null)result=next;}check(tag.equals(result),"network roundtrip");System.out.println("WIRE PASS parts="+parts.size());}
  public static void main(String[] args)throws Exception{
    RoadData data=Followup211Inspection.load();
    var command=new CompoundTag();AutoJunctions.enrich(command,data,new BlockPos(-16,56,558),new BlockPos(235,56,527),null);wire(command);
    var graph=AutoCrossings.class.getDeclaredMethod("graph",CompoundTag.class,CompoundTag.class);graph.setAccessible(true);
    var candidates=AutoCrossings.candidates(command);var entries=command.getList("CrossingCandidates",Tag.TAG_COMPOUND);
    var legacy=command.copy();var legacyEntries=new ListTag();
    for(int i=0;i<candidates.size();i++){
      var road=candidates.get(i);var decoded=(CompoundTag)graph.invoke(null,command,entries.getCompound(i));
      check(decoded.equals(road.railway()?new CompoundTag():AutoJunctions.context(data,Set.of(road.a(),road.b()),false)),"crossing graph changed: "+road.id());
      var entry=new CompoundTag();entry.put("Road",road.header());entry.put("Graph",decoded);legacyEntries.add(entry);
    }
    legacy.put("CrossingCandidates",legacyEntries);legacy.remove("CrossingRoads");
    check(AutoCrossings.candidates(legacy).equals(candidates),"legacy crossing candidates");
    var hits=candidates.stream().map(r->new AutoCrossings.Hit(r.id(),0,0,r.start().position())).toList();
    check(AutoCrossings.graph(legacy,hits).equals(AutoCrossings.graph(command,hits)),"merged crossing graph changed");
    var spui=data.interchanges.get(UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3")).copy();
    check(ParallelSpui.capture(data,spui,null),"SPUI capture");wire(spui);
    var context=spui.getCompound("ParallelJunction");var restore=ParallelSpui.class.getDeclaredMethod("contextRoad",CompoundTag.class,CompoundTag.class);restore.setAccessible(true);
    for(var value:context.getList("Roads",Tag.TAG_COMPOUND)){
      var road=(RoadRecord)restore.invoke(null,context,value);var original=data.index.roads.get(road.id()).record;
      check(road.equals(RoadRecord.load(original.header())),"pooled junction differs: "+road.id());
      check(road.equals(restore.invoke(null,new CompoundTag(),original.header())),"legacy SPUI header differs");
    }
    var host=data.index.roads.get(context.getUUID("Upper")).record;
    check(spui.getUUID("Id").equals(ParallelSpui.groupAt(data,host,new V(246,111,1185))),"ordinary host SPUI selection");
    var owner=data.index.roads.get(UUID.fromString("046ca5c8-5296-49a6-9d56-80ba68110158"));
    var blocker=data.index.roads.get(UUID.fromString("68a0db2e-d3ac-47c2-960a-51ceb700750b"));
    var part=owner.record.fragment().supports().stream().min(Comparator.comparingDouble(p->p.a().sub(new V(611.845901192415,56,48.114677072086)).horizontalLength())).orElseThrow();
    var saved=Map.of(owner.record.id(),owner,blocker.record.id(),blocker);
    var index=new RoadPlanningIndex(List.of(owner,blocker));index.editHistory(List.of(owner),saved,Set.of());
    check(index.unchangedFixedSupport(owner,part),"unchanged historical pier proof");
    index.editHistory(List.of(owner),saved,Set.of(owner.record.id()));check(index.unchangedFixedSupport(owner,part),"explicit membership must retain proven unchanged local pier");
    var movedTag=owner.record.header();var movedSamples=owner.mesh.samples().stream().map(s->new Sample(s.center().add(new V(0,.5,0)),s.left(),s.distance(),s.halfWidth())).toList();
    var moved=new RoadIndex.Built(owner.record.fragment(null).alignment(owner.record.assembly(),RoadRibbon.mesh(movedSamples,owner.mesh.settings())));
    index=new RoadPlanningIndex(List.of(moved,blocker));index.editHistory(List.of(moved),saved,Set.of(owner.record.id()));check(!index.unchangedFixedSupport(moved,part),"changed owner deck must revoke pier proof");
    var addedTag=blocker.record.header();addedTag.putUUID("Id",UUID.randomUUID());var added=new RoadIndex.Built(RoadRecord.load(addedTag));
    index=new RoadPlanningIndex(List.of(owner,blocker,added));index.editHistory(List.of(owner),saved,Set.of());check(!index.unchangedFixedSupport(owner,part),"new road bypassed clearance");
    var fixtures=new ArrayList<>(blocker.record.structures());fixtures.add(new RoadStructures.Part(part.a(),part.b(),3,4,true,RoadStructures.Material.CONCRETE));
    var changed=new RoadIndex.Built(blocker.record.structures(fixtures));index=new RoadPlanningIndex(List.of(owner,changed));index.editHistory(List.of(owner),saved,Set.of());check(!index.unchangedFixedSupport(owner,part),"new fixture bypassed clearance");
    for(String id:List.of("9a6726c7-fc4e-463f-a482-f635a46da7ee","54526339-ef86-49ab-9dc3-afc53de4272e"))for(var style:TrumpetStyle.values()){
      var input=data.interchanges.get(UUID.fromString(id)).copy();input.getCompound("Options").putString("TrumpetStyle",style.name());var plan=Interchanges.plan(input);
      check(plan.legs().size()>=(id.startsWith("9a")?6:13),"missing trumpet movements");
      String name=(id.startsWith("9a")?"single":"double")+"-"+style;
      export(name,plan);checkDirectional(plan,name,input);
    }
    System.out.println("FOLLOWUP PASS assertions="+checks);
  }
  static void checkDirectional(Plan plan,String name,CompoundTag input){
    int tails=0;
    for(var leg:plan.legs())if(leg.name().endsWith("左转")){
      var samples=leg.mesh().samples();double min=Double.POSITIVE_INFINITY;
      int axis=leg.name().startsWith("CD")?1:0;V origin=plan.anchors().get(axis*2).position(),normal=plan.anchors().get(axis*2+1).position().sub(origin).horizontalUnit().left();double hostHalf=RoadRecord.readSettings(input.getCompound("Main"+(axis+1))).width()/2;
      int insideRuns=0;boolean wasInside=false;
      for(var sample:samples){boolean inside=Math.abs(sample.center().sub(origin).dot(normal))<hostHalf+sample.halfWidth();if(inside&&!wasInside)insideRuns++;wasInside=inside;}
      check(insideRuns==2,name+" extra mainline intrusion, runs="+insideRuns);
      for(int i=4;i<samples.size()-4;i+=4){V a=samples.get(i-4).center(),b=samples.get(i).center(),c=samples.get(i+4).center(),ab=b.sub(a),bc=c.sub(b);double cross=Math.abs(ab.x()*bc.z()-ab.z()*bc.x());if(cross>1e-9)min=Math.min(min,ab.horizontalLength()*bc.horizontalLength()*c.sub(a).horizontalLength()/(2*cross));}
      check(min>=25,name+" directional radius="+min);tails++;System.out.println(name+" "+leg.name()+" minRadius="+min);
    }
    check(tails==(name.startsWith("single")?1:2),"directional tail coverage");
  }
  static void export(String name,Plan plan)throws Exception{
    var csv=new StringBuilder("leg,x,y,z,width\n");for(var leg:plan.legs())for(var s:leg.mesh().samples())csv.append("\"").append(leg.name().replace("\"","\"\"")).append("\"").append(',').append(s.center().x()).append(',').append(s.center().y()).append(',').append(s.center().z()).append(',').append(s.halfWidth()*2).append('\n');Files.writeString(Path.of("work/feedback-211-nov15-followup/"+name+".csv"),csv);
  }
}
