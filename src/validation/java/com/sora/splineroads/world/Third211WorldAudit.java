package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
public final class Third211WorldAudit {
 public static void main(String[]args)throws Exception{
  var before=Third211Inspection.load();System.setProperty("inspection.world","work/feedback-211-nov15-third/client/saves/sr211-third/data/splineroads.dat");var after=Followup211Inspection.load();int checks=0;
  for(String id:List.of("3e39ef94-5b45-471e-b067-6188b26fd29a","b82e1f44-eebe-3d18-947b-892dd6d94985")){
   var key=UUID.fromString(id);var a=before.index.roads.get(key).record;var b=after.index.roads.get(key).record;var ids=new HashSet<UUID>();b.settings().options().lanePoints().points().forEach(p->ids.add(p.id()));
   for(var p:a.settings().options().lanePoints().points()){if(!ids.contains(p.id()))throw new AssertionError("existing lane point lost "+p.id());checks++;}
  }
  for(String id:List.of("1abf0a51-975f-3088-9f5b-061d4561fced","6e9458be-f710-418c-896d-af0378a68024")){
   var key=UUID.fromString(id);var r=after.index.roads.get(key);var m=r.mesh;boolean first=id.startsWith("6e94");var at=first?m.first():m.last();
   for(int side:new int[]{-1,1}){var center=at.at(RoadProfile.layout(m,at).outer(side)+side*.75,-.45);boolean continuous=r.record.structures().stream().anyMatch(p->p.material()==RoadStructures.Material.GREEN&&p.model().equals("sr:cycle_planter")&&(p.a().distance(center)<1e-5&&Math.abs(p.frameA().horizontalLength()-.6)<1e-5||p.b().distance(center)<1e-5&&Math.abs(p.frameB().horizontalLength()-.6)<1e-5));if(!continuous)throw new AssertionError("persisted planting not continuous "+id+" side="+side);checks++;}
   if(!r.record.start().equals(before.index.roads.get(key).record.start())||!r.record.end().equals(before.index.roads.get(key).record.end()))throw new AssertionError("planting edit moved road ports");checks++;
  }
  var group=UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3");int ramps=0;
  for(var r:after.index.roads.values())if(group.equals(r.record.assembly())&&r.mesh.settings().style().ramp()){
   ramps++;var m=r.mesh;double max=0;for(int i=1;i<m.samples().size();i++){var delta=m.samples().get(i).center().sub(m.samples().get(i-1).center());max=Math.max(max,Math.abs(delta.y())/delta.horizontalLength());}
   if(m.length()>=200||max<.10||max>.15)throw new AssertionError("saved ramp is not compact "+r.record.id());checks++;System.out.println("SAVED RAMP "+r.record.id()+" length="+m.length()+" peakGrade="+max);
  }
  if(ramps!=4)throw new AssertionError("saved ramp count");System.out.println("Third211WorldAudit PASS checks="+(checks+1)+" original host points retained; persisted four planting joins; port positions retained; four compact ramps");
 }
}
