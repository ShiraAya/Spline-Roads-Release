package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;

/** A road editor for the link, independent of the junction's movements and signals. */
public final class SideRoads {
  public static CompoundTag payload(CompoundTag junction){
    var t=junction.copy();var spec=JunctionCodec.read(t.getCompound("Spec"));var arm=spec.arms().get(2);
    t.putString("Kind","sideRoad");t.putInt("Signature",signature(junction));
    t.put("Settings",RoadRecord.writeSettings(JunctionRoads.editable(arm)));
    t.put("StartNode",RoadRecord.writeNode(arm.endpoint()));
    t.put("EndNode",RoadRecord.writeNode(new Node(spec.center(),RoadPlanner.yaw(arm.inward()),0)));
    return t;
  }
  public static int signature(CompoundTag t){return Objects.hash(t.getCompound("Spec"),t.getCompound("PairSpec"),t.getInt("MainSignature"),t.getInt("MainSignatureB"),t.getInt("BranchSignature"),t.getInt("MarkerSignature"));}
  public static CompoundTag action(CompoundTag draft){
    var t=new CompoundTag();
    for(String key:List.of("Id","Main","AttachedPoint","Branch","MainFirst","Paired","MainB","AttachedPointB","Fallback","MainSignature","MainSignatureB","BranchSignature","Signature","Settings","MixedMarker","Marker","MixedLink","MarkerSignature"))
      if(draft.contains(key))t.put(key,draft.get(key).copy());
    t.putString("Action","junctionSideRoad");return t;
  }
  public static Settings settings(Settings wanted,Settings outside,boolean attached){
    wanted.validate();
    if(RoadProfile.catalog(wanted.style()).type()!=RoadProfile.Type.ORDINARY||wanted.options().leftTraffic())
      throw new IllegalArgumentException("附属点连接请选择右侧通行的普通道路");
    // The two junction ports own alignment and elevation; this editor owns the road section/furniture.
    var options=wanted.options().lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY)
        .route(outside.options().routing()).ends(attached?outside.options().ends():RoadTransitions.Ends.NONE);
    return new Settings(outside.mode(),wanted.style(),wanted.width(),outside.thickness(),outside.tension(),outside.arcDegrees())
        .structure(outside.structure()).options(options).taper(attached?outside.startWidth():wanted.width(),wanted.width());
  }
  public static Arm change(Arm old,Settings wanted,boolean incoming){
    var settings=settings(wanted,old.external(),old.attached());
    var fresh=JunctionSpec.arm(old.endpoint(),old.inward(),settings,incoming,old.phase());
    var lanes=new ArrayList<Lane>();for(int i=0;i<fresh.incoming();i++)lanes.add(i<old.lanes().size()?old.lanes().get(i):Lane.AUTO);
    return new Arm(old.endpoint(),old.inward(),settings,fresh.incoming(),fresh.outgoing(),fresh.width(),fresh.median(),fresh.medianKind(),fresh.cycleWidth(),fresh.curbWidth(),old.crosswalk(),old.crossingWidth(),old.crossingSetback(),old.stopGap(),old.phase(),lanes,old.attached(),old.advanceSign(),old.nameSign(),false);
  }
  public static JunctionSpec preview(JunctionSpec spec,Settings wanted,boolean incoming){
    var arms=new ArrayList<>(spec.arms());arms.set(2,change(arms.get(2),wanted,incoming));
    return spec.arms(arms).supportedCrossings();
  }
  private SideRoads(){}
}
