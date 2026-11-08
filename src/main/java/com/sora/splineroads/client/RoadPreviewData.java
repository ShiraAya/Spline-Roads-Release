package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.Mesh;
import com.sora.splineroads.world.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Pure immutable preview preparation on the existing bounded network decoder.
 * No level, renderer, texture or GUI state is read here. */
public record RoadPreviewData(Toll toll,Lane lane,List<Mesh> conflicts,Set<UUID> dependencies) {
  public record Toll(Mesh mesh,TollPlanner.Plan plan,List<Mesh> roads,List<RoadSurface.Face> faces) {}
  public record Lane(RoadRecord road,Mesh mesh,List<Mesh> roads,double minimumRadius) {}

  public static boolean queued(CompoundTag envelope) {
    String type=envelope.getString("Type");
    var payload=type.equals("open")?envelope.getCompound("Payload"):envelope;
    if(!payload.getList("ConflictRoads",Tag.TAG_COMPOUND).isEmpty())return type.equals("open")||type.equals("resultPreview");
    if(!type.equals("open")||payload.contains("Error"))return false;
    return Set.of("tollCheck","laneRampCheck","corridorCheck").contains(payload.getString("Kind"));
  }

  public static RoadPreviewData prepare(CompoundTag envelope) {
    return preparePayload(envelope.getString("Type").equals("open")?envelope.getCompound("Payload"):envelope);
  }

  public static RoadPreviewData preparePayload(CompoundTag payload) {
    var dependencies=new LinkedHashSet<UUID>();var conflicts=new ArrayList<Mesh>();
    for(Tag tag:payload.getList("ConflictRoads",Tag.TAG_COMPOUND)) {
      try {var road=RoadRecord.load((CompoundTag)tag);conflicts.add(road.mesh());depend(dependencies,road);}
      catch(IllegalArgumentException ignored) {}
    }
    Toll toll=null;Lane lane=null;
    if(!payload.contains("Error")&&payload.getString("Kind").equals("tollCheck")) {
      var input=payload.getCompound("Inputs");var spec=TollCodec.read(input.getCompound("Spec"));
      var roads=new ArrayList<RoadIndex.Built>();
      for(Tag tag:input.getList("Roads",Tag.TAG_COMPOUND)){var road=RoadRecord.load((CompoundTag)tag);depend(dependencies,road);roads.add(new RoadIndex.Built(road));}
      var station=roads.stream().filter(r->spec.equals(r.record.toll())).findFirst().orElseThrow(()->new IllegalArgumentException("收费站预览缺少站区"));
      var plan=station.record.tollPlan();var faces=new ArrayList<RoadSurface.Face>(plan.paint());
      for(var road:roads){
        road.record.structures().forEach(part->faces.addAll(part.faces()));
        if(road!=station)faces.addAll(RoadSurface.build(road.mesh,List.of(),roads.stream().filter(r->r!=road).map(r->r.mesh).toList()).markings());
      }
      toll=new Toll(station.mesh,plan,roads.stream().map(r->r.mesh).toList(),List.copyOf(faces));
    } else if(!payload.contains("Error")&&payload.getString("Kind").equals("laneRampCheck")) {
      var road=RoadRecord.load(payload.getCompound("Road"));var mesh=road.mesh();depend(dependencies,road);
      var views=new ArrayList<Mesh>();for(Tag tag:payload.getList("ChangedRoads",Tag.TAG_COMPOUND)) {
        var changed=RoadRecord.load((CompoundTag)tag);depend(dependencies,changed);views.add(changed.mesh());
      }
      lane=new Lane(road,mesh,views.isEmpty()?List.of(mesh):List.copyOf(views),RoadRibbon.minRadius(mesh));
    }
    return new RoadPreviewData(toll,lane,List.copyOf(conflicts),Set.copyOf(dependencies));
  }

  private static void depend(Set<UUID> ids,RoadRecord road) {
    ids.add(road.id());var link=LaneTopology.metadata(road).link();
    if(link!=null){if(link.from().road()!=null)ids.add(link.from().road());if(link.to().road()!=null)ids.add(link.to().road());}
  }

  /** Keep the original request envelope so the affected editor can offer retry. */
  public static CompoundTag failed(CompoundTag envelope,String message) {
    var out=envelope.copy();var payload=out.getString("Type").equals("open")?out.getCompound("Payload"):out;
    for(String key:List.of("Inputs","Road","ChangedRoads","ConflictRoads"))payload.remove(key);
    if(out.getString("Type").equals("open"))payload.putString("Error",message);
    else {out.putBoolean("Success",false);out.putString("Message",message);}
    return out;
  }
}
