package com.sora.splineroads.core;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** A changed road batch may split or merge UUIDs. A section is publishable only
 * after every member which contributes there has its complete replacement.
 * Keys are spatial tiles, not build-queue state or GPU readiness. */
public final class RoadHandoffCohorts<K> {
  private static final class Group<K> {
    final Set<UUID> members=new LinkedHashSet<>();
    final Set<K> keys=new LinkedHashSet<>();
    boolean installed;
  }
  private final Map<UUID,Group<K>> groups=new HashMap<>();

  /** Replace overlapping in-flight batches with one coherent new batch. */
  public Set<UUID> begin(Collection<UUID> members,Collection<K> previousKeys) {
    var next=new Group<K>();next.members.addAll(members);next.keys.addAll(previousKeys);
    var previous=Collections.newSetFromMap(new IdentityHashMap<Group<K>,Boolean>());
    for(UUID member:members){var group=groups.get(member);if(group!=null)previous.add(group);}
    for(var group:previous){next.members.addAll(group.members);next.keys.addAll(group.keys);}
    for(UUID member:next.members)groups.put(member,next);
    return Set.copyOf(next.members);
  }
  public void include(UUID member,K key) {var group=groups.get(member);if(group!=null)group.keys.add(key);}
  public void installed(Collection<UUID> members) {
    var changed=Collections.newSetFromMap(new IdentityHashMap<Group<K>,Boolean>());
    for(UUID member:members){var group=groups.get(member);if(group!=null)changed.add(group);}
    for(var group:changed){group.installed=true;retire(group);}
  }
  /** Calling this after a whole tile was activated releases all its vertical
   * sections together. Missing members are interpreted by the supplied predicate. */
  public boolean ready(K key,BiPredicate<UUID,K> memberReady) {
    var touching=new ArrayList<Group<K>>();
    for(var group:new HashSet<>(groups.values()))if(group.keys.contains(key)){
      if(!group.installed)return false;
      for(UUID member:group.members)if(!memberReady.test(member,key))return false;
      touching.add(group);
    }
    for(var group:touching){group.keys.remove(key);retire(group);}
    return true;
  }
  private Set<Group<K>> distinctGroups(){var unique=Collections.newSetFromMap(new IdentityHashMap<Group<K>,Boolean>());unique.addAll(groups.values());return unique;}
  public Set<K> keys(){Set<K> keys=new LinkedHashSet<>();for(var group:distinctGroups())keys.addAll(group.keys);return Set.copyOf(keys);}
  public Set<UUID> members(){return Set.copyOf(groups.keySet());}
  public Set<UUID> members(K key){Set<UUID> ids=new HashSet<>();for(var group:distinctGroups())if(group.keys.contains(key))ids.addAll(group.members);return Set.copyOf(ids);}
  /** Union each batch once and freeze each touched tile once. Prior snapshots
   * remain immutable while overlapping retirements accumulate their coverage. */
  public <S> void rememberCoverage(Map<K,Set<UUID>> coverage,Map<UUID,? extends Collection<S>> transitions,Function<S,K> keyOf){
    Map<K,Set<UUID>> merged=new HashMap<>();
    Function<K,Set<UUID>> seed=key->new HashSet<>(coverage.getOrDefault(key,Set.of()));
    for(var group:distinctGroups())for(K key:group.keys)merged.computeIfAbsent(key,seed).addAll(group.members);
    transitions.forEach((road,sections)->{for(S section:sections)merged.computeIfAbsent(keyOf.apply(section),seed).add(road);});
    merged.forEach((key,ids)->coverage.put(key,Set.copyOf(ids)));
  }
  public boolean contains(UUID member){return groups.containsKey(member);}
  public int size(){return new HashSet<>(groups.values()).size();}
  public void clear(){groups.clear();}
  private void retire(Group<K> group){if(group.installed&&group.keys.isEmpty())for(UUID member:group.members)groups.remove(member,group);}
}
