package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.StreetPlan.Cell;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;

/** Per connected unit history, including removed objects, exact block entities and terrain ledgers. */
final class StreetJournal {
  static final class Snapshot {
    final Map<Long,BlockState> blocks=new LinkedHashMap<>();final Map<Long,CompoundTag> entities=new HashMap<>();final Map<Long,RoadData.StreetLedger> ledger=new HashMap<>();
    Snapshot(){}
    Snapshot(ServerLevel level,Collection<Long> keys){var data=RoadData.get(level);for(long key:keys){var p=BlockPos.of(key);var state=level.getBlockState(p);blocks.put(key,state);var be=state.hasBlockEntity()?level.getBlockEntity(p):null;if(be!=null)entities.put(key,be.saveWithFullMetadata());ledger.put(key,data.streetLedger(key));}}
    void keep(Set<Long> keys){blocks.keySet().retainAll(keys);entities.keySet().retainAll(keys);ledger.keySet().retainAll(keys);}
    boolean cellEquals(Snapshot other,long key){return Objects.equals(blocks.get(key),other.blocks.get(key))&&Objects.equals(entities.get(key),other.entities.get(key));}
    CompoundTag save(){
      var t=new CompoundTag();t.put("Blocks",RoadBlockStorage.write(new Long2ObjectOpenHashMap<>(blocks)));var bes=new ListTag();entities.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var v=new CompoundTag();v.putLong("Pos",e.getKey());v.put("Data",e.getValue().copy());bes.add(v);});t.put("Entities",bes);
      for(String kind:List.of("Fill","Original","Sidewalk")){var states=new Long2ObjectOpenHashMap<BlockState>();for(var e:ledger.entrySet()){var s=switch(kind){case "Fill"->e.getValue().fill();case "Original"->e.getValue().original();default->e.getValue().sidewalk();};if(s!=null)states.put(e.getKey().longValue(),s);}t.put(kind,RoadBlockStorage.write(states));}
      t.putLongArray("Aprons",ledger.entrySet().stream().filter(e->e.getValue().apron()).mapToLong(Map.Entry::getKey).toArray());return t;
    }
    static Snapshot load(CompoundTag t){
      var s=new Snapshot();var blocks=new Long2ObjectOpenHashMap<BlockState>();RoadBlockStorage.read(t.getList("Blocks",Tag.TAG_COMPOUND),blocks);s.blocks.putAll(blocks);
      for(Tag v:t.getList("Entities",Tag.TAG_COMPOUND)){var e=(CompoundTag)v;s.entities.put(e.getLong("Pos"),e.getCompound("Data").copy());}
      var fill=new Long2ObjectOpenHashMap<BlockState>();var original=new Long2ObjectOpenHashMap<BlockState>();var sidewalk=new Long2ObjectOpenHashMap<BlockState>();RoadBlockStorage.read(t.getList("Fill",Tag.TAG_COMPOUND),fill);RoadBlockStorage.read(t.getList("Original",Tag.TAG_COMPOUND),original);RoadBlockStorage.read(t.getList("Sidewalk",Tag.TAG_COMPOUND),sidewalk);var aprons=new HashSet<Long>();for(long p:t.getLongArray("Aprons"))aprons.add(p);
      for(long key:s.blocks.keySet())s.ledger.put(key,new RoadData.StreetLedger(fill.get(key),original.get(key),sidewalk.get(key),aprons.contains(key)));return s;
    }
  }
  final Set<UUID> roads,centers;final Snapshot before,after;final CompoundTag oldObjects,newObjects;
  StreetJournal(Set<UUID> roads,Set<UUID> centers,Snapshot before,Snapshot after,CompoundTag oldObjects,CompoundTag newObjects){this.roads=Set.copyOf(roads);this.centers=Set.copyOf(centers);this.before=before;this.after=after;this.oldObjects=oldObjects.copy();this.newObjects=newObjects.copy();}
  CompoundTag save(){var t=new CompoundTag();t.put("RoadIds",ids(roads));t.put("CenterIds",ids(centers));t.put("Before",before.save());t.put("After",after.save());t.put("OldObjects",oldObjects);t.put("NewObjects",newObjects);return t;}
  static StreetJournal load(CompoundTag t){return new StreetJournal(ids(t.getList("RoadIds",Tag.TAG_STRING)),ids(t.getList("CenterIds",Tag.TAG_STRING)),Snapshot.load(t.getCompound("Before")),Snapshot.load(t.getCompound("After")),t.getCompound("OldObjects"),t.getCompound("NewObjects"));}
  static ListTag ids(Set<UUID> values){var t=new ListTag();values.stream().sorted().forEach(id->t.add(StringTag.valueOf(id.toString())));return t;}
  static Set<UUID> ids(ListTag values){var result=new HashSet<UUID>();for(int i=0;i<values.size();i++)result.add(UUID.fromString(values.getString(i)));return result;}
  record Undo(boolean restored,int conflicts){}
  Undo undo(ServerLevel level,ServerPlayer player){
    var data=RoadData.get(level);if(!data.streetObjects(roads,centers).equals(newObjects))return new Undo(false,1);
    var nodes=new HashSet<BlockPos>();for(Tag value:newObjects.getList("Centers",Tag.TAG_COMPOUND))nodes.add(BlockPos.of(((CompoundTag)value).getLong("CenterPos")));
    for(long key:before.blocks.keySet())if(after.blocks.get(key).is(SplineRoads.NODE.get())&&!before.blocks.get(key).is(SplineRoads.NODE.get()))nodes.add(BlockPos.of(key));
    if(data.index.roads.values().stream().anyMatch(r->!roads.contains(r.record.id())&&(nodes.contains(r.record.a())||nodes.contains(r.record.b()))))return new Undo(false,1);
    if(data.index.roads.values().stream().anyMatch(r->!roads.contains(r.record.id())&&before.blocks.keySet().stream().anyMatch(k->!before.cellEquals(after,k)&&r.cells.containsKey(k))))return new Undo(false,1);
    try(var lease=RoadWorkChunks.open(level)){
      var chunks=new HashSet<Long>();for(long key:before.blocks.keySet())chunks.add(new net.minecraft.world.level.ChunkPos(BlockPos.of(key)).toLong());lease.load(chunks);
      var current=new Snapshot(level,before.blocks.keySet());var restores=new LinkedHashMap<Long,BlockState>();var entities=new HashMap<Long,CompoundTag>();int conflicts=0;
      for(long key:before.blocks.keySet()){
        var p=BlockPos.of(key);if(player!=null&&!level.mayInteract(player,p))return new Undo(false,1);
        if(after.cellEquals(current,key)){restores.put(key,before.blocks.get(key));if(before.entities.containsKey(key))entities.put(key,before.entities.get(key));}
        else {conflicts++;var state=current.blocks.get(key);if(RoadBlocks.isCollider(state)||state.is(SplineRoads.NODE.get()))return new Undo(false,conflicts);}
      }
      for(var e:restores.entrySet())if(player!=null)RoadEditProtection.check(level,player,BlockPos.of(e.getKey()),current.blocks.get(e.getKey()),e.getValue());
      // Restore as one bounded unit. If a write fails, restore the exact current
      // world/metadata rather than publishing half an undone junction.
      try{
        RoadData.restoreStreetBlocks(level,restores,entities);
        for(long key:before.blocks.keySet())data.streetLedger(key,before.ledger.get(key));
        data.restoreStreetObjects(level,roads,centers,oldObjects);
      }catch(RuntimeException failure){
        try{RoadData.restoreStreetBlocks(level,current.blocks,current.entities);for(long key:current.blocks.keySet())data.streetLedger(key,current.ledger.get(key));data.restoreStreetObjects(level,roads,centers,newObjects);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}throw failure;
      }
      return new Undo(true,conflicts);
    }
  }
}
