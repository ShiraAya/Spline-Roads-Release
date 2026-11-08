package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.StreetPlan.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Strict bounded wire/save format. Client geometry is never accepted as construction input. */
public final class StreetPlanCodec {
  public static CompoundTag write(Input input){
    var t=new CompoundTag();t.putInt("Version",StreetPlan.VERSION);t.putLongArray("Area",input.selection().stream().mapToLong(Cell::key).toArray());t.putLongArray("Protect",input.protection().stream().mapToLong(Cell::key).toArray());
    t.putLongArray("Corridor",input.corridor().stream().mapToLong(Cell::key).toArray());t.putBoolean("CorridorConfirmed",input.corridorConfirmed());
    var centers=new ListTag();input.centers().forEach(p->centers.add(point(p)));t.put("Centers",centers);
    var entries=new ListTag();for(var e:input.entries()){var tag=point(e.point());tag.putDouble("Y",e.height());tag.putDouble("Yaw",e.yaw());tag.putBoolean("Left",e.left());tag.putBoolean("Existing",e.existing());tag.putString("Reference",e.reference());entries.add(tag);}t.put("Entries",entries);
    var o=input.options();var v=new CompoundTag();v.putString("Style",o.style().name());v.putDouble("Density",o.density());v.putDouble("BlockSize",o.blockSize());v.putDouble("Direction",o.direction());v.putLong("Seed",o.seed());
    v.putDouble("PrimaryWidth",o.primaryWidth());v.putDouble("SecondaryWidth",o.secondaryWidth());v.putDouble("LocalWidth",o.localWidth());v.putString("PrimarySection",o.primarySection().name());v.putString("SecondarySection",o.secondarySection().name());v.putString("LocalSection",o.localSection().name());v.putBoolean("Left",o.left());v.putBoolean("Sidewalks",o.sidewalks());v.putBoolean("Lighting",o.lighting());v.putDouble("Earthwork",o.earthwork());t.put("Options",v);return t;
  }
  public static Input read(CompoundTag t){
    if(t.getInt("Version")!=StreetPlan.VERSION)throw new IllegalArgumentException("路网草案版本不同，请重新规划");
    long[] area=t.getLongArray("Area"),protect=t.getLongArray("Protect");var centers=t.getList("Centers",Tag.TAG_COMPOUND);var entries=t.getList("Entries",Tag.TAG_COMPOUND);
    if(area.length>StreetPlan.MAX_CHUNKS||protect.length>StreetPlan.MAX_CHUNKS||centers.size()>StreetPlan.MAX_CENTERS||entries.size()>StreetPlan.MAX_ENTRIES)throw new IllegalArgumentException("路网参数数量超限");
    var selection=new HashSet<Cell>();for(long p:area)selection.add(Cell.from(p));var protection=new HashSet<Cell>();for(long p:protect)protection.add(Cell.from(p));
    var locations=new ArrayList<Point>();for(Tag tag:centers)locations.add(point((CompoundTag)tag));var interfaces=new ArrayList<Entry>();for(Tag tag:entries){var e=(CompoundTag)tag;interfaces.add(new Entry(point(e),e.getDouble("Y"),e.getDouble("Yaw"),e.getBoolean("Left"),e.getBoolean("Existing"),e.getString("Reference")));}
    var v=t.getCompound("Options");var options=new Options(Style.valueOf(v.getString("Style")),v.getDouble("Density"),v.getDouble("BlockSize"),v.getDouble("Direction"),v.getLong("Seed"),v.getDouble("PrimaryWidth"),v.getDouble("SecondaryWidth"),v.getDouble("LocalWidth"),RoadGeometry.Style.valueOf(v.getString("PrimarySection")),RoadGeometry.Style.valueOf(v.getString("SecondarySection")),RoadGeometry.Style.valueOf(v.getString("LocalSection")),v.getBoolean("Left"),v.getBoolean("Sidewalks"),v.getBoolean("Lighting"),v.getDouble("Earthwork"));
    var corridor=new HashSet<Cell>();long[] extra=t.getLongArray("Corridor");if(extra.length>StreetPlan.MAX_CHUNKS)throw new IllegalArgumentException("走廊数量超限");for(long key:extra)corridor.add(Cell.from(key));
    return new Input(selection,protection,locations,interfaces,options,corridor,t.getBoolean("CorridorConfirmed"));
  }
  public static CompoundTag write(StreetTerrain terrain){
    var result=new CompoundTag();var chunks=new ListTag();for(var e:terrain.chunks().entrySet()){var t=new CompoundTag();t.putLong("Cell",e.getKey().key());t.putIntArray("Height",e.getValue().heights());t.putByteArray("Ground",e.getValue().ground());t.putLongArray("Fingerprint",e.getValue().fingerprints());chunks.add(t);}result.put("Chunks",chunks);return result;
  }
  public static StreetTerrain terrain(CompoundTag tag){
    var source=tag.getList("Chunks",Tag.TAG_COMPOUND);if(source.size()>StreetPlan.MAX_CHUNKS)throw new IllegalArgumentException("地形快照过大");var chunks=new HashMap<Cell,StreetTerrain.Chunk>();
    for(Tag item:source){var t=(CompoundTag)item;var key=Cell.from(t.getLong("Cell"));if(chunks.put(key,new StreetTerrain.Chunk(t.getIntArray("Height"),t.getByteArray("Ground"),t.getLongArray("Fingerprint")))!=null)throw new IllegalArgumentException("地形快照区块重复");}return new StreetTerrain(chunks);
  }
  private static CompoundTag point(Point p){var t=new CompoundTag();t.putDouble("X",p.x());t.putDouble("Z",p.z());return t;}
  private static Point point(CompoundTag t){return new Point(t.getDouble("X"),t.getDouble("Z"));}
  private StreetPlanCodec(){}
}
