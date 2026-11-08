package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
public final class RoadCrossingTransitions {
  public static RoadRecord fit(RoadRecord proposed,Collection<RoadRecord> roads){
    if(!AutoCrossings.eligible(proposed))return proposed;
    var mesh=proposed.caps(0).rawMesh();var others=roads.stream().filter(r->!r.id().equals(proposed.id())&&(AutoCrossings.eligible(r)||r.railway())).map(RoadRecord::mesh).toList();
    var fitted=RoadCrossingGrades.fit(mesh,others.stream().filter(m->m.settings().style()!=com.sora.splineroads.core.RoadGeometry.Style.RAILBED).toList());var result=fitted==mesh?proposed:proposed.alignment(proposed.assembly(),fitted);var rails=others.stream().filter(m->m.settings().style()==com.sora.splineroads.core.RoadGeometry.Style.RAILBED).toList();return result.withLevelCrossings(RailwayCrossings.surfaces(result.withLevelCrossings(List.of()).mesh(),rails));
  }
  public static Collection<RoadRecord> candidates(RoadData data){var roads=new LinkedHashMap<>(AutoJunctions.logical(data));data.index.roads.values().stream().map(b->b.record).filter(RoadRecord::railway).forEach(r->roads.put(r.id(),r));return roads.values();}
  public static RoadRecord preview(RoadRecord proposed,CompoundTag command){return fit(proposed,AutoCrossings.candidates(command));}
  private RoadCrossingTransitions(){}
}
