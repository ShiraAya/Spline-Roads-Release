package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
public final class FeedbackFix2Inspect {
 static void railOverlap(String path)throws Exception{
  var root=NbtIo.readCompressed(Path.of(path).toFile()).getCompound("data");var rails=new ArrayList<Map.Entry<UUID,RoadStructures.Part>>();var walks=new ArrayList<Map.Entry<UUID,RoadStructures.Part>>();var roads=new ArrayList<RoadIndex.Built>();
  for(var tag:root.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)tag);var built=RoadIndex.Built.loading(r);if(built.mesh.min().x()<1235&&built.mesh.max().x()>1175&&built.mesh.min().z()<1025&&built.mesh.max().z()>975)roads.add(built);for(var p:r.structures()){
   if(Math.max(p.a().x(),p.b().x())<1180||Math.min(p.a().x(),p.b().x())>1230||Math.max(p.a().z(),p.b().z())<980||Math.min(p.a().z(),p.b().z())>1020||Math.min(p.a().y(),p.b().y())>59)continue;
   if(p.material()==RoadStructures.Material.STEEL||p.material()==RoadStructures.Material.DARK_STEEL)rails.add(Map.entry(r.id(),p));
   if(RoadSidewalks.smoothPart(p))walks.add(Map.entry(r.id(),p));
  }}
  int count=0;System.out.println("RAIL_OVERLAP_SOURCE "+path+" rails="+rails.size()+" walks="+walks.size());
  int deckHits=0;for(var entry:rails){var part=entry.getValue();if(part.material()!=RoadStructures.Material.STEEL)continue;var p=part.a().add(part.b()).mul(.5).add(new V(0,part.height()/2,0));var cell=new RoadRaster.Cell((int)Math.floor(p.x()),(int)Math.floor(p.y()),(int)Math.floor(p.z()));
   for(var road:roads){var boxes=road.prepareCollision().deckColumn(cell.x(),cell.z()).getOrDefault(cell,List.of());
    if(boxes.stream().anyMatch(b->p.x()>cell.x()+b.x0()+.0001&&p.x()<cell.x()+b.x1()-.0001&&p.y()>cell.y()+b.y0()+.0001&&p.y()<cell.y()+b.y1()-.0001&&p.z()>cell.z()+b.z0()+.0001&&p.z()<cell.z()+b.z1()-.0001)){
     deckHits++;if(deckHits<=15)System.out.println("RAIL_INSIDE_DECK rail="+entry.getKey()+" p="+p+" deck="+road.record.id());
    }
   }
  }System.out.println("RAIL_DECK_HITS "+deckHits);
  int exact=0;for(var entry:rails)for(var road:roads)if(!entry.getKey().equals(road.record.id())&&RoadClearance.structureInDeck(entry.getValue(),road.mesh)){exact++;if(exact<5)System.out.println("EXACT_DECK_CONFLICT "+road.record.id()+" "+entry.getValue());}System.out.println("EXACT_DECK_COUNT "+exact);
  for(var rail:rails){var hits=walks.stream().filter(w->RoadSolidOverlap.intersects(rail.getValue(),w.getValue())).toList();if(!hits.isEmpty()){
   count++;System.out.println("BURIED_RAIL owner="+rail.getKey()+" part="+rail.getValue()+" footways="+hits.stream().map(Map.Entry::getKey).distinct().toList());
  }}System.out.println("RAIL_OVERLAP_COUNT "+count);
 }
 public static void main(String[] args)throws Exception{
  if(args.length>1){railOverlap(args[1]);railOverlap(args[0]);return;}
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var roads=new ArrayList<RoadIndex.Built>();
  for(var tag:root.getList("Roads",Tag.TAG_COMPOUND))roads.add(new RoadIndex.Built(RoadRecord.load((CompoundTag)tag),true));
  System.out.println("FIX2_ROADS "+roads.size());
  for(var p:List.of(new V(2702,56,22),new V(1081,56,1471),new V(1207,56,1001),new V(1217,56,939),new V(1567,56,705))){
   System.out.println("FIX2_AT "+p);
   roads.stream().sorted(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,p).horizontalDistance())).limit(3).forEach(r->{
    var q=RoadQueries.horizontal(r.mesh,p);System.out.println("  "+r.record.id()+" distance="+q.horizontalDistance()+" station="+q.sample().distance()+" style="+r.mesh.settings().style()+" structure="+r.mesh.settings().structure()+" start="+r.mesh.first().center()+" end="+r.mesh.last().center()+" parts="+r.record.structures().size()+" points="+LaneTopology.metadata(r.record).points()+" link="+LaneTopology.metadata(r.record).link());
   });
  }
  for(var r:roads){
   for(var p:r.record.structures())if((p.material()==RoadStructures.Material.STEEL||p.material()==RoadStructures.Material.DARK_STEEL)&&p.height()<1.3&&p.a().y()<59&&p.a().add(p.b()).mul(.5).sub(new V(1207,56,1001)).horizontalLength()<20)
    System.out.println("RAIL "+r.record.id()+" "+p);
   var near=r.record.structures().stream().filter(p->p.material()==RoadStructures.Material.TUNNEL&&p.a().add(p.b()).mul(.5).sub(new V(1217,56,939)).horizontalLength()<20).toList();
   if(!near.isEmpty()){
    System.out.println("WALL_OWNER "+r.record.id()+" parts="+r.record.structures().size());
    for(var p:near)System.out.println("WALL "+p.a().add(p.b()).mul(.5)+" top="+(p.a().y()+p.b().y())/2+" h="+p.height()+" tag="+p.model());
   }
   if(r.record.id().equals(UUID.fromString("50716f67-d317-49c8-9982-2006341a502a"))){
    System.out.println("OPEN_RANGES "+RoadAutoTunnels.openRegions(r.record.structures()));
    System.out.println("OPTS "+r.record.settings().options().streetscape());
   }
  }
 }
}
