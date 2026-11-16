package com.sora.splineroads.world;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
public final class Followup211Scene {
 public static String preflight(ServerPlayer player){
  player.server.getPlayerList().op(player.getGameProfile());
  player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SplineRoads.INTERCHANGE.get()));
  var road=RoadTool.pick(player);if(road==null)throw new AssertionError("camera misses SPUI host");
  var d=RoadData.get(player.serverLevel());var id=ParallelSpui.groupAt(d,road.record,new RoadGeometry.V(player.getX(),player.getY(),player.getZ()));
  if(id==null)throw new AssertionError("SPUI descriptor did not resolve");
  var saved=d.interchanges.get(id);var payload=Interchanges.payload(player.serverLevel(),player,saved.getLongArray("Points"),id);
  var parts=com.sora.splineroads.net.RoadWire.split(payload);var assembler=new com.sora.splineroads.net.RoadWire.Assembler();CompoundTag decoded=null;
  for(var part:parts){var tag=assembler.accept(part,System.nanoTime());if(tag!=null)decoded=tag;}
  if(!payload.equals(decoded))throw new AssertionError("SPUI wire differs");
  System.out.println("SR211 SPUI PAYLOAD parts="+parts.size()+" shift="+player.isShiftKeyDown()+" kind="+payload.getString("Kind"));
  SplineRoads.INTERCHANGE.get().use(player.serverLevel(),player,InteractionHand.MAIN_HAND);
  return "SPUI picked="+road.record.id()+" assembly="+road.record.assembly();
 }
 public static String update(ServerLevel level){
  var data=RoadData.get(level);var id=UUID.fromString("9a6726c7-fc4e-463f-a482-f635a46da7ee");var saved=data.interchanges.get(id);
  var command=Interchanges.payload(level,null,saved.getLongArray("Points"),id);
  try{NbtIo.writeCompressed(command,new java.io.File("followup211/single-editor.nbt"));}catch(Exception e){throw new RuntimeException(e);}
  Interchanges.build(level,null,command);return "EXISTING SINGLE UPDATE PASS";
 }
 public static CompoundTag rebuild(ServerLevel level,boolean single,String style){
  var data=RoadData.get(level);var id=UUID.fromString(single?"9a6726c7-fc4e-463f-a482-f635a46da7ee":"54526339-ef86-49ab-9dc3-afc53de4272e");var saved=data.interchanges.get(id);
  var command=Interchanges.payload(level,null,saved.getLongArray("Points"),id);command.getCompound("Options").putString("TrumpetStyle",style);
  Interchanges.build(level,null,command);var result=Interchanges.payload(level,null,data.interchanges.get(id).getLongArray("Points"),id);
  if(!result.getCompound("Options").getString("TrumpetStyle").equals(style))throw new AssertionError("style not persisted");return result;
 }
 public static void endpoint(ServerPlayer player){
  var d=RoadData.get(player.serverLevel());player.teleportTo(-20,62,559);
  RoadTool.openRoad(player,d.index.roads.get(UUID.fromString("b7017daa-18ee-417d-9fab-e96728670cf3")).record,false);
 }
 public static CompoundTag curveChoice(ServerLevel level){
  var id=UUID.fromString("54526339-ef86-49ab-9dc3-afc53de4272e");var saved=RoadData.get(level).interchanges.get(id);var t=Interchanges.payload(level,null,saved.getLongArray("Points"),id);
  var axes=new java.util.ArrayList<com.sora.splineroads.core.RoadAxis>();
  for(var axis:HostAxes.read(t)){var a=axis.at(0);var b=axis.at(axis.length());var middle=a.add(b).mul(.5).add(b.sub(a).horizontalUnit().left().mul(15));axes.add(new com.sora.splineroads.core.RoadAxis(java.util.List.of(a,middle,b)));}
  HostAxes.write(t,axes);var o=t.getCompound("Options");o.putString("Preset","DIAMOND");return t;
 }

}
