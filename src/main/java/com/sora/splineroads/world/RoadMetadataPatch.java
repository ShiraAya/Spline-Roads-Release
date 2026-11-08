package com.sora.splineroads.world;

import java.util.*;
import java.util.function.UnaryOperator;

/** An optimistic, entry-scoped metadata edit. Unrelated live entries are never replaced. */
record RoadMetadataPatch<T>(Map<UUID,T> expected,Map<UUID,T> updates,Set<UUID> removed) {
  static <T> RoadMetadataPatch<T> between(Map<UUID,T> before,Map<UUID,T> after,UnaryOperator<T> snapshot) {
    var expected=new LinkedHashMap<UUID,T>();var updates=new LinkedHashMap<UUID,T>();var removed=new LinkedHashSet<UUID>();
    var keys=new LinkedHashSet<>(before.keySet());keys.addAll(after.keySet());
    for(UUID id:keys){
      if(before.containsKey(id)==after.containsKey(id)&&Objects.equals(before.get(id),after.get(id)))continue;
      if(before.containsKey(id))expected.put(id,snapshot.apply(before.get(id)));
      if(after.containsKey(id))updates.put(id,snapshot.apply(after.get(id)));else removed.add(id);
    }
    return new RoadMetadataPatch<>(Map.copyOf(expected),Map.copyOf(updates),Set.copyOf(removed));
  }
  void validate(Map<UUID,T> live) {
    var keys=new HashSet<>(updates.keySet());keys.addAll(removed);
    for(UUID id:keys)if(live.containsKey(id)!=expected.containsKey(id)||!Objects.equals(live.get(id),expected.get(id)))
      throw new IllegalArgumentException("相关道路元数据已改变，请重新预览");
  }
  void apply(Map<UUID,T> live){removed.forEach(live::remove);live.putAll(updates);}
}
