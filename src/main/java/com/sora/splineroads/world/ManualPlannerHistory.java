package com.sora.splineroads.world;

import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;

/** Session-local, conflict-checked undo/redo. Only cells reached by a real transaction are captured. */
public final class ManualPlannerHistory {
  private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
  private final ArrayDeque<Entry> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
  private static final int MAX_CELLS=1_000_000,MAX_RETAINED=2_000_000;
  private int limit=30;private long revision;private Set<Long> changedChunks=Set.of();
  public Set<Long> changedChunks(){return changedChunks;}
  private static Set<Long> chunks(Entry entry){var result=new HashSet<Long>();for(long cell:entry.before().blocks.keySet())result.add(new net.minecraft.world.level.ChunkPos(BlockPos.of(cell)).toLong());return Set.copyOf(result);}
  public long revision(){return revision;}
  public int limit(){return limit;}
  public void limit(int value){if(value<1||value>30)throw new IllegalArgumentException("撤销最大步应为1–30");limit=value;while(undo.size()>limit)undo.removeFirst();while(redo.size()>limit)redo.removeFirst();}
  public int undoCount(){return undo.size();}public int redoCount(){return redo.size();}
  public Scope begin(ServerPlayer player){return new Scope(player);}
  static void capture(ServerLevel level,Collection<Long> cells){var scope=ACTIVE.get();if(scope!=null){if(scope.level!=level)throw new IllegalArgumentException("地图编辑维度已改变");scope.capture(cells);}}
  private static Map<UUID,CompoundTag> tags(Map<UUID,CompoundTag> source){var result=new LinkedHashMap<UUID,CompoundTag>();source.forEach((id,t)->result.put(id,t.copy()));return result;}
  private record State(Map<UUID,RoadRecord> roads,Map<UUID,RoadRecord> streets,Map<UUID,CompoundTag> junctions,Map<UUID,CompoundTag> interchanges,Map<SmartSigns.Key,SmartSigns.State> signs){
    static State of(RoadData data){var roads=new LinkedHashMap<UUID,RoadRecord>();data.index.roads.forEach((id,b)->roads.put(id,b.record));var signs=new LinkedHashMap<SmartSigns.Key,SmartSigns.State>();data.smartSigns.snapshot().states().forEach(s->signs.put(s.key(),s));return new State(roads,new LinkedHashMap<>(data.streets),tags(data.junctions),tags(data.interchanges),signs);}
  }
  private record Changes<K,V>(Map<K,V> before,Map<K,V> after,Set<K> keys){
    static <K,V> Changes<K,V> between(Map<K,V> before,Map<K,V> after){var keys=new HashSet<>(before.keySet());keys.addAll(after.keySet());keys.removeIf(k->Objects.equals(before.get(k),after.get(k)));var a=new HashMap<K,V>();var b=new HashMap<K,V>();for(K k:keys){if(before.containsKey(k))a.put(k,before.get(k));if(after.containsKey(k))b.put(k,after.get(k));}return new Changes<>(Map.copyOf(a),Map.copyOf(b),Set.copyOf(keys));}
    void validate(Map<K,V> current,boolean reverse){var expected=reverse?after:before;for(K k:keys)if(!Objects.equals(current.get(k),expected.get(k)))throw new IllegalArgumentException("相关道路、节点或配置已被修改，不能覆盖后续改动");}
    void apply(Map<K,V> target,boolean reverse){apply(target,reverse,java.util.function.UnaryOperator.identity());}
    void apply(Map<K,V> target,boolean reverse,java.util.function.UnaryOperator<V> copy){var next=reverse?before:after;for(K k:keys){if(next.containsKey(k))target.put(k,copy.apply(next.get(k)));else target.remove(k);}}
  }
  private record Entry(StreetJournal.Snapshot before,StreetJournal.Snapshot after,Changes<UUID,RoadRecord> roads,Changes<UUID,RoadRecord> streets,Changes<UUID,CompoundTag> junctions,Changes<UUID,CompoundTag> interchanges,Changes<SmartSigns.Key,SmartSigns.State> signs){
    int size(){return before.blocks.size();}
    boolean empty(){return before.blocks.isEmpty()&&roads.keys().isEmpty()&&streets.keys().isEmpty()&&junctions.keys().isEmpty()&&interchanges.keys().isEmpty()&&signs.keys().isEmpty();}
  }
  public final class Scope implements AutoCloseable {
    private final ServerLevel level;private final State state;private final StreetJournal.Snapshot before=new StreetJournal.Snapshot();private boolean finished;
    private Scope(ServerPlayer player){if(ACTIVE.get()!=null)throw new IllegalStateException("地图编辑不可嵌套");level=player.serverLevel();state=State.of(RoadData.get(level));ACTIVE.set(this);}
    private void capture(Collection<Long> cells){var missing=new HashSet<>(cells);missing.removeAll(before.blocks.keySet());if(before.blocks.size()+missing.size()>MAX_CELLS)throw new IllegalArgumentException("本次地图编辑超过撤销记录上限，请分段操作");var values=new StreetJournal.Snapshot(level,missing);before.blocks.putAll(values.blocks);before.entities.putAll(values.entities);before.ledger.putAll(values.ledger);}
    private Entry changes(){var after=new StreetJournal.Snapshot(level,before.blocks.keySet());var changed=new HashSet<Long>();for(long key:before.blocks.keySet())if(!before.cellEquals(after,key)||!Objects.equals(before.ledger.get(key),after.ledger.get(key)))changed.add(key);before.keep(changed);after.keep(changed);var current=State.of(RoadData.get(level));
      return new Entry(before,after,Changes.between(state.roads(),current.roads()),Changes.between(state.streets(),current.streets()),Changes.between(state.junctions(),current.junctions()),Changes.between(state.interchanges(),current.interchanges()),Changes.between(state.signs(),current.signs()));
    }
    public void finish(){if(finished)return;var entry=changes();finished=true;
      if(!entry.empty()){revision++;changedChunks=chunks(entry);undo.addLast(entry);redo.clear();while(undo.size()>limit||undo.stream().mapToInt(Entry::size).sum()>MAX_RETAINED)undo.removeFirst();}
    }
    @Override public void close(){try{if(!finished){var entry=changes();if(!entry.empty()){var data=RoadData.get(level);var callback=data.index.changed;data.index.changed=id->{};try{RoadData.restorePlannerBlocks(level,before.blocks,before.entities);before.ledger.forEach(data::streetLedger);restore(data,entry,true);}finally{data.index.changed=callback;}entry.roads().keys().forEach(callback);data.setDirty();for(UUID id:entry.roads().keys()){var b=data.index.roads.get(id);if(b==null)RoadNetwork.broadcastDelete(level,id);else RoadNetwork.broadcastRoad(level,b.record);}}}}finally{if(ACTIVE.get()==this)ACTIVE.remove();}}
  }
  public String apply(ServerPlayer player,boolean reverse){
    var from=reverse?undo:redo;var to=reverse?redo:undo;if(from.isEmpty())throw new IllegalArgumentException(reverse?"没有可撤销的地图操作":"没有可重做的地图操作");var entry=from.peekLast();var level=player.serverLevel();var data=RoadData.get(level);var current=State.of(data);
    entry.roads().validate(current.roads(),reverse);entry.streets().validate(current.streets(),reverse);entry.junctions().validate(current.junctions(),reverse);entry.interchanges().validate(current.interchanges(),reverse);entry.signs().validate(current.signs(),reverse);
    var expected=reverse?entry.after():entry.before();var target=reverse?entry.before():entry.after();
    // A later road using a restored marker or cell also makes this history entry stale.
    var changedNodes=new HashSet<BlockPos>();for(long key:target.blocks.keySet())if(target.blocks.get(key).is(com.sora.splineroads.SplineRoads.NODE.get())||expected.blocks.get(key).is(com.sora.splineroads.SplineRoads.NODE.get()))changedNodes.add(BlockPos.of(key));
    for(var road:data.index.roads.values())if(!entry.roads().keys().contains(road.record.id())){
      if(changedNodes.contains(road.record.a())||changedNodes.contains(road.record.b()))throw new IllegalArgumentException("后续道路使用了这个端点，请先撤销后续连接");
      for(long key:target.blocks.keySet())if(road.cells.containsKey(key)&&!Objects.equals(target.blocks.get(key),expected.blocks.get(key)))throw new IllegalArgumentException("后续道路使用了原施工范围");
    }
    try(var chunks=RoadWorkChunks.open(level)){
      var needed=new HashSet<Long>();for(long key:target.blocks.keySet())needed.add(new net.minecraft.world.level.ChunkPos(BlockPos.of(key)).toLong());chunks.load(needed);
      var world=new StreetJournal.Snapshot(level,target.blocks.keySet());for(long key:target.blocks.keySet()){
        var p=BlockPos.of(key);if(!expected.cellEquals(world,key)||!Objects.equals(expected.ledger.get(key),world.ledger.get(key)))throw new IllegalArgumentException("原位置已被其他操作改变，撤销/重做未写入世界："+p.toShortString());
        if(!level.mayInteract(player,p))throw new IllegalArgumentException("没有修改该位置的权限");RoadEditProtection.check(level,player,p,world.blocks.get(key),target.blocks.get(key));
      }
      var records=List.copyOf((reverse?entry.roads().before():entry.roads().after()).values());
      var point=changedNodes.stream().findFirst().orElse(records.isEmpty()?BlockPos.ZERO:records.get(0).a());
      RoadNetwork.PreparedBatch publication;
      var callback=data.index.changed;data.index.changed=id->{};
      try{RoadData.restorePlannerBlocks(level,target.blocks,target.entities);target.ledger.forEach(data::streetLedger);restore(data,entry,reverse);publication=entry.roads().keys().isEmpty()?null:RoadNetwork.prepareBatch(level,records,entry.roads().keys(),point);}
      catch(RuntimeException failure){try{RoadData.restorePlannerBlocks(level,world.blocks,world.entities);world.ledger.forEach(data::streetLedger);restore(data,entry,!reverse);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}throw failure;}
      finally{data.index.changed=callback;}
      entry.roads().keys().forEach(callback);data.setDirty();
      if(publication!=null)RoadNetwork.broadcastBatch(level,records,entry.roads().keys(),publication);
    }
    from.removeLast();to.addLast(entry);revision++;changedChunks=chunks(entry);return reverse?"已撤销地图操作":"已重做地图操作";
  }
  private static void restore(RoadData data,Entry entry,boolean reverse){
    for(UUID id:entry.roads().keys())data.index.remove(id);for(var r:(reverse?entry.roads().before():entry.roads().after()).values())data.index.put(RoadIndex.Built.loading(r));
    // Descriptors are edited in place by later junction/assembly transactions.
    // Never let live state share those mutable tags with retained history.
    entry.streets().apply(data.streets,reverse);entry.junctions().apply(data.junctions,reverse,CompoundTag::copy);entry.interchanges().apply(data.interchanges,reverse,CompoundTag::copy);
    RailbedRoads.sync(data,entry.roads().keys().stream().map(data.index.roads::get).filter(Objects::nonNull).toList(),entry.roads().keys());
    var signs=data.smartSigns.snapshot();var states=new LinkedHashMap<SmartSigns.Key,SmartSigns.State>();signs.states().forEach(s->states.put(s.key(),s));entry.signs().apply(states,reverse);data.smartSigns.restore(new SmartSignRoads.Snapshot(List.copyOf(states.values()),signs.versions(),signs.sightDirty()));
  }
}
