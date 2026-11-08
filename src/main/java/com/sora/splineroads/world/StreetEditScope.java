package com.sora.splineroads.world;

import com.sora.splineroads.config.StreetServerConfig;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.StreetPlan.*;
import java.util.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Task-local guard at the actual shared road transaction boundary. */
final class StreetEditScope implements AutoCloseable {
  private static final ThreadLocal<StreetEditScope> ACTIVE=new ThreadLocal<>();
  final ServerLevel level;final Input input;final StreetTerrain terrain;final Map<Long,Long> fingerprints;final Set<UUID> centers;
  Set<UUID> ids;Set<Long> columns=new LongOpenHashSet();StreetJournal.Snapshot before;CompoundTag objects;
  int remainingCells=Integer.MAX_VALUE;
  StreetEditScope(ServerLevel level,Input input,StreetTerrain terrain,Map<Long,Long> fingerprints,Set<UUID> centers){
    if(ACTIVE.get()!=null||!level.getServer().isSameThread())throw new IllegalStateException("路网施工上下文无效");this.level=level;this.input=input;this.terrain=terrain;this.fingerprints=fingerprints;this.centers=Set.copyOf(centers);ACTIVE.set(this);
  }
  static boolean active(){return ACTIVE.get()!=null;}
  static void prepare(ServerLevel level,List<RoadIndex.Built> built,Set<UUID> removed,Set<Long> touched,Map<Long,BlockState> writes,Map<Long,BlockState> sidewalks,Set<Long> aprons,Set<Long> restores,boolean preview){
    var scope=ACTIVE.get();if(scope==null)return;if(scope.level!=level)throw new IllegalStateException("路网施工维度已改变");scope.prepare(built,removed,touched,writes,sidewalks,aprons,restores,preview);
  }
  private void prepare(List<RoadIndex.Built> built,Set<UUID> removed,Set<Long> touched,Map<Long,BlockState> writes,Map<Long,BlockState> sidewalks,Set<Long> aprons,Set<Long> restores,boolean preview){
    try(var timing=RoadTimings.start("street_guard",built.size(),touched.size())){
    RoadPlanningBudget.phase("路网完整占地与地形复核");var data=RoadData.get(level);
    var journalKeys=new LongOpenHashSet(writes.keySet());journalKeys.addAll(restores);for(long key:restores)checkPosition(key);
    var affected=new LongOpenHashSet(journalKeys);
    for(var road:built){affected.addAll(road.cells.keySet());affected.addAll(road.clearanceCells);}
    for(var id:removed){var old=data.index.roads.get(id);if(old!=null){affected.addAll(old.cells.keySet());affected.addAll(old.clearanceCells);}}
    for(long key:touched){var prior=data.streetLedger(key);if(!Objects.equals(prior.sidewalk(),sidewalks.get(key))||prior.apron()!=aprons.contains(key)){affected.add(key);journalKeys.add(key);checkPosition(key);}}
    if(journalKeys.size()>StreetServerConfig.MAX_UNIT_CELLS.get())throw new IllegalArgumentException("单批路口施工范围超过服务器上限，请降低密度或断面宽度");
    if(journalKeys.size()>remainingCells)throw new IllegalArgumentException("本任务恢复日志达到服务器上限，已停止后续施工；可撤销或分开规划");
    timing.stage("journal_candidates");
    // Check new physical cells, including lamps, sidewalks, approach aprons and
    // clearance. Unchanged remote portions of an existing native street are not new land.
    for(var road:built){var old=data.index.roads.get(road.record.id());
      for(long key:road.cells.keySet())if(old==null||!old.cells.containsKey(key))checkPosition(key);
      for(long key:road.clearanceCells)if(old==null||!old.clearanceCells.contains(key))checkPosition(key);
    }
    for(long key:writes.keySet())checkPosition(key);
    var examined=new LongOpenHashSet();for(long key:affected){var p=BlockPos.of(key);examined.add(columnKey(p.getX(),p.getZ()));}
    for(long key:examined)if(!columns.contains(key)&&input.allowed((int)(key>>32)+.5,(int)key+.5))columns.add(key);
    timing.stage("complete_footprint");RoadPlanningBudget.phase("路网过期地形复核");
    int checked=0;for(long key:columns){if((checked++&511)==0)RoadPlanningBudget.check();int x=(int)(key>>32),z=(int)key;var chunk=terrain.chunks().get(Cell.at(x,z));if(chunk==null)throw new IllegalArgumentException("施工地形尚未读取");
      long expected=fingerprints.getOrDefault(key,chunk.fingerprint(StreetTerrain.column(x,z)));if(StreetTerrainCapture.column(level,x,z).fingerprint()!=expected)throw new IllegalArgumentException("规划范围的地形或建筑已变化，请重新读取预览："+x+", "+z);
    }
    timing.stage("fingerprints");RoadPlanningBudget.phase("路网写入保护");
    for(var write:writes.entrySet()){
      var p=BlockPos.of(write.getKey());var state=level.getBlockState(p);
      if(!state.isAir()&&!RoadBlocks.isCollider(state)&&!(level.getBlockEntity(p) instanceof NodeEntity)&&!Objects.equals(data.streetLedger(write.getKey()).sidewalk(),state)&&!StreetTerrainCapture.natural(state))throw new IllegalArgumentException("施工遇到保留建筑或设施："+p.toShortString());
      double surface=terrain.at(p.getX()+.5,p.getZ()+.5).y();if(p.getY()<surface-input.options().earthwork()-1.01)throw new IllegalArgumentException("施工开挖超过已确认预算："+p.toShortString());
    }
    timing.stage("write_protection");RoadPlanningBudget.phase("路网恢复日志快照");
    if(!preview){ids=new HashSet<>(removed);for(var r:built)ids.add(r.record.id());before=new StreetJournal.Snapshot(level,journalKeys);objects=data.streetObjects(ids,centers);}
    timing.stage("snapshot");}
  }
  private void checkPosition(long key){var p=BlockPos.of(key);if(!columns.add(columnKey(p.getX(),p.getZ())))return;if(!input.allowed(p.getX()+.5,p.getZ()+.5))throw new IllegalArgumentException("完整施工占地进入选区外或保护区："+p.toShortString());}
  StreetJournal finish(){
    if(before==null)throw new IllegalStateException("路网施工没有事务记录");var data=RoadData.get(level);var after=new StreetJournal.Snapshot(level,before.blocks.keySet());var changed=new LongOpenHashSet();
    for(long key:before.blocks.keySet())if(!before.cellEquals(after,key)||!Objects.equals(before.ledger.get(key),after.ledger.get(key)))changed.add(key);before.keep(changed);after.keep(changed);
    var changedColumns=new LongOpenHashSet();for(long key:changed){var p=BlockPos.of(key);changedColumns.add(columnKey(p.getX(),p.getZ()));}for(long key:changedColumns)fingerprints.put(key,StreetTerrainCapture.column(level,(int)(key>>32),(int)key).fingerprint());
    return new StreetJournal(ids,centers,before,after,objects,data.streetObjects(ids,centers));
  }
  static long columnKey(int x,int z){return (long)x<<32^(z&0xffffffffL);}
  public void close(){if(ACTIVE.get()==this)ACTIVE.remove();}
}
