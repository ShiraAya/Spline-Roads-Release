package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;

/** Server-owned whitelist: only the double trumpet's central carriageway accepts tolls. */
final class InterchangeConnectors {
 private static final String ROADS="TollConnectorRoads",SHAPE="TollConnectorShape";
 static List<UUID> ids(CompoundTag t){var out=new ArrayList<UUID>();for(Tag v:t.getList(ROADS,Tag.TAG_STRING))out.add(UUID.fromString(v.getAsString()));return out;}
 static boolean eligible(RoadData data,RoadRecord road){var t=road.assembly()==null?null:data.interchanges.get(road.assembly());return t!=null&&!t.getBoolean("IntegratedToll")&&t.getCompound("Options").getString("Preset").equals("DOUBLE_TRUMPET")&&ids(t).contains(road.id());}
 static RoadRecord group(RoadRecord r,UUID group){return new RoadRecord(r.id(),r.owner(),r.a(),r.b(),r.start(),r.end(),r.settings(),r.automatic(),r.clearance(),r.structures(),r.endCaps(),r.buildVersion(),group,r.alignment(),r.furniturePhase(),r.junction(),r.toll(),r.fragment());}
 static RoadRecord forTollSplit(RoadRecord road){
  // The station transaction regenerates its longitudinal guardrails. A curved,
  // graded rail/edge prism is non-planar and cannot use the lossless solid cutter.
  // Keep every pier, modeled fixture, lamp and other indivisible body protected.
  var mesh=road.mesh();return road.structures(road.structures().stream().filter(p->{
   if(RoadSplitGeometry.linear(p)||p.pier()||!p.model().isEmpty()||p.heights()!=null||p.frameA()==null||p.frameB()==null||p.width()>.7||p.height()>1.1||!Set.of(RoadStructures.Material.CONCRETE,RoadStructures.Material.STEEL,RoadStructures.Material.DARK_STEEL).contains(p.material()))return true;
   var a=RoadQueries.horizontal(mesh,p.a()).sample();var b=RoadQueries.horizontal(mesh,p.b()).sample();
   double lateralA=Math.abs(p.a().sub(a.center()).dot(a.left())),lateralB=Math.abs(p.b().sub(b.center()).dot(b.left()));
   boolean aboveDeck=p.a().y()>=a.center().y()-.05&&p.b().y()>=b.center().y()-.05;
   return !aboveDeck&&(lateralA<a.halfWidth()-.8||lateralB<b.halfWidth()-.8)||p.a().y()<a.center().y()-mesh.settings().thickness()-.05||p.b().y()<b.center().y()-mesh.settings().thickness()-.05||Math.abs(p.frameA().horizontalUnit().dot(a.left()))<.999||Math.abs(p.frameB().horizontalUnit().dot(b.left()))<.999;
  }).toList());
 }
 static void capturePart(CompoundTag t,RoadRecord r){var list=t.getList(ROADS,Tag.TAG_STRING);list.add(StringTag.valueOf(r.id().toString()));t.put(ROADS,list);}
 static void capture(CompoundTag t,RoadRecord r){var ids=new ListTag();ids.add(StringTag.valueOf(r.id().toString()));t.put(ROADS,ids);t.put(SHAPE,r.header());}
 static CompoundTag split(CompoundTag before,RoadRecord old,RoadSplitGeometry.Pair pair){
  var ids=ids(before);if(!ids.contains(old.id()))return before;var t=before.copy();var list=new ListTag();for(UUID id:ids){list.add(StringTag.valueOf(id.toString()));if(id.equals(old.id()))list.add(StringTag.valueOf(pair.second().id().toString()));}t.put(ROADS,list);
  var nodes=new LinkedHashSet<Long>();for(long p:t.getLongArray("TollConnectorNodes"))nodes.add(p);nodes.add(pair.pos().asLong());t.putLongArray("TollConnectorNodes",new ArrayList<>(nodes));
  var topology=new ListTag();for(Tag v:before.getList("SignTopology",Tag.TAG_COMPOUND)){var e=(CompoundTag)v;topology.add(e.copy());if(e.getUUID("Road").equals(old.id())){var next=e.copy();next.putUUID("Road",pair.second().id());topology.add(next);}}t.put("SignTopology",topology);return t;
 }
 static boolean hasTolls(RoadData data,CompoundTag t){return ids(t).stream().map(data.index.roads::get).filter(Objects::nonNull).anyMatch(b->b.record.toll()!=null);}
 static List<BlockPos> obsoleteNodes(RoadData data,CompoundTag t,Collection<RoadIndex.Built> next,Set<UUID> removed){
  var retired=new ArrayList<BlockPos>();var retained=new ArrayList<Long>();
  for(long value:t.getLongArray("TollConnectorNodes")){var pos=BlockPos.of(value);
   if(next.stream().anyMatch(b->b.record.a().equals(pos)||b.record.b().equals(pos))){retained.add(value);continue;}
   if(data.index.atNode(pos).stream().anyMatch(id->!removed.contains(id)))throw new IllegalArgumentException("中央收费站端点已连接其他道路，请先处理外接道路");
   retired.add(pos);
  }
  t.putLongArray("TollConnectorNodes",retained);return List.copyOf(retired);
 }
 static List<RoadRecord> preserve(RoadData data,CompoundTag t,InterchangePlanner.Plan plan){
  if(t.getBoolean("IntegratedToll")||Interchanges.read(t.getCompound("Options")).toll().enabled()||!hasTolls(data,t))return List.of();var middle=plan.legs().stream().filter(l->l.name().equals(DoubleTrumpetPlanner.CONNECTOR)).findFirst().orElseThrow(InterchangeConnectors::changed);
  if(!t.contains(SHAPE))throw changed();var original=RoadRecord.load(t.getCompound(SHAPE));
  if(!original.mesh().samples().equals(middle.mesh().samples())||!original.settings().equals(middle.mesh().settings()))throw changed();
  var out=new ArrayList<RoadRecord>();for(UUID id:ids(t)){var b=data.index.roads.get(id);if(b==null)throw changed();out.add(b.record);}return List.copyOf(out);
 }
 static RoadRecord restored(RoadData data,RoadRecord station){
  var spec=station.toll();if(spec==null||spec.path().isEmpty())throw new IllegalArgumentException("收费站没有可恢复的原路");
  Settings settings=spec.portA();List<Sample> path=spec.path();
  var saved=station.assembly()==null?null:data.interchanges.get(station.assembly());
  if(saved!=null&&saved.contains(SHAPE)){
   var original=RoadRecord.load(saved.getCompound(SHAPE));var mesh=original.mesh();
   double from=RoadQueries.horizontal(mesh,path.get(0).center()).sample().distance(),to=RoadQueries.horizontal(mesh,path.get(path.size()-1).center()).sample().distance();
   var restored=RoadSlicing.slice(mesh,from,to,original.settings()).mesh();
   if(restored.first().center().distance(path.get(0).center())>1e-5||restored.last().center().distance(path.get(path.size()-1).center())>1e-5)throw new IllegalArgumentException("原连接段接口已变化，未删除收费站");
   settings=restored.settings(); // Keep the exact original samples saved in the station receipt.
  }
  if(settings==null)throw new IllegalArgumentException("缺少收费站原路断面，未删除收费站");
  return new RoadRecord(station.id(),station.owner(),station.a(),station.b(),station.start(),station.end(),settings,false,station.clearance(),List.of(),0,41,station.assembly()!=null&&!station.assembly().equals(station.id())?station.assembly():null,path,station.furniturePhase());
 }
 private static IllegalArgumentException changed(){return new IllegalArgumentException("中央连接段已有收费站；请先移除收费站，再改变中央段路径、宽度或高度。原收费站和立交均未修改");}
 private InterchangeConnectors(){}
}
