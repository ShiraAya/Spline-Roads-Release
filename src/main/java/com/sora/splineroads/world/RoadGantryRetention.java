package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.Part;
import java.util.*;

/** Transaction-local proof that an indirectly enlisted road's remote authored
 * frame is outside this edit. Explicit road/gantry edits still rebuild normally. */
final class RoadGantryRetention {
  record Snapshot(RoadGantry.Station station,List<Part> parts){
    Snapshot{parts=List.copyOf(parts);}
    List<Part> at(RoadGantry.Station current){return station.equals(current)?parts:null;}
  }
  static Map<UUID,Map<Integer,Snapshot>> capture(List<RoadIndex.Built> candidates,Map<UUID,RoadIndex.Built> saved,Set<UUID> explicit,Set<UUID> removed){
    var changed=new ArrayList<RoadBoundsIndex.Bounds>();
    var replaced=new HashSet<UUID>();var localProof=new HashSet<UUID>();
    for(var candidate:candidates){
      var id=candidate.record.id();replaced.add(id);var before=saved.get(id);
      if(!explicit.contains(id)){var local=RoadEditScope.endpointRails(before,candidate);if(local!=null){localProof.add(id);changed.addAll(local);continue;}}
      // Use both footprints and saved fixture bounds. The same conservative
      // 82 m reach used by StructurePlanner covers automatic support searches.
      changed.add(RoadEditScope.bounds(candidate.mesh));if(!candidate.record.structures().isEmpty())changed.add(RoadEditScope.bounds(candidate.record.structures()));
      if(before!=null){changed.add(RoadEditScope.bounds(before.mesh));if(!before.record.structures().isEmpty())changed.add(RoadEditScope.bounds(before.record.structures()));}
    }
    // A pure deletion can remove support or terrain used by a saved frame.
    // Include its old footprint even though it has no replacement candidate.
    for(var id:removed){
      if(replaced.contains(id))continue;var before=saved.get(id);if(before==null)continue;
      changed.add(RoadEditScope.bounds(before.mesh));if(!before.record.structures().isEmpty())changed.add(RoadEditScope.bounds(before.record.structures()));
    }
    var result=new HashMap<UUID,Map<Integer,Snapshot>>();
    for(var candidate:candidates){
      RoadPlanningBudget.check();var id=candidate.record.id();var before=saved.get(id);
      if(!localProof.contains(id)||candidate.record.junction()!=null||candidate.record.toll()!=null)continue;
      var frames=new HashMap<Integer,Snapshot>();
      for(var edit:candidate.record.settings().options().infrastructure().gantryEdits()){
        int slot=edit.slot();if(!RoadGantry.exists(before.mesh,slot)||!RoadGantry.exists(candidate.mesh,slot))continue;
        var station=RoadGantry.station(candidate.mesh,slot);if(station.kind()==RoadInfrastructure.Gantry.OFF||!station.equals(RoadGantry.station(before.mesh,slot)))continue;
        var parts=RoadGantry.members(before.mesh,slot,before.record.structures());if(parts.isEmpty())continue;
        var footprint=RoadEditScope.bounds(parts);if(changed.stream().anyMatch(area->area.overlaps(footprint,82)))continue;
        frames.put(slot,new Snapshot(station,parts));
      }
      if(!frames.isEmpty())result.put(id,Map.copyOf(frames));
    }
    return Map.copyOf(result);
  }
  private RoadGantryRetention(){}
}
