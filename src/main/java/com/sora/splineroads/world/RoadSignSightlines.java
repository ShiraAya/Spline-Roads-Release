package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.Part;
import com.sora.splineroads.core.StreetLampClearance;
import java.util.*;
import java.util.function.*;
import net.minecraft.world.level.ChunkPos;

/** One deduplicated maintenance wave. No geometry or world mutation starts until
 * its previous bounded step finishes; relevant intervening edits requeue it. */
final class RoadSignSightlines {
  @FunctionalInterface interface Source {Predicate<V> prepare(RoadIndex.Built road,List<RoadIndex.Built> context);}
  record Cost(long steps,long chunkQueries,long roads,long signSources,long partVisits,long signTests,long keyBuilds,long commits,long repairs){}
  private final RoadIndex index;
  private final Set<UUID> requested;
  private final BiFunction<RoadIndex.Built,List<Part>,Set<Long>> apply;
  private final LongConsumer repair;
  private final Source source;
  private final Set<Long> bases=new HashSet<>(),queried=new HashSet<>(),repairs=new LinkedHashSet<>();
  private final Set<UUID> candidates=new LinkedHashSet<>(),changes=new LinkedHashSet<>();
  private final List<RoadIndex.Built> roads=new ArrayList<>();
  private final List<Predicate<V>> prepared=new ArrayList<>();
  private List<Predicate<V>> signs=List.of();
  private final Iterator<UUID> requests;
  private Iterator<Long> chunks=Collections.emptyIterator(),changeChunks=Collections.emptyIterator();
  private Iterator<UUID> neighbors=Collections.emptyIterator(),candidateIterator;
  private int phase,dx=7,dz,baseX,baseZ,sourceAt,roadAt;
  private boolean invalidated;
  private long revision,steps,chunkQueries,signSources,partVisits,signTests,keyBuilds,commits,repairCount;
  private StreetLampClearance.Removal scan;

  RoadSignSightlines(RoadIndex index,Set<UUID> requested,
      BiFunction<RoadIndex.Built,List<Part>,Set<Long>> apply,LongConsumer repair){
    this(index,requested,apply,repair,JunctionSignRoads::lampClearance);
  }
  RoadSignSightlines(RoadIndex index,Set<UUID> requested,
      BiFunction<RoadIndex.Built,List<Part>,Set<Long>> apply,LongConsumer repair,Source source){
    this.index=index;this.requested=Collections.unmodifiableSet(new LinkedHashSet<>(requested));
    this.requests=this.requested.iterator();this.apply=apply;this.repair=repair;this.source=source;revision=index.contentRevision();
  }
  Set<UUID> requested(){return requested;}
  void changed(UUID id){changes.add(id);}
  void invalidate(){invalidated=true;}
  boolean invalidated(){return invalidated;}
  boolean done(){return repairs.isEmpty()&&(invalidated||phase==4);}
  Cost cost(){return new Cost(steps,chunkQueries,roads.size(),signSources,partVisits,signTests,keyBuilds,commits,repairCount);}
  int advance(long deadline,int maxSteps){
    int used=0;
    while(used<maxSteps&&!done()&&System.nanoTime()<deadline){
      step();used++;steps++;
    }
    return used;
  }
  private void step(){
    // A completed exact removal owns its cleanup even if a later input changes.
    if(!repairs.isEmpty()){long chunk=repairs.iterator().next();repair.accept(chunk);repairs.remove(chunk);repairCount++;return;}
    if(changeChunks.hasNext()){if(queried.contains(changeChunks.next()))invalidated=true;return;}
    if(!changes.isEmpty()){
      UUID id=changes.iterator().next();changes.remove(id);
      if(requested.contains(id)||candidates.contains(id)){invalidated=true;return;}
      var road=index.roads.get(id);if(road!=null)changeChunks=road.chunks.iterator();
      revision=index.contentRevision();return;
    }
    // clear()/rollback can change an index without its ordinary per-road callback.
    if(index.contentRevision()!=revision){invalidated=true;return;}
    if(phase==0){
      if(neighbors.hasNext()){candidates.add(neighbors.next());return;}
      if(dx<=6){
        long key=ChunkPos.asLong(baseX+dx,baseZ+dz);
        if(++dz>6){dz=-6;dx++;}
        if(queried.add(key)){
          chunkQueries++;
          // The live chunk set can change between ticks. Snapshot only this
          // queried bucket, then consume its IDs one at a time.
          neighbors=List.copyOf(index.inChunk(key)).iterator();
        }
        return;
      }
      if(chunks.hasNext()){
        long key=chunks.next();if(bases.add(key)){var at=new ChunkPos(key);baseX=at.x;baseZ=at.z;dx=dz=-6;}
        return;
      }
      if(requests.hasNext()){var road=index.roads.get(requests.next());if(road!=null)chunks=road.chunks.iterator();return;}
      candidateIterator=candidates.iterator();phase=1;return;
    }
    if(phase==1){
      if(candidateIterator.hasNext()){var road=index.roads.get(candidateIterator.next());if(road!=null)roads.add(road);return;}
      phase=2;return;
    }
    if(phase==2){
      if(sourceAt<roads.size()){
        var sign=source.prepare(roads.get(sourceAt++),Collections.unmodifiableList(roads));
        if(sign!=null){prepared.add(sign);signSources++;}return;
      }
      signs=List.copyOf(prepared);phase=signs.isEmpty()?4:3;return;
    }
    if(roadAt==roads.size()){phase=4;return;}
    var road=roads.get(roadAt);
    if(scan==null){scan=new StreetLampClearance.Removal(road.record.structures(),signs);return;}
    if(!scan.done()){scan.advance(1);return;}
    var removed=scan.removed();partVisits+=scan.partVisits();signTests+=scan.signTests();keyBuilds+=scan.keyBuilds();
    if(!removed.isEmpty()){
      var cleanup=apply.apply(road,removed);
      if(cleanup==null){invalidated=true;return;}
      repairs.addAll(cleanup);commits++;revision=index.contentRevision();
    }
    scan=null;roadAt++;
  }
}
