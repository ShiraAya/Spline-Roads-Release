package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.nbt.*;
import java.util.*;

final class RoadSliceCodec {
  static CompoundTag write(RoadSlicing.Fragment s){
    var t=new CompoundTag();t.putInt("Version",2);t.putUUID("Source",s.source());t.putDouble("Origin",s.origin());t.putDouble("Total",s.total());t.putString("Connection",s.connection().name());t.put("Applied",AttachedPointCodec.write(s.applied()));t.putByteArray("Supports",RoadRecord.packStructures(s.supports()));
    var anchors=new ListTag();for(var c:s.curveAnchors()){var a=new CompoundTag();a.putUUID("Id",c.id());a.put("Position",LanePointCodec.position(c.position()));anchors.add(a);}t.put("CurveAnchors",anchors);
    var patches=new ListTag();for(var p:s.geometry().patches()){
      var q=new CompoundTag();q.putDouble("From",p.from());q.putDouble("To",p.to());var strips=new ListTag();
      for(var strip:p.strips()){var v=new CompoundTag();v.put("AL",LanePointCodec.position(strip.al()));v.put("AR",LanePointCodec.position(strip.ar()));v.put("BL",LanePointCodec.position(strip.bl()));v.put("BR",LanePointCodec.position(strip.br()));v.putBoolean("Low",strip.lowWall());v.putBoolean("High",strip.highWall());strips.add(v);}
      q.put("Strips",strips);patches.add(q);
    }t.put("Patches",patches);return t;
  }
  static RoadSlicing.Fragment read(CompoundTag t){
    if(t.getInt("Version")!=1&&t.getInt("Version")!=2)throw new IllegalArgumentException("道路分割数据版本无效");
    var patches=new ArrayList<RoadSlicing.Patch>();var list=t.getList("Patches",Tag.TAG_COMPOUND);if(list.size()>4)throw new IllegalArgumentException("道路分割切面数量无效");
    for(Tag tag:list){var p=(CompoundTag)tag;var strips=new ArrayList<LaneDeck.Strip>();var values=p.getList("Strips",Tag.TAG_COMPOUND);if(values.size()>16)throw new IllegalArgumentException("道路分割切面过大");
      for(Tag value:values){var v=(CompoundTag)value;strips.add(new LaneDeck.Strip(LanePointCodec.position(v.getCompound("AL")),LanePointCodec.position(v.getCompound("AR")),LanePointCodec.position(v.getCompound("BL")),LanePointCodec.position(v.getCompound("BR")),v.getBoolean("Low"),v.getBoolean("High")));}
      patches.add(new RoadSlicing.Patch(p.getDouble("From"),p.getDouble("To"),strips));
    }
    var anchors=new ArrayList<RoadProfile.Control>();var saved=t.getList("CurveAnchors",Tag.TAG_COMPOUND);if(saved.size()>32)throw new IllegalArgumentException("分割曲线点数量无效");for(var value:saved){var a=(CompoundTag)value;anchors.add(new RoadProfile.Control(a.getUUID("Id"),LanePointCodec.position(a.getCompound("Position"))));}
    return new RoadSlicing.Fragment(t.getUUID("Source"),t.getDouble("Origin"),t.getDouble("Total"),RoadSlicing.Connection.valueOf(t.getString("Connection")),AttachedPointCodec.read(t.getCompound("Applied")),new RoadSlicing.Geometry(patches),t.contains("Supports")?RoadRecord.unpackStructures(t.getByteArray("Supports")):List.of(),anchors);
  }
  private RoadSliceCodec(){}
}
