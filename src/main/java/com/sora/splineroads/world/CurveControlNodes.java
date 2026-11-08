package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;

/** Selection receipts stay on the tool; marker retirement shares the road transaction. */
public final class CurveControlNodes {
  public static final String KEY="CurveControlNodes";
  public static String add(ServerLevel level,ServerPlayer player,ItemStack tool,BlockPos pos){
    return add(level,player,tool,pos,false);
  }
  static String add(ServerLevel level,ServerPlayer player,ItemStack tool,BlockPos pos,boolean map){
    var tag=tool.getOrCreateTag();
    if(!tag.contains("Start")||!level.dimension().location().toString().equals(tag.getString("Dimension")))throw new IllegalArgumentException("请先普通右键选择起点，再用左 Alt＋右键添加控制点");
    if(pos.asLong()==tag.getLong("Start"))throw new IllegalArgumentException("起点不能同时作为控制点");
    if(!map&&player.distanceToSqr(pos.getCenter())>64)throw new IllegalArgumentException("控制点离玩家太远");
    var marker=RoadData.requireNode(level,pos,player);
    if(RoadData.get(level).linked(pos))throw new IllegalArgumentException("已有道路使用的端点不能转换为曲线控制点");
    var list=tag.getList(KEY,Tag.TAG_COMPOUND);
    if(list.size()>=32)throw new IllegalArgumentException("最多添加32个控制点");
    for(var entry:list)if(((CompoundTag)entry).getLong("Pos")==pos.asLong())throw new IllegalArgumentException("这个控制点已选中");
    var entry=new CompoundTag();entry.putLong("Pos",pos.asLong());entry.putUUID("Id",UUID.randomUUID());entry.put("Node",RoadRecord.writeNode(marker.constructionNode()));list.add(entry);tag.put(KEY,list);
    return map?"已添加"+(tag.getBoolean("CurveThrough")?"经过点 ":"控制点 ")+list.size()+"；继续加点或选择终点 B":"已添加控制点 "+list.size()+"；继续左 Alt＋右键添加，普通右键选择终点";
  }
  public static List<RoadProfile.Control> controls(CompoundTag tool){
    var out=new ArrayList<RoadProfile.Control>();
    for(var value:tool.getList(KEY,Tag.TAG_COMPOUND)){var c=(CompoundTag)value;out.add(new RoadProfile.Control(c.getUUID("Id"),RoadRecord.readNode(c.getCompound("Node")).position()));}
    return List.copyOf(out);
  }
  public static List<BlockPos> validate(ServerLevel level,ServerPlayer player,CompoundTag tool,Settings settings,BlockPos a,BlockPos b){
    var selected=tool.getList(KEY,Tag.TAG_COMPOUND);var controls=settings.options().routing().controls();
    if(!controls.isEmpty()&&settings.options().routing().through()!=tool.getBoolean("CurveThrough"))throw new IllegalArgumentException("经过点/控制点类型与当前选择不一致");
    if(selected.size()!=controls.size())throw new IllegalArgumentException("控制点选择已失效，请重新选择");
    var out=new ArrayList<BlockPos>();
    for(int i=0;i<selected.size();i++){
      var c=selected.getCompound(i);var pos=BlockPos.of(c.getLong("Pos"));var node=RoadData.requireNode(level,pos,player);
      if(!c.getUUID("Id").equals(controls.get(i).id())||!node.constructionNode().equals(RoadRecord.readNode(c.getCompound("Node")))||pos.equals(a)||pos.equals(b)||out.contains(pos)||RoadData.get(level).linked(pos))throw new IllegalArgumentException("控制端点已移动、被使用或重复，请重新选择");
      out.add(pos);
    }
    return List.copyOf(out);
  }
  public static RoadAttachments.Data attachments(RoadAttachments.Data old,List<RoadProfile.Control> before,List<RoadProfile.Control> after){
    var ids=new HashSet<UUID>();for(var c:before)ids.add(c.id());for(var c:after)ids.add(c.id());
    var points=new ArrayList<RoadAttachments.Point>();for(var p:old.points())if(!ids.contains(p.id()))points.add(p);
    return old.points(points);
  }
  private CurveControlNodes(){}
}
