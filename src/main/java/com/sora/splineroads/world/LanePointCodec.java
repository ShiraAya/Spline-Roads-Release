package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.LanePoints.*;
import net.minecraft.nbt.*;
import java.util.*;
public final class LanePointCodec {
  public static CompoundTag position(V value){return RoadRecord.writeNode(new Node(value,0,0));}
  public static V position(CompoundTag tag){return RoadRecord.readNode(tag).position();}
  public static CompoundTag ref(Ref r){var t=new CompoundTag();if(r.junction()!=null)t.putUUID("Junction",r.junction());else {t.putUUID("Road",r.road());t.putUUID("Point",r.point());}return t;}
  public static Ref ref(CompoundTag t){return t.hasUUID("Junction")?Ref.junction(t.getUUID("Junction")):Ref.lane(t.getUUID("Road"),t.getUUID("Point"));}
  public static CompoundTag options(Options o){var t=new CompoundTag();t.putString("Path",o.path().name());t.putBoolean("SourceExtra",o.sourceExtra());t.putBoolean("TargetExtra",o.targetExtra());t.putDouble("Radius",o.radius());t.putDouble("Transition",o.transition());t.putString("Departure",o.departure().name());t.putString("Arrival",o.arrival().name());t.putString("Elevation",o.elevation().name());t.putString("Landing",o.landing().name());t.putBoolean("GradeOverride",o.gradeOverride());t.putBoolean("AllowTunnel",o.allowTunnel());if(o.lanes()!=1)t.putInt("Lanes",o.lanes());return t;}
  public static Options options(CompoundTag t){
    if(t.isEmpty())return Options.DEFAULT;
    return new Options(Path.valueOf(t.getString("Path")),
      t.contains("Departure")?Departure.valueOf(t.getString("Departure")):t.getBoolean("SourceExtra")?Departure.EXTRA:Departure.BRANCH,
      t.contains("Arrival")?Arrival.valueOf(t.getString("Arrival")):t.getBoolean("TargetExtra")?Arrival.EXTRA:Arrival.MERGE,
      t.getDouble("Radius"),t.getDouble("Transition"),
      t.contains("Elevation")?Elevation.valueOf(t.getString("Elevation")):Elevation.AUTO,
      t.contains("Landing")?Landing.valueOf(t.getString("Landing")):Landing.FLEXIBLE,
      t.getBoolean("GradeOverride"),t.getBoolean("AllowTunnel"),t.contains("Lanes")?t.getInt("Lanes"):1);
  }
  public static CompoundTag link(Link link){var t=new CompoundTag();t.put("From",ref(link.from()));t.put("To",ref(link.to()));t.put("Options",options(link.options()));t.putDouble("TargetOffset",link.targetOffset());t.putBoolean("ProtectedMerge",link.protectedMerge());t.putBoolean("RectangularClosure",link.rectangularClosure());if(link.junctionMouth()!=null)t.put("JunctionMouth",position(link.junctionMouth()));return t;}
  public static Link link(CompoundTag t){return new Link(ref(t.getCompound("From")),ref(t.getCompound("To")),options(t.getCompound("Options")),t.contains("JunctionMouth")?position(t.getCompound("JunctionMouth")):null,t.getDouble("TargetOffset"),t.getBoolean("ProtectedMerge"),t.getBoolean("RectangularClosure"));}
  public static CompoundTag write(Data data){
    var t=new CompoundTag();var points=new ListTag();for(var p:data.points()){var q=new CompoundTag();q.putUUID("Id",p.id());q.putString("Origin",p.origin().name());q.putInt("Lane",p.lane());q.putDouble("MergeLength",p.mergeLength());q.put("Position",position(p.position()));if(p.anchor()!=null)q.put("Anchor",position(p.anchor()));points.add(q);}t.put("Points",points);
    t.putInt("PriorityDepth",data.priorityDepth());if(data.link()!=null)t.put("Link",link(data.link()));var openings=new ListTag();for(var o:data.openings()){var q=new CompoundTag();q.putUUID("Connection",o.connection());q.putDouble("HalfWidth",o.halfWidth());var path=new ListTag();for(V v:o.centerline())path.add(position(v));q.put("Path",path);openings.add(q);}t.put("Openings",openings);
    var cuts=new ListTag();for(var c:data.cuts()){var q=new CompoundTag();q.putUUID("Connection",c.connection());q.putInt("Lane",c.lane());q.putInt("Sign",c.sign());q.putDouble("Begin",c.begin());q.putDouble("End",c.end());q.putDouble("Transition",c.transition());q.putBoolean("Temporary",c.temporary());q.putBoolean("ArrivalClosure",c.arrival());q.putBoolean("Rectangular",c.rectangular());q.putBoolean("Underpass",c.underpass());if(c.replacement()!=null)q.putUUID("Replacement",c.replacement());cuts.add(q);}t.put("LaneCuts",cuts);
    var additions=new ListTag();for(var a:data.additions()){var q=new CompoundTag();q.putUUID("Connection",a.connection());q.putInt("Slot",a.slot());q.putInt("Sign",a.sign());q.putDouble("Station",a.station());q.putDouble("Transition",a.transition());if(Double.isFinite(a.end()))q.putDouble("End",a.end());additions.add(q);}t.put("OuterLaneAdditions",additions);var bands=new ListTag();for(var b:data.bands()){var q=new CompoundTag();q.putDouble("Station",b.station());q.putDouble("Low",b.low());q.putDouble("High",b.high());bands.add(q);}if(!bands.isEmpty())t.put("MotorBands",bands);
    var access=new ListTag();for(var r:data.access()){
      var q=new CompoundTag();q.putUUID("Connection",r.connection());q.putUUID("Point",r.point());q.put("Lanes",write(Data.EMPTY.cuts(r.cuts()).additions(r.additions())));
      var gaps=new ListTag();for(var g:r.gaps()){var v=new CompoundTag();v.putDouble("From",g.from());v.putDouble("To",g.to());v.putDouble("Low",g.low());v.putDouble("High",g.high());gaps.add(v);}q.put("Gaps",gaps);access.add(q);
    }if(!access.isEmpty())t.put("ElevatedAccess",access);return t;
  }
  public static Data read(CompoundTag t){
    var points=new ArrayList<Point>();for(Tag value:t.getList("Points",Tag.TAG_COMPOUND)){var p=(CompoundTag)value;points.add(new Point(p.getUUID("Id"),Origin.valueOf(p.getString("Origin")),p.getInt("Lane"),position(p.getCompound("Position")),p.contains("Anchor",Tag.TAG_COMPOUND)?position(p.getCompound("Anchor")):null,p.getDouble("MergeLength")));}
    var openings=new ArrayList<Opening>();for(Tag value:t.getList("Openings",Tag.TAG_COMPOUND)){var o=(CompoundTag)value;var path=new ArrayList<V>();for(Tag p:o.getList("Path",Tag.TAG_COMPOUND))path.add(position((CompoundTag)p));openings.add(new Opening(o.getUUID("Connection"),path,o.getDouble("HalfWidth")));}
    var cuts=new ArrayList<LaneSections.Cut>();for(Tag v:t.getList("LaneCuts",Tag.TAG_COMPOUND)){var q=(CompoundTag)v;cuts.add(new LaneSections.Cut(q.getUUID("Connection"),q.getInt("Lane"),q.getInt("Sign"),q.getDouble("Begin"),q.getDouble("End"),q.getDouble("Transition"),q.hasUUID("Replacement")?q.getUUID("Replacement"):null,q.getBoolean("Temporary"),q.getBoolean("ArrivalClosure"),q.getBoolean("Rectangular"),q.getBoolean("Underpass")));}
    var additions=new ArrayList<LaneAdditions.Addition>();for(Tag v:t.getList("OuterLaneAdditions",Tag.TAG_COMPOUND)){var q=(CompoundTag)v;additions.add(new LaneAdditions.Addition(q.getUUID("Connection"),q.getInt("Slot"),q.getInt("Sign"),q.getDouble("Station"),q.getDouble("Transition"),q.contains("End")?q.getDouble("End"):Double.NaN));}
    var bands=new ArrayList<Band>();for(Tag v:t.getList("MotorBands",Tag.TAG_COMPOUND)){var q=(CompoundTag)v;bands.add(new Band(q.getDouble("Station"),q.getDouble("Low"),q.getDouble("High")));}
    var access=new ArrayList<ElevatedAccess.Reservation>();for(Tag value:t.getList("ElevatedAccess",Tag.TAG_COMPOUND)){
      var q=(CompoundTag)value;var laneTag=q.getCompound("Lanes").copy();laneTag.remove("ElevatedAccess");var laneData=read(laneTag);var gaps=new ArrayList<ElevatedAccess.Gap>();
      for(Tag item:q.getList("Gaps",Tag.TAG_COMPOUND)){var v=(CompoundTag)item;gaps.add(new ElevatedAccess.Gap(v.getDouble("From"),v.getDouble("To"),v.getDouble("Low"),v.getDouble("High")));}
      access.add(new ElevatedAccess.Reservation(q.getUUID("Connection"),q.getUUID("Point"),laneData.cuts(),laneData.additions(),gaps));
    }
    return new Data(points,t.contains("Link")?link(t.getCompound("Link")):null,openings,t.contains("PriorityDepth")?t.getInt("PriorityDepth"):t.contains("Link")?1:0,cuts,additions,bands,access);
  }
  private LanePointCodec(){}
}
