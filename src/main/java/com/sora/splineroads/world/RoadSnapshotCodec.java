package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionSpec;
import java.util.*;
import net.minecraft.nbt.*;

/** Packet-local immutable junction sharing. Saved worlds retain the original format. */
public final class RoadSnapshotCodec {
  public static void write(CompoundTag packet,List<RoadRecord> records){
    var shared=new HashMap<JunctionSpec,Integer>();var specs=new ListTag();var roads=new ListTag();
    for(var record:records)roads.add(record.saveShared(shared,specs));
    packet.put("Roads",roads);if(!specs.isEmpty())packet.put("JunctionSpecs",specs);else packet.remove("JunctionSpecs");
  }
  public static List<RoadRecord> read(CompoundTag packet){
    var roads=packet.getList("Roads",Tag.TAG_COMPOUND);var definitions=packet.getList("JunctionSpecs",Tag.TAG_COMPOUND);
    if(definitions.size()>roads.size())throw new IllegalArgumentException("Too many shared junction definitions");
    var specs=new ArrayList<JunctionSpec>();for(var definition:definitions)specs.add(JunctionCodec.read((CompoundTag)definition));
    var result=new ArrayList<RoadRecord>(roads.size());for(var road:roads)result.add(RoadRecord.loadShared((CompoundTag)road,specs));return List.copyOf(result);
  }
  private RoadSnapshotCodec(){}
}
