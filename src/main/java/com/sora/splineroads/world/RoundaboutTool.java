package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.JunctionSpec.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
public final class RoundaboutTool extends Item {
 public RoundaboutTool(){super(new Properties().stacksTo(1));}
 @Override public InteractionResult useOn(UseOnContext c){if(c.getLevel().isClientSide)return InteractionResult.SUCCESS;if(!(c.getPlayer() instanceof ServerPlayer p))return InteractionResult.PASS;try(var work=RoadWorkChunks.open(p.serverLevel())){
   BlockPos pos=RoadTool.endpointAtHit(c);var data=RoadData.get(p.serverLevel());var ring=AutoJunctions.center(data,pos);if(ring==null)ring=AutoJunctions.ringAt(data,pos);
   if(ring!=null){RoadNetwork.open(p,Junctions.payload(p.serverLevel(),p,new long[0],ring.getUUID("Id")));return InteractionResult.CONSUME;}
   NodeEntity node=RoadData.requireNode(p.serverLevel(),pos,p);if(data.linked(pos))throw new IllegalArgumentException("请选择空闲端点作为环岛圆心");var tool=c.getItemInHand().getOrCreateTag();tool.putLong("Center",pos.asLong());tool.putString("Dimension",p.level().dimension().location().toString());
   CompoundTag t=new CompoundTag();t.putString("Kind","roundabout");t.putLong("Center",pos.asLong());t.put("Node",RoadRecord.writeNode(node.constructionNode()));t.putDouble("Radius",20);t.putInt("Lanes",1);RoadNetwork.open(p,t);
 }catch(IllegalArgumentException e){if(ManualPlanner.inSession())throw e;p.displayClientMessage(Component.literal(e.getMessage()),false);}return InteractionResult.CONSUME;}
 @Override public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand h){if(!l.isClientSide&&p instanceof ServerPlayer s){var hit=RoadTool.pick(s);if(hit!=null&&hit.record.junction()!=null)RoadNetwork.open(s,Junctions.selectedPayload(s.serverLevel(),s,hit.record));}return InteractionResultHolder.sidedSuccess(p.getItemInHand(h),l.isClientSide);}
 public static JunctionSpec specification(CompoundTag t){double radius=t.getDouble("Radius");int lanes=t.getInt("Lanes");if(lanes<1||lanes>2||radius<10||radius>48)throw new IllegalArgumentException("环岛中心线半径 10–48 格，支持单／双车道");V center=RoadRecord.readNode(t.getCompound("Node")).position();return new JunctionSpec(center,Kind.ROUNDABOUT,t.getBoolean("LeftTraffic"),4,radius-lanes*2,lanes,4,1,Control.YIELD,20,3,1,0,true,true,t.getBoolean("OuterRail"),List.of());}
 public static List<Node> ports(JunctionSpec spec){double radius=spec.islandRadius()+spec.ringLanes()*spec.ringLaneWidth();var nodes=new ArrayList<Node>();int[] directions={0,4,6,2,7,1,5,3};for(int i:directions){double angle=-Math.PI/2+i*Math.PI/4;V out=new V(Math.cos(angle),0,Math.sin(angle));nodes.add(new Node(spec.center().add(out.mul(radius)),RoadPlanner.yaw(out),0));}return nodes;}
 public static String build(ServerLevel l,ServerPlayer p,CompoundTag t,ItemStack stack){try(var work=RoadWorkChunks.open(l)){
   if(!(stack.getItem() instanceof RoundaboutTool)||stack.getOrCreateTag().getLong("Center")!=t.getLong("Center")||!l.dimension().location().toString().equals(stack.getTag().getString("Dimension")))throw new IllegalArgumentException("请用环岛生成器重新选择圆心");BlockPos center=BlockPos.of(t.getLong("Center"));var node=RoadData.requireNode(l,center,p);if(RoadData.get(l).linked(center))throw new IllegalArgumentException("圆心已有道路");if(!node.constructionNode().equals(RoadRecord.readNode(t.getCompound("Node"))))throw new IllegalArgumentException("圆心已变化，请重新选择");var spec=specification(t);AutoJunctions.createRing(l,p,center,spec);stack.getOrCreateTag().remove("Center");return "环岛已生成；用道路连接器从 A–H 八个方向端点接路";
 }}
 @Override public void appendHoverText(ItemStack s,Level l,List<Component> lines,TooltipFlag f){lines.add(Component.literal("右键空闲端点作为圆心，设置半径及单双车道"));lines.add(Component.literal("生成东南西北及四个斜向接路点，用道路连接器接路"));}
}
