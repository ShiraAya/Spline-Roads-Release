package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.nbt.*;
import java.util.*;
/** The on-disk type is explicitly an attached road point, never a lane point. */
public final class AttachedPointCodec {
  private static CompoundTag v(V p){return RoadRecord.writeNode(new Node(p,0,0));}
  private static V v(CompoundTag t){return RoadRecord.readNode(t).position();}
  public static CompoundTag write(RoadAttachments.Data data){
    var t=new CompoundTag();t.putInt("ControlVersion",2);var points=new ListTag();var spans=new ListTag();
    for(var p:data.points()){var q=new CompoundTag();q.putUUID("Id",p.id());q.put("Position",v(p.position()));q.put("Anchor",v(p.anchor()));q.put("Origin",v(p.origin()));q.putBoolean("Controlled",p.controlled());if(p.elevated())q.putBoolean("Elevated",true);points.add(q);}
    for(var s:data.spans()){var q=new CompoundTag();q.put("Start",v(s.start()));q.put("End",v(s.end()));q.putBoolean("HideArrows",s.paint().hideArrows());var lines=new ListTag();for(var e:s.paint().lines()){var l=new CompoundTag();l.putString("Key",e.key());l.putString("Pattern",e.pattern().name());l.putDouble("Width",e.width());lines.add(l);}q.put("Lines",lines);spans.add(q);}
    t.put("Points",points);t.put("Spans",spans);return t;
  }
  public static RoadAttachments.Data read(CompoundTag t){
    var points=new ArrayList<RoadAttachments.Point>();var spans=new ArrayList<RoadAttachments.Span>();
    for(Tag tag:t.getList("Points",Tag.TAG_COMPOUND)){var p=(CompoundTag)tag;points.add(new RoadAttachments.Point(p.getUUID("Id"),v(p.getCompound("Position")),v(p.getCompound("Anchor")),p.getBoolean("Controlled"),p.contains("Origin")?v(p.getCompound("Origin")):v(p.getCompound("Anchor")),p.getBoolean("Elevated")));}
    for(Tag tag:t.getList("Spans",Tag.TAG_COMPOUND)){var s=(CompoundTag)tag;var lines=new ArrayList<RoadLaneLines.Edit>();for(Tag e:s.getList("Lines",Tag.TAG_COMPOUND)){var q=(CompoundTag)e;lines.add(new RoadLaneLines.Edit(q.getString("Key"),RoadLaneLines.Pattern.valueOf(q.getString("Pattern")),q.getDouble("Width")));}spans.add(new RoadAttachments.Span(v(s.getCompound("Start")),v(s.getCompound("End")),new RoadAttachments.Paint(lines,s.getBoolean("HideArrows"))));}
    // Older deformation treated every marker as a knot once any point was controlled.
    // Preserve that stored shape on migration; new ordinary markers do not become knots.
    if(t.getInt("ControlVersion")<2&&points.stream().anyMatch(RoadAttachments.Point::controlled))
      points.replaceAll(p->p.controlled()?p:new RoadAttachments.Point(p.id(),p.position(),p.anchor(),true,p.origin(),p.elevated()));
    return new RoadAttachments.Data(points,spans);
  }
  private AttachedPointCodec(){}
}
