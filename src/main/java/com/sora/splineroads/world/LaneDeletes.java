package com.sora.splineroads.world;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
/** Review exact dependency IDs; stale confirmations cannot delete newly added dependents. */
public final class LaneDeletes {
  static Set<UUID> junctionDependencies(RoadData data,UUID group){var seeds=new HashSet<UUID>();data.index.roads.values().stream().filter(b->group.equals(b.record.assembly())).forEach(b->seeds.add(b.record.id()));var c=data.junctions.get(group);if(c!=null&&c.getBoolean("Auto")&&!c.getBoolean("GeneratedRing")){long pos=c.getLong("CenterPos");data.streets.values().stream().filter(r->r.a().asLong()==pos||r.b().asLong()==pos).forEach(r->seeds.add(r.id()));}return LaneTopology.dependents(data,seeds);}
  static void junctionPayload(RoadData data,UUID group,CompoundTag t){var list=new ListTag();junctionDependencies(data,group).stream().sorted().forEach(v->list.add(StringTag.valueOf(v.toString())));t.put("Dependencies",list);}
  public static void removeJunction(ServerLevel level,ServerPlayer player,CompoundTag t){var data=RoadData.get(level);UUID group=t.getUUID("Id");var deps=junctionDependencies(data,group);requireConfirmation(t,deps);UUID representative=data.index.roads.values().stream().filter(b->group.equals(b.record.assembly())).map(b->b.record.id()).findFirst().orElseThrow(()->new IllegalArgumentException("路口已不存在"));data.removeWithDependents(level,player,representative,deps);}
  public static void payload(RoadData data,UUID id,CompoundTag t){var list=new ListTag();LaneTopology.dependents(data,Set.of(id)).stream().sorted().forEach(v->list.add(StringTag.valueOf(v.toString())));t.put("Dependencies",list);}
  public static void requireConfirmation(CompoundTag t,Set<UUID> dependencies){if(dependencies.isEmpty())return;var confirmed=new HashSet<UUID>();for(Tag value:t.getList("ConfirmDependencies",Tag.TAG_STRING))try{confirmed.add(UUID.fromString(value.getAsString()));}catch(IllegalArgumentException e){throw new IllegalArgumentException("依赖确认无效");}if(!confirmed.equals(dependencies))throw new IllegalArgumentException("依赖匝道已变化，请重新打开删除界面，确认级联删除清单");}
  public static void remove(ServerLevel level,ServerPlayer player,CompoundTag t){var data=RoadData.get(level);UUID id=t.getUUID("Id");var dependent=LaneTopology.dependents(data,Set.of(id));requireConfirmation(t,dependent);if(dependent.isEmpty()){data.remove(level,player,id);return;}var target=data.index.roads.get(id);if(target==null)throw new IllegalArgumentException("道路已不存在");data.removeWithDependents(level,player,id,dependent);}
  private LaneDeletes(){}
}
