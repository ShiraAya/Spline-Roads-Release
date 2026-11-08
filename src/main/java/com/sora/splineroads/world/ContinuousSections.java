package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.nbt.*;

/** Keep only the occupied interval; generated continuations are retired on explicit rebuild. */
final class ContinuousSections {
  static UUID sourceId(String name){
    String value=name.startsWith(ContinuousRoads.MAIN)?name.substring(ContinuousRoads.MAIN.length()):name.startsWith(ContinuousRoads.DONOR)?name.substring(ContinuousRoads.DONOR.length()):null;
    return value==null?null:UUID.fromString(value.split("@")[0]);
  }
  static UUID id(String name){
    UUID source=sourceId(name);
    return name.contains("@")&&ContinuousRoads.donor(name)?UUID.nameUUIDFromBytes(("sr:continuous:"+name).getBytes(StandardCharsets.UTF_8)):source;
  }
  static RoadRecord source(CompoundTag tag,String name){
    RoadRecord source=ContinuousRoads.source(tag,sourceId(name));
    if(!name.contains("@"))return source;
    RoadRecord old=source.caps(0);
    String[] range=name.substring(name.indexOf('@')+1).split(",");double from=Double.parseDouble(range[0]),to=Double.parseDouble(range[1]);
    var a=RoadSplitGeometry.node(old.mesh(),from);var b=RoadSplitGeometry.node(old.mesh(),to);
    return RoadSplitGeometry.range(old,from,to,id(name),from==0?old.a():InterchangeJunctions.pos(a.position()),to==old.mesh().length()?old.b():InterchangeJunctions.pos(b.position()),a,b,RoadSlicing.Connection.CONTINUOUS).caps((from==0?source.endCaps()&1:0)|(to==old.mesh().length()?source.endCaps()&2:0));
  }
  static void append(List<Leg> out,Leg leg,RoadRecord original,Mesh generated,double first,double last,boolean moved){
    // End-cap overlap is render padding, not authored path stationing.
    original=original.caps(0);
    double length=moved?generated.length():original.mesh().length();first=Math.max(0,Math.min(length,first));last=Math.max(first,Math.min(length,last));
    // A moved endpoint already carries its adjacent donor; avoid splitting a sub-block remnant.
    if(moved||first<2&&length-last<2){out.add(new Leg(ContinuousRoads.MAIN+original.id(),leg.from(),leg.to(),generated));return;}
    if(first<2)first=0;if(length-last<2)last=length;
    // Only the occupied mainline exists in the new assembly. Do not manufacture
    // ordinary continuation roads beyond the planned terminal anchors.
    String name=ContinuousRoads.MAIN+original.id()+"@"+first+","+last;
    Node a=RoadSplitGeometry.node(original.mesh(),first),b=RoadSplitGeometry.node(original.mesh(),last);
    RoadRecord slice=RoadSplitGeometry.range(original,first,last,id(name),first==0?original.a():InterchangeJunctions.pos(a.position()),last==length?original.b():InterchangeJunctions.pos(b.position()),a,b,RoadSlicing.Connection.CONTINUOUS);
    var o=generated.settings().options();var saved=slice.settings().options();
    Settings settings=generated.settings().options(o.attachments(saved.attachments()).lanePoints(saved.lanePoints()).ends(saved.ends()).infrastructure(saved.infrastructure().gantry(o.infrastructure().gantry())));
    out.add(new Leg(name,leg.from(),leg.to(),RoadSlicing.slice(generated,first,last,settings).mesh()));
  }
  static boolean detached(CompoundTag tag,UUID id){
    return tag.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(t->(CompoundTag)t)
        .anyMatch(t->t.getBoolean("Detached")&&t.getCompound("Road").getUUID("Id").equals(id));
  }
  static void updateSources(CompoundTag tag,Plan plan){
    var list=new ListTag();
    for(var source:ContinuousRoads.sources(tag)){
      if(detached(tag,source.road().id()))continue;
      var legs=plan.legs().stream().filter(l->source.road().id().equals(sourceId(l.name()))&&l.name().contains("@")).toList();
      if(legs.isEmpty()){
        var previous=tag.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(t->(CompoundTag)t).filter(t->t.getCompound("Road").getUUID("Id").equals(source.road().id())).findFirst().orElseThrow();list.add(previous.copy());continue;
      }
      for(var leg:legs){var item=new CompoundTag();item.putInt("Axis",source.axis());item.put("Road",source(tag,leg.name()).header());item.putBoolean("Detached",ContinuousRoads.donor(leg.name()));list.add(item);}
    }
    tag.put("ContinuousSources",list);
  }
  static Map<UUID,Map<Integer,UUID>> signOwners(Plan plan,List<RoadIndex.Built> records){
    var out=new HashMap<UUID,Map<Integer,UUID>>();
    for(var leg:plan.legs())if(leg.name().contains("@")){
      var owners=out.computeIfAbsent(sourceId(leg.name()),k->new HashMap<>());var road=records.stream().filter(r->r.record.id().equals(id(leg.name()))).findFirst().orElseThrow();
      for(int slot:RoadGantry.slots(road.mesh))owners.put(slot,road.record.id());
    }return out;
  }
  static RoadRecord remap(RoadRecord road,Map<UUID,UUID> owners){
    var data=road.settings().options().lanePoints();var link=data.link();if(link==null)return road;
    var a=link.from();var b=link.to();var na=owners.get(a.point());var nb=owners.get(b.point());
    if(na!=null&&!na.equals(a.road()))a=LanePoints.Ref.lane(na,a.point());if(nb!=null&&!nb.equals(b.road()))b=LanePoints.Ref.lane(nb,b.point());
    return a==link.from()&&b==link.to()?road:road.withLanePoints(data.link(new LanePoints.Link(a,b,link.options(),link.junctionMouth(),link.targetOffset(),link.protectedMerge(),link.rectangularClosure())));
  }
  private ContinuousSections(){}
}
