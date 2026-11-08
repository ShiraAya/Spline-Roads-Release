package com.sora.splineroads.world;

import com.sora.splineroads.core.ImmutableOrderedMap;
import com.sora.splineroads.core.RoadGeometry.Mesh;
import java.util.*;

/** Immutable authored records and meshes only: no Level, mutable Built, or live index.
 * The index publishes a new root for each changed road; old workers retain their own roots. */
final class RoadPlanningSnapshot {
  private record Source(RoadRecord record,Mesh mesh){}
  private final ImmutableOrderedMap<UUID,Source> sources;
  private final ImmutableOrderedMap<UUID,RoadRecord> tolls;
  private final Records records;
  private final Map<RoadRecord,Mesh> seeds;
  private RoadPlanningSnapshot(ImmutableOrderedMap<UUID,Source> sources,ImmutableOrderedMap<UUID,RoadRecord> tolls){
    this.sources=sources;this.tolls=tolls;this.records=new Records();
    this.seeds=Collections.unmodifiableMap(new AbstractMap<>(){
      @Override public Mesh get(Object key){if(!(key instanceof RoadRecord r))return null;var source=sources.get(r.id());return source!=null&&source.record()==r?source.mesh():null;}
      @Override public boolean containsKey(Object key){return get(key)!=null;}
      @Override public int size(){return sources.size();}
      @Override public Set<Entry<RoadRecord,Mesh>> entrySet(){return mappedEntries(s->new SimpleImmutableEntry<>(s.record(),s.mesh()));}
    });
  }
  static RoadPlanningSnapshot empty(){return new RoadPlanningSnapshot(ImmutableOrderedMap.empty(),ImmutableOrderedMap.empty());}
  RoadPlanningSnapshot with(RoadIndex.Built built){
    var id=built.record.id();var old=sources.get(id);if(old!=null&&old.record()==built.record&&old.mesh()==built.mesh)return this;
    return new RoadPlanningSnapshot(sources.with(id,new Source(built.record,built.mesh)),built.record.toll()==null?tolls.without(id):tolls.with(id,built.record));
  }
  RoadPlanningSnapshot without(UUID id){var next=sources.without(id);return next==sources?this:new RoadPlanningSnapshot(next,tolls.without(id));}
  RoadPlanningSnapshot changed(Set<UUID> removed,Collection<RoadIndex.Built> built){var next=this;for(UUID id:removed)next=next.without(id);for(var road:built)next=next.with(road);return next;}
  Map<UUID,RoadRecord> records(){return records;}
  Map<RoadRecord,Mesh> seeds(){return seeds;}
  int size(){return sources.size();}
  /** View preserves the authoritative insertion order without a materialized values list. */
  final class Records extends AbstractMap<UUID,RoadRecord> {
    Collection<RoadRecord> tolls(){return tolls.values();}
    @Override public RoadRecord get(Object id){var source=sources.get(id);return source==null?null:source.record();}
    @Override public boolean containsKey(Object id){return sources.containsKey(id);}
    @Override public int size(){return sources.size();}
    @Override public Set<Entry<UUID,RoadRecord>> entrySet(){return mappedEntries(s->new SimpleImmutableEntry<>(s.record().id(),s.record()));}
  }
  private <K,V> Set<Map.Entry<K,V>> mappedEntries(java.util.function.Function<Source,Map.Entry<K,V>> map){
    return Collections.unmodifiableSet(new AbstractSet<>(){
      @Override public int size(){return sources.size();}
      @Override public Iterator<Map.Entry<K,V>> iterator(){var iterator=sources.values().iterator();return new Iterator<>(){
        public boolean hasNext(){return iterator.hasNext();}
        public Map.Entry<K,V> next(){return map.apply(iterator.next());}
      };}
    });
  }
}
