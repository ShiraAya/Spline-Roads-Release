package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
public class Third211Seams {
 public static void main(String[]args)throws Exception{
  var d=Third211Inspection.load();
  for(String id:List.of("1abf0a51-975f-3088-9f5b-061d4561fced","6e9458be-f710-418c-896d-af0378a68024")){
   var r=d.index.roads.get(UUID.fromString(id)).record;var m=r.mesh();System.out.println("ROAD "+id+" start="+r.start()+" end="+r.end()+" style="+r.settings().style()+" ends="+r.settings().options().ends()+" decor="+r.settings().options().streetscape());
   boolean first=id.startsWith("6e94");var s=first?m.first():m.last();System.out.println("SEAM "+s+" layout="+RoadProfile.layout(m,s));
   r.structures().stream().filter(p->p.model().equals("sr:cycle_planter")&&p.material()==RoadStructures.Material.GREEN&&(p.a().sub(s.center()).horizontalLength()<23||p.b().sub(s.center()).horizontalLength()<23)).forEach(p->System.out.println("PLANTER "+p));
  }
  for(var r:d.index.roads.values())if(UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3").equals(r.record.assembly())&&r.mesh.settings().style().ramp()){
   var m=r.mesh;double high=Math.max(m.first().center().y(),m.last().center().y()),d0=0;boolean fromStart=m.first().center().y()==high;var a=new ArrayList<>(m.samples());if(!fromStart)Collections.reverse(a);for(var s:a){if(Math.abs(s.center().y()-high)>.1)break;d0=fromStart?s.distance():m.length()-s.distance();}System.out.println("SAVED_RAMP "+r.record.id()+" length="+m.length()+" highPlateau="+d0);
  }
 }
}
