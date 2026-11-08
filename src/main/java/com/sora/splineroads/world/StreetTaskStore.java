package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.StreetPlan.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Plans and undo obligations belong to their dimension, not to a transient screen. */
public final class StreetTaskStore extends SavedData {
  enum State { READING,READY,BUILDING,PAUSED,CANCELLED,COMPLETE,UNDOING,UNDONE,CONFLICT,FAILED }
  static final class Task {
    final UUID id,owner;Input input;StreetTerrain terrain=new StreetTerrain(Map.of());
    State state=State.READING;String message="正在读取地形";int progress,readChunks,undoCursor=-1,conflicts;
    long peakUnitMicros,totalUnitMicros;final long created;long updated;
    long verifiedRevision=Long.MIN_VALUE;boolean undoIntent;
    List<RoadRecord> roads=List.of();List<CompoundTag> centers=List.of();final List<StreetJournal> journals=new ArrayList<>();
    final Map<Long,Long> fingerprints=new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();final Map<UUID,CompoundTag> dependencies=new HashMap<>();
    Task(UUID id,UUID owner,Input input){this(id,owner,input,System.currentTimeMillis());}
    Task(UUID id,UUID owner,Input input,long created){this.id=id;this.owner=owner;this.input=input;this.created=created;updated=created;}
    int cells(){return journals.stream().mapToInt(j->j.before.blocks.size()).sum();}
    CompoundTag save(){
      var t=new CompoundTag();t.putUUID("Id",id);t.putUUID("Owner",owner);t.put("Input",StreetPlanCodec.write(input));t.put("Terrain",StreetPlanCodec.write(terrain));t.putString("State",state.name());t.putString("Message",message);t.putInt("Progress",progress);t.putInt("ReadChunks",readChunks);t.putInt("UndoCursor",undoCursor);t.putBoolean("UndoIntent",undoIntent);t.putInt("Conflicts",conflicts);t.putLong("Created",created);t.putLong("Updated",updated);t.putLong("PeakUnitMicros",peakUnitMicros);t.putLong("TotalUnitMicros",totalUnitMicros);
      var r=new ListTag();roads.forEach(v->r.add(v.save()));t.put("Roads",r);var c=new ListTag();centers.forEach(v->c.add(v.copy()));t.put("Centers",c);var j=new ListTag();journals.forEach(v->j.add(v.save()));t.put("Journals",j);
      var keys=new long[fingerprints.size()*2];int i=0;for(var e:fingerprints.entrySet()){keys[i++]=e.getKey();keys[i++]=e.getValue();}t.putLongArray("Fingerprints",keys);
      var deps=new ListTag();dependencies.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var v=new CompoundTag();v.putUUID("Id",e.getKey());v.put("Road",e.getValue().copy());deps.add(v);});t.put("Dependencies",deps);return t;
    }
    static Task load(CompoundTag t){
      var task=new Task(t.getUUID("Id"),t.getUUID("Owner"),StreetPlanCodec.read(t.getCompound("Input")),t.getLong("Created"));task.terrain=StreetPlanCodec.terrain(t.getCompound("Terrain"));task.state=State.valueOf(t.getString("State"));task.message=t.getString("Message");task.progress=t.getInt("Progress");task.readChunks=t.getInt("ReadChunks");task.undoCursor=t.getInt("UndoCursor");task.undoIntent=t.getBoolean("UndoIntent")||task.state==State.UNDOING;task.conflicts=t.getInt("Conflicts");task.updated=t.getLong("Updated");task.peakUnitMicros=t.getLong("PeakUnitMicros");task.totalUnitMicros=t.getLong("TotalUnitMicros");
      task.roads=t.getList("Roads",Tag.TAG_COMPOUND).stream().map(v->RoadRecord.load((CompoundTag)v)).toList();task.centers=t.getList("Centers",Tag.TAG_COMPOUND).stream().map(v->((CompoundTag)v).copy()).toList();
      for(Tag v:t.getList("Journals",Tag.TAG_COMPOUND))task.journals.add(StreetJournal.load((CompoundTag)v));var keys=t.getLongArray("Fingerprints");if(keys.length%2!=0)throw new IllegalStateException("路网地形校验记录损坏");for(int i=0;i<keys.length;i+=2)task.fingerprints.put(keys[i],keys[i+1]);for(Tag v:t.getList("Dependencies",Tag.TAG_COMPOUND)){var d=(CompoundTag)v;task.dependencies.put(d.getUUID("Id"),d.getCompound("Road").copy());}
      if(task.roads.size()>StreetPlan.MAX_EDGES||task.progress<0||task.progress>task.roads.size()||task.journals.size()>StreetPlan.MAX_EDGES)throw new IllegalStateException("路网任务进度损坏，已停止恢复");
      if(task.state==State.BUILDING||task.state==State.READING||task.state==State.UNDOING){task.state=State.PAUSED;task.message="服务器已重载；任务暂停，已完成部分保留，可继续或撤销";}
      return task;
    }
  }
  final Map<UUID,Task> tasks=new LinkedHashMap<>();
  public static StreetTaskStore get(ServerLevel level){return level.getDataStorage().computeIfAbsent(StreetTaskStore::load,StreetTaskStore::new,"splineroads_street_tasks");}
  public static StreetTaskStore load(CompoundTag t){if(t.getInt("Version")!=1)throw new IllegalStateException("路网任务存档版本不受支持");var store=new StreetTaskStore();for(Tag v:t.getList("Tasks",Tag.TAG_COMPOUND)){var task=Task.load((CompoundTag)v);store.tasks.put(task.id,task);}store.setDirty();return store;}
  @Override public CompoundTag save(CompoundTag t){t.putInt("Version",1);var tasksTag=new ListTag();tasks.values().forEach(task->tasksTag.add(task.save()));t.put("Tasks",tasksTag);return t;}
  void changed(Task t){t.updated=System.currentTimeMillis();setDirty();}
}
