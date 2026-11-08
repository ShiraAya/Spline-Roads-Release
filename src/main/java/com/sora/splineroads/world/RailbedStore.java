package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;

/** Part of the same SavedData as the render/collision records and transaction journal. */
public final class RailbedStore {
  public static final class Entry {
    public final RoadRecord road;public final List<RailbedPath.Path> paths;public final double width;public final Structure mode;public final RailbedOptions options;
    public final Long2ObjectOpenHashMap<BlockState> original=new Long2ObjectOpenHashMap<>(),written=new Long2ObjectOpenHashMap<>();
    public Entry(RoadRecord road,List<RailbedPath.Path> paths,double width,Structure mode){this(road,paths,width,mode,RailbedOptions.DEFAULT);}
    public Entry(RoadRecord road,List<RailbedPath.Path> paths,double width,Structure mode,RailbedOptions options){this.road=road;this.paths=List.copyOf(paths);this.width=width;this.mode=mode;this.options=options;}
    public String identity(){return identity(paths);}
    public static String identity(List<RailbedPath.Path> paths){return paths.stream().map(p->p.segments().stream().map(RailbedPath.Segment::id).sorted().toList().toString()).sorted().toList().toString();}
    CompoundTag save(){var t=new CompoundTag();t.put("Road",road.save());var rails=new ListTag();paths.forEach(p->rails.add(RailbedCodec.write(p)));t.put("Paths",rails);t.putDouble("Width",width);t.putString("Mode",mode.name());t.put("Options",RailbedCodec.options(options));t.put("Original",RoadBlockStorage.write(original));t.put("Written",RoadBlockStorage.write(written));return t;}
    static Entry load(CompoundTag t){var e=new Entry(RoadRecord.load(t.getCompound("Road")),t.getList("Paths",Tag.TAG_COMPOUND).stream().map(v->RailbedCodec.read((CompoundTag)v)).toList(),t.getDouble("Width"),Structure.valueOf(t.getString("Mode")),RailbedCodec.options(t.getCompound("Options")));RoadBlockStorage.read(t.getList("Original",Tag.TAG_COMPOUND),e.original);RoadBlockStorage.read(t.getList("Written",Tag.TAG_COMPOUND),e.written);if(!e.original.keySet().equals(e.written.keySet()))throw new IllegalStateException("铁路恢复清单不完整");return e;}
  }
  public static final class Pending {
    final UUID id;final Entry old,next;final Long2ObjectOpenHashMap<BlockState> before,after;
    Pending(UUID id,Entry old,Entry next,Long2ObjectOpenHashMap<BlockState> before,Long2ObjectOpenHashMap<BlockState> after){this.id=id;this.old=old;this.next=next;this.before=before;this.after=after;}
    CompoundTag save(){var t=new CompoundTag();t.putUUID("Id",id);if(old!=null)t.put("Old",old.save());if(next!=null)t.put("Next",next.save());t.put("Before",RoadBlockStorage.write(before));t.put("After",RoadBlockStorage.write(after));return t;}
    static Pending load(CompoundTag t){var before=new Long2ObjectOpenHashMap<BlockState>();var after=new Long2ObjectOpenHashMap<BlockState>();RoadBlockStorage.read(t.getList("Before",Tag.TAG_COMPOUND),before);RoadBlockStorage.read(t.getList("After",Tag.TAG_COMPOUND),after);if(!before.keySet().equals(after.keySet()))throw new IllegalStateException("铁路事务日志不完整");return new Pending(t.getUUID("Id"),t.contains("Old")?Entry.load(t.getCompound("Old")):null,t.contains("Next")?Entry.load(t.getCompound("Next")):null,before,after);}
  }
  public final Map<UUID,Entry> entries=new LinkedHashMap<>();
  final Map<UUID,Pending> pending=new LinkedHashMap<>();
  CompoundTag save(){var out=new CompoundTag();out.putInt("Version",2);var values=new ListTag();entries.values().forEach(e->values.add(e.save()));out.put("Entries",values);var journal=new ListTag();pending.values().forEach(p->journal.add(p.save()));out.put("Pending",journal);return out;}
  void load(CompoundTag tag){if(tag.getInt("Version")>2)throw new IllegalStateException("铁路存档版本过新");for(Tag raw:tag.getList("Entries",Tag.TAG_COMPOUND)){var e=Entry.load((CompoundTag)raw);entries.put(e.road.id(),e);}for(Tag raw:tag.getList("Pending",Tag.TAG_COMPOUND)){var p=Pending.load((CompoundTag)raw);pending.put(p.id,p);}}
}
