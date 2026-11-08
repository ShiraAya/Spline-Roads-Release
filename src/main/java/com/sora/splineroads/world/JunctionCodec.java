package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;
import net.minecraft.nbt.*;

public final class JunctionCodec {
  /** Immutable road receipts are preview context, never client-supplied edit authority. */
  public static CompoundTag editCommand(CompoundTag payload){
    var command=payload.copy();command.remove("FreshSpec");
    if(command.getBoolean("OneSided")){command.remove("OriginalMain");command.remove("OriginalMainB");command.remove("BranchNode");command.getCompound("Spec").remove("OneSide");command.getCompound("PairSpec").remove("OneSide");}
    return command;
  }
  public static CompoundTag write(JunctionSpec s) {
    CompoundTag t=new CompoundTag();t.putInt("Version",1);t.put("Center",RoadRecord.writeNode(new Node(s.center(),0,0)));
    t.putString("Kind",s.kind().name());t.putBoolean("LeftTraffic",s.leftTraffic());
    t.putDouble("Corner",s.cornerRadius());t.putDouble("Island",s.islandRadius());
    t.putInt("RingLanes",s.ringLanes());t.putDouble("RingWidth",s.ringLaneWidth());t.putDouble("Thickness",s.thickness());
    t.putString("Control",s.control().name());t.putInt("Green",s.greenSeconds());t.putInt("Yellow",s.yellowSeconds());
    t.putInt("AllRed",s.allRedSeconds());t.putInt("Offset",s.timeOffset());t.putBoolean("Guides",s.guides());t.putBoolean("GreenIsland",s.greenIsland());t.putBoolean("OuterRail",s.outerRail());t.putBoolean("ParallelApproaches",s.parallelApproaches());
    ListTag arms=new ListTag();
    for(Arm a:s.arms()) {
      CompoundTag p=new CompoundTag();p.put("Node",RoadRecord.writeNode(a.endpoint()));
      p.putDouble("DX",a.inward().x());p.putDouble("DZ",a.inward().z());p.put("External",RoadRecord.writeSettings(a.external()));
      p.putBoolean("Attached",a.attached());p.putInt("In",a.incoming());p.putInt("Out",a.outgoing());p.putDouble("Width",a.width());p.putDouble("Median",a.median());p.putString("MedianKind",a.medianKind().name());
      p.putDouble("Cycle",a.cycleWidth());p.putDouble("Curb",a.curbWidth());p.putBoolean("RightSlip",a.rightSlip());p.putBoolean("AdvanceSign",a.advanceSign());p.putBoolean("NameSign",a.nameSign());p.putBoolean("Crosswalk",a.crosswalk());p.putDouble("CrossWidth",a.crossingWidth());p.putDouble("Setback",a.crossingSetback());p.putDouble("StopGap",a.stopGap());p.putInt("Phase",a.phase());
      ListTag lanes=new ListTag();for(Lane l:a.lanes()){CompoundTag q=new CompoundTag();q.putInt("Mask",l.mask());q.putBoolean("SplitLeft",l.splitLeft());q.putBoolean("ControlRight",l.controlRight());q.putInt("LeftPhase",l.leftPhase());q.putIntArray("Targets",l.targets());q.putIntArray("TargetLanes",l.targetLanes());lanes.add(q);}p.put("Lanes",lanes);arms.add(p);
    }
    t.put("Arms",arms);
    if(s.oneSide()!=null){var source=s.oneSide();var mesh=source.road();var p=new CompoundTag();
      var record=new RoadRecord(new UUID(0,0),new UUID(0,0),net.minecraft.core.BlockPos.ZERO,net.minecraft.core.BlockPos.ZERO,new Node(mesh.first().center(),0,0),new Node(mesh.last().center(),0,0),mesh.settings()).alignment(null,mesh).structures(source.structures());
      record=record.fragment(new RoadSlicing.Fragment(new UUID(0,0),0,mesh.length(),RoadSlicing.Connection.CONTINUOUS,mesh.settings().options().attachments(),mesh.slicing()==null?new RoadSlicing.Geometry(List.of()):mesh.slicing()));
      p.put("Road",record.save());p.putDouble("From",source.from());p.putDouble("To",source.to());p.putInt("Side",source.side());t.put("OneSide",p);t.putBoolean("MainAdvancePolicy",true);
    }return t;
  }
  public static JunctionSpec read(CompoundTag t) {
    if(t.getInt("Version")!=1)throw new IllegalArgumentException("不支持的路口数据版本");
    var entries=t.getList("Arms",Tag.TAG_COMPOUND);if(t.getString("Kind").equals("ROUNDABOUT")?entries.size()>8:entries.size()<2||entries.size()>6)throw new IllegalArgumentException("普通路口支持 2–6 向，环岛支持 0–8 个接入口");
    List<Arm> arms=new ArrayList<>();
    for(Tag entry:entries) {
      CompoundTag a=(CompoundTag)entry;var lanes=new ArrayList<Lane>();
      if(a.getList("Lanes",Tag.TAG_COMPOUND).size()>RoadLanes.MAX_DIRECTION)throw new IllegalArgumentException("入口车道过多");
      for(Tag item:a.getList("Lanes",Tag.TAG_COMPOUND)){CompoundTag l=(CompoundTag)item;lanes.add(new Lane(l.getInt("Mask"),Arrays.stream(l.getIntArray("Targets")).boxed().toList(),Arrays.stream(l.getIntArray("TargetLanes")).boxed().toList(),l.getBoolean("SplitLeft"),l.contains("LeftPhase")?l.getInt("LeftPhase"):-1,l.getBoolean("ControlRight")));}
      arms.add(new Arm(RoadRecord.readNode(a.getCompound("Node")),new V(a.getDouble("DX"),0,a.getDouble("DZ")),RoadRecord.readSettings(a.getCompound("External")),a.getInt("In"),a.getInt("Out"),a.getDouble("Width"),a.getDouble("Median"),RoadProfile.Median.valueOf(a.getString("MedianKind")),a.getDouble("Cycle"),a.getDouble("Curb"),a.getBoolean("Crosswalk"),a.getDouble("CrossWidth"),a.getDouble("Setback"),a.getDouble("StopGap"),a.getInt("Phase"),lanes,a.getBoolean("Attached"),!a.contains("AdvanceSign")||a.getBoolean("AdvanceSign"),!a.contains("NameSign")||a.getBoolean("NameSign"),a.getBoolean("RightSlip")));
    }
    var source=readSide(t);
    if(source!=null&&arms.size()==3){
      for(int i=0;i<3;i++){
        var arm=arms.get(i);
        boolean enabled=i==source.incoming()&&arms.get(2).outgoing()>0&&(!t.getBoolean("MainAdvancePolicy")||arm.advanceSign());
        arms.set(i,arm.signs(enabled,arm.nameSign()));
      }
    }
    return new JunctionSpec(RoadRecord.readNode(t.getCompound("Center")).position(),Kind.valueOf(t.getString("Kind")),t.getBoolean("LeftTraffic"),t.getDouble("Corner"),t.getDouble("Island"),t.getInt("RingLanes"),t.getDouble("RingWidth"),t.getDouble("Thickness"),Control.valueOf(t.getString("Control")),t.getInt("Green"),t.getInt("Yellow"),t.getInt("AllRed"),t.getInt("Offset"),t.getBoolean("Guides"),t.getBoolean("GreenIsland"),t.getBoolean("OuterRail"),arms,t.getBoolean("ParallelApproaches")).oneSide(source);
  }
  private static OneSidedJunction.Source readSide(CompoundTag t){
    if(!t.contains("OneSide"))return null;var p=t.getCompound("OneSide");var saved=p.getCompound("Road");
    if(saved.contains("Junction")||saved.contains("Toll"))throw new IllegalArgumentException("单边路口主路快照无效");
    var r=RoadRecord.load(saved);
    if(r.junction()!=null||r.toll()!=null)throw new IllegalArgumentException("单边路口主路快照无效");
    return new OneSidedJunction.Source(r.mesh(),r.structures(),p.getDouble("From"),p.getDouble("To"),p.getInt("Side"));
  }
  public static CompoundTag writeRef(JunctionPlanner.Ref ref){var t=write(ref.spec());t.putInt("Piece",ref.piece());t.putInt("GeometryVersion",ref.geometryVersion());
    if(!ref.hostCuts().isEmpty()){var cuts=new ListTag();for(var cut:ref.hostCuts()){var c=new CompoundTag();c.putDouble("From",cut.from());c.putDouble("To",cut.to());c.putInt("Side",cut.side());cuts.add(c);}t.put("HostCuts",cuts);}return t;}
  public static JunctionPlanner.Ref readRef(CompoundTag t){var cuts=new ArrayList<OneSidedJunction.Cut>();for(var value:t.getList("HostCuts",Tag.TAG_COMPOUND)){var c=(CompoundTag)value;cuts.add(new OneSidedJunction.Cut(c.getDouble("From"),c.getDouble("To"),c.getInt("Side")));}return new JunctionPlanner.Ref(read(t),t.getInt("Piece"),t.contains("GeometryVersion")?t.getInt("GeometryVersion"):21,cuts);}
  private JunctionCodec() {}
}
