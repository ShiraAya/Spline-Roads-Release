package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
public final class FeedbackFix9 {
 static int checks;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 static Ground ground(boolean supported,boolean blockBracket){return new Ground(){
  public double top(double x,double z,double y){return supported?y:56;}
  public boolean blocked(Part p){return blockBracket&&p.material()==Material.CONCRETE&&p.width()==.7;}
  public boolean joined(V p){return false;}
 };}
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");
  RoadRecord road=null;
  for(var tag:root.getList("Roads",Tag.TAG_COMPOUND)){
   var r=RoadRecord.load((CompoundTag)tag);
   if(r.settings().style()==Style.O6_RAIL&&r.settings().structure()==Structure.GROUND&&r.start().position().y()==70&&r.start().position().x()>4500&&r.end().position().x()>5300)road=r;
  }
  check(road!=null,"feedback elevated road found");var mesh=road.caps(0).mesh();
  var oldFeet=road.structures().stream().map(RoadStreetscape::lampFoot).filter(Objects::nonNull).distinct().toList();check(!oldFeet.isEmpty(),"saved floating lamps present");
  System.out.println("FIX9_SAVED road="+road.id()+" lamps="+oldFeet.size()+" nearest="+oldFeet.stream().min(Comparator.comparingDouble(p->p.distance(new V(4912,70,10089)))).orElseThrow());
  var phase=FurnitureSpacing.onMesh(road,mesh);
  var parts=RoadStreetscape.plan(mesh,ground(false,false),phase);
  var feet=parts.stream().map(RoadStreetscape::lampFoot).filter(Objects::nonNull).distinct().toList();check(!feet.isEmpty(),"generated lamps");
  var nearest=oldFeet.stream().min(Comparator.comparingDouble(p->p.distance(new V(4912,70,10089)))).orElseThrow();
  check(feet.stream().anyMatch(p->p.distance(nearest)<1e-6),"feedback lamp position preserved");
  for(var foot:feet){
   var group=parts.stream().filter(p->foot.equals(RoadStreetscape.lampFoot(p))).toList();
   var beam=group.stream().filter(p->p.material()==Material.CONCRETE&&p.width()==.7).findFirst().orElseThrow();
   var riser=group.stream().filter(p->p.material()==Material.CONCRETE&&p.height()==.12).findFirst().orElseThrow();
   var at=RoadStructures.sample(mesh,foot.sub(mesh.first().center()).dot(mesh.last().center().sub(mesh.first().center()).horizontalUnit()));
   check(Math.abs(beam.a().sub(at.center()).dot(at.left()))<at.halfWidth(),"beam embedded in slab");
   check(beam.a().y()>=at.center().y()-mesh.settings().thickness()&&beam.a().y()+beam.height()<=at.center().y(),"beam seated below surface");
   check(beam.b().sub(foot).horizontalLength()<1e-7,"beam reaches lamp");
   check(Math.abs(beam.b().y()+beam.height()-riser.a().y())<1e-7,"beam touches riser");
   check(Math.abs(riser.a().y()+riser.height()-foot.y())<1e-7,"riser touches pedestal");
  }
  var full=RoadStructures.plan(mesh,ground(false,false),phase);check(full.containsAll(parts),"complete planner retains lamp supports");
  var saved=RoadRecord.load(road.structures(parts).save());check(saved.structures().equals(parts),"lamp supports survive NBT roundtrip");
  var supported=RoadStreetscape.plan(mesh,ground(true,false),phase);check(supported.stream().noneMatch(p->p.width()==.7&&p.material()==Material.CONCRETE),"ground-supported lamps need no bracket");
  check(!supported.isEmpty(),"ground lamps retained");
  var blocked=RoadStreetscape.plan(mesh,ground(false,true),phase);check(blocked.stream().noneMatch(p->RoadStreetscape.lampFoot(p)!=null),"blocked bracket removes whole lamp");
  var off=RoadRibbon.mesh(mesh.samples(),mesh.settings().options(mesh.settings().options().streetscape(mesh.settings().options().streetscape().lamps(false))));
  check(RoadStreetscape.plan(off,ground(false,false),phase).stream().noneMatch(p->RoadStreetscape.lampFoot(p)!=null),"disabled lamps remain disabled");
  System.out.println("FIX9_PASS lamps="+feet.size()+" checks="+checks);
 }
}
