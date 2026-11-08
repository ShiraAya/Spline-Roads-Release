package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Road-tool routing and edits are distinct from junction controls and movements. */
public final class JunctionRoads {
  public static RoadRecord logical(RoadData data,RoadRecord road){
    if(road.junction()==null)return road;
    int arm=road.junction().get().arm();var saved=data.junctions.get(road.assembly());
    if(saved==null||!saved.getBoolean("Auto")||arm<0)return null;
    var ids=saved.getList("ArmRoads",Tag.TAG_STRING);if(arm>=ids.size())return null;
    try{return data.streets.get(UUID.fromString(ids.getString(arm)));}catch(IllegalArgumentException e){return null;}
  }
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,RoadRecord road){
    if(road.junction()==null||road.junction().get().arm()<0)
      throw new IllegalArgumentException("这里是路口中心，请使用普通路口编辑器；道路连接器只编辑接入路段");
    var t=Junctions.payload(level,player,new long[0],road.assembly());
    if(t.getBoolean("Auto"))throw new IllegalArgumentException("接入道路记录已变化，请重新选择路口外侧路段");
    t.putString("Kind","armRoad");t.putInt("Arm",road.junction().get().arm());
    t.putInt("Signature",t.getCompound("Spec").hashCode());
    var spec=JunctionCodec.read(t.getCompound("Spec"));var a=spec.arms().get(t.getInt("Arm"));var settings=editable(a);settings=settings.options(settings.options().traffic(spec.leftTraffic()));
    t.put("Settings",RoadRecord.writeSettings(settings));t.put("StartNode",RoadRecord.writeNode(a.endpoint()));
    var m=road.mesh();t.put("EndNode",RoadRecord.writeNode(new com.sora.splineroads.core.RoadGeometry.Node(m.last().center(),a.endpoint().yaw(),0)));
    return t;
  }
  public static com.sora.splineroads.core.RoadGeometry.Settings editable(Arm a){
    var type=RoadProfile.Type.ORDINARY;boolean two=a.incoming()>0&&a.outgoing()>0;
    int count=two?Math.max(2,Math.min(8,2*Math.max(a.incoming(),a.outgoing()))):Math.min(4,a.incoming()+a.outgoing());
    var median=two?a.medianKind():RoadProfile.Median.NONE;
    if(median==RoadProfile.Median.NONE||median==RoadProfile.Median.DASHED_YELLOW&&count!=2)median=two?RoadProfile.Median.DOUBLE_YELLOW:RoadProfile.Median.NONE;
    var style=RoadProfile.choose(type,count,two,median,false);
    var options=a.external().options().extras(a.cycleWidth()>0,a.external().options().cycleRail(),a.curbWidth()>0);
    options=options.lanes(a.incoming()>0?new RoadLanes.Counts(a.incoming(),a.outgoing()):new RoadLanes.Counts(a.outgoing(),0));
    if(!a.attached())options=options.ends(RoadTransitions.Ends.NONE);
    return new com.sora.splineroads.core.RoadGeometry.Settings(com.sora.splineroads.core.RoadGeometry.Mode.STRAIGHT,style,RoadProfile.width(style,options,a.laneWidth()),a.external().thickness(),a.external().tension(),a.external().arcDegrees()).structure(a.external().structure()).options(options);
  }
  public static Arm change(Arm old,com.sora.splineroads.core.RoadGeometry.Settings settings){
    settings.validate();if(RoadProfile.catalog(settings.style()).type()!=RoadProfile.Type.ORDINARY)throw new IllegalArgumentException("平交接入段请选择普通道路");
    var fresh=JunctionSpec.arm(old.endpoint(),old.inward(),settings,old.incoming()>0,old.phase());
    var lanes=new ArrayList<Lane>();for(int i=0;i<fresh.incoming();i++)lanes.add(i<old.lanes().size()?old.lanes().get(i):Lane.AUTO);
    // Keep attached outside ports fixed; the selected approach tapers to its new width.
    var external=old.attached()?settings.taper(old.external().startWidth(),settings.width()):settings;
    return new Arm(old.endpoint(),old.inward(),external,fresh.incoming(),fresh.outgoing(),fresh.width(),fresh.median(),fresh.medianKind(),fresh.cycleWidth(),fresh.curbWidth(),old.crosswalk(),old.crossingWidth(),old.crossingSetback(),old.stopGap(),old.phase(),lanes,old.attached(),old.advanceSign(),old.nameSign(),old.rightSlip());
  }
  public static Arm change(Arm old,CompoundTag edit){
    int in=edit.getInt("Incoming"),out=edit.getInt("Outgoing");
    var lanes=new ArrayList<Lane>();for(int i=0;i<in&&i<RoadLanes.MAX_DIRECTION;i++)lanes.add(i<old.lanes().size()?old.lanes().get(i):Lane.AUTO);
    var walk=old.external().options().sidewalk();
    walk=new RoadSidewalks.Config(edit.getBoolean("Walk"),RoadSidewalks.Side.valueOf(edit.getString("WalkSide")),edit.getInt("WalkWidth"),edit.getString("WalkMaterial"),true,edit.getBoolean("Tactile"));
    var options=old.external().options().sidewalk(walk).cycleFinish(edit.getDouble("CycleWidth")==0?RoadProfile.Options.CycleFinish.NONE:edit.getBoolean("CycleAsphalt")?RoadProfile.Options.CycleFinish.ASPHALT:RoadProfile.Options.CycleFinish.GREEN);
    // Preserve the outside connection's actual port, grade and width. Only this approach changes.
    return new Arm(old.endpoint(),old.inward(),old.external().options(options),in,out,edit.getDouble("Width"),edit.getDouble("Median"),RoadProfile.Median.valueOf(edit.getString("MedianKind")),edit.getDouble("CycleWidth"),edit.getDouble("CurbWidth"),old.crosswalk(),old.crossingWidth(),old.crossingSetback(),old.stopGap(),old.phase(),lanes,old.attached(),old.advanceSign(),old.nameSign(),old.rightSlip());
  }
  public static CompoundTag fields(Arm a){
    var t=new CompoundTag();t.putInt("Incoming",a.incoming());t.putInt("Outgoing",a.outgoing());t.putDouble("Width",a.width());t.putDouble("Median",a.median());t.putString("MedianKind",a.medianKind().name());t.putDouble("CycleWidth",a.cycleWidth());t.putDouble("CurbWidth",a.curbWidth());
    var w=a.external().options().sidewalk();t.putBoolean("Walk",w.enabled());t.putInt("WalkWidth",w.width());t.putString("WalkSide",w.side().name());t.putString("WalkMaterial",w.material());t.putBoolean("Tactile",w.tactile());t.putBoolean("CycleAsphalt",a.external().options().cycleAsphalt());return t;
  }
  public static String build(ServerLevel level,ServerPlayer player,CompoundTag command){
    var fresh=Junctions.payload(level,player,new long[0],command.getUUID("Id"));
    if(fresh.getBoolean("Auto"))throw new IllegalArgumentException("自动路口的道路请重新用道路连接器选择");
    if(command.getInt("Signature")!=fresh.getCompound("Spec").hashCode())throw new IllegalArgumentException("路口已改变，请重新打开这段道路");
    int arm=command.getInt("Arm");var spec=JunctionCodec.read(fresh.getCompound("Spec"));
    if(arm<0||arm>=spec.arms().size())throw new IllegalArgumentException("接入路段已不存在");
    var arms=new ArrayList<>(spec.arms());arms.set(arm,command.contains("Settings")?change(arms.get(arm),RoadRecord.readSettings(command.getCompound("Settings"))):change(arms.get(arm),command.getCompound("Fields")));
    fresh.put("Spec",JunctionCodec.write(spec.arms(arms)));Junctions.buildRoad(level,player,fresh,arm);
    return "所选接入道路已更新；信号配时和通行关系由普通路口编辑器管理";
  }
  private JunctionRoads(){}
}
