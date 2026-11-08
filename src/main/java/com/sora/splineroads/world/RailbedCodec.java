package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

/** SR owns these snapshots; loading them never asks MTR to recreate a rail. */
public final class RailbedCodec {
  public static CompoundTag options(RailbedOptions value){
    var t=new CompoundTag();t.putBoolean("LeftRail",value.leftRail());t.putBoolean("RightRail",value.rightRail());t.putBoolean("Catenary",value.catenary());
    t.putDouble("PoleSpacing",value.poleSpacing());t.putDouble("WireHeight",value.wireHeight());t.putInt("SingleSide",value.singleSide());t.putString("Bridge",value.bridge().name());t.putString("Tunnel",value.tunnel().name());t.putString("Fence",value.fence().name());return t;
  }
  public static RailbedOptions options(CompoundTag tag){
    if(tag.isEmpty())return RailbedOptions.DEFAULT;
    return new RailbedOptions(tag.getBoolean("LeftRail"),tag.getBoolean("RightRail"),tag.getBoolean("Catenary"),tag.getDouble("PoleSpacing"),tag.getDouble("WireHeight"),tag.getInt("SingleSide"),tag.contains("Bridge")?RoadInfrastructure.Bridge.valueOf(tag.getString("Bridge")):RoadInfrastructure.Bridge.STANDARD,tag.contains("Tunnel")?RoadInfrastructure.Tunnel.valueOf(tag.getString("Tunnel")):RoadInfrastructure.Tunnel.BOX,tag.contains("Fence")?RailbedOptions.Fence.valueOf(tag.getString("Fence")):RailbedOptions.Fence.SOLID);
  }
  public static CompoundTag write(RailbedPath.Path path){var out=new CompoundTag();var segments=new ListTag();
    for(var s:path.segments()){var t=new CompoundTag();t.putString("Id",s.id());t.putLong("From",pos(s.from()).asLong());t.putLong("To",pos(s.to()).asLong());t.putBoolean("Reverse",s.reverse());t.putDouble("Length",s.length());
      var styles=new ListTag();s.styles().forEach(style->styles.add(StringTag.valueOf(style)));t.put("Styles",styles);
      var points=new ListTag();for(var p:s.points()){var q=new CompoundTag();q.putDouble("S",p.parameter());q.putDouble("X",p.position().x());q.putDouble("Y",p.position().y());q.putDouble("Z",p.position().z());points.add(q);}t.put("Points",points);segments.add(t);}
    out.put("Segments",segments);return out;}
  public static RailbedPath.Path read(CompoundTag tag){var segments=new ArrayList<RailbedPath.Segment>();var list=tag.getList("Segments",Tag.TAG_COMPOUND);if(list.size()>RailbedPath.MAX_SEGMENTS)throw new IllegalArgumentException("铁路快照轨段数量超限");int count=0;
    for(Tag raw:list){var t=(CompoundTag)raw;var source=t.getList("Points",Tag.TAG_COMPOUND);count+=source.size();if(count>RailbedPath.MAX_POINTS)throw new IllegalArgumentException("铁路快照采样数量超限");var points=new ArrayList<RailbedPath.Point>();for(Tag r:source){var p=(CompoundTag)r;points.add(new RailbedPath.Point(p.getDouble("S"),new V(p.getDouble("X"),p.getDouble("Y"),p.getDouble("Z"))));}
      var styles=t.getList("Styles",Tag.TAG_STRING).stream().map(Tag::getAsString).toList();segments.add(new RailbedPath.Segment(t.getString("Id"),node(BlockPos.of(t.getLong("From"))),node(BlockPos.of(t.getLong("To"))),t.getBoolean("Reverse"),t.getDouble("Length"),styles,points));}
    return new RailbedPath.Path(segments);}
  public static BlockPos pos(RailbedPath.Node p){return new BlockPos(p.x(),p.y(),p.z());}
  public static RailbedPath.Node node(BlockPos p){return new RailbedPath.Node(p.getX(),p.getY(),p.getZ());}
  public static net.minecraft.nbt.CompoundTag profile(com.sora.splineroads.core.RailbedProfile value){
    var tag=new net.minecraft.nbt.CompoundTag();var paths=new net.minecraft.nbt.ListTag();value.paths().forEach(p->paths.add(write(p)));tag.put("Paths",paths);tag.putDouble("Width",value.requestedWidth());tag.put("Fittings",options(value.fittings()));return tag;
  }
  public static com.sora.splineroads.core.RailbedProfile profile(net.minecraft.nbt.CompoundTag tag){return new com.sora.splineroads.core.RailbedProfile(tag.getList("Paths",net.minecraft.nbt.Tag.TAG_COMPOUND).stream().map(t->read((net.minecraft.nbt.CompoundTag)t)).toList(),tag.getDouble("Width"),options(tag.getCompound("Fittings")));}
  private RailbedCodec(){}
}
