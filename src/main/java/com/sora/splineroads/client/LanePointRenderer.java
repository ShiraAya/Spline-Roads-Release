package com.sora.splineroads.client;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.*;
import com.sora.splineroads.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class LanePointRenderer {
  static boolean accepts(ItemStack s){return s.getItem() instanceof LanePointTool||s.getItem() instanceof LaneRampTool;}
  @SubscribeEvent public static void render(RenderLevelStageEvent e){if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;var mc=Minecraft.getInstance();if(mc.player==null||!(accepts(mc.player.getMainHandItem())||accepts(mc.player.getOffhandItem())))return;var camera=e.getCamera().getPosition();var pose=e.getPoseStack();var buffer=mc.renderBuffers().bufferSource();var lines=buffer.getBuffer(RenderType.lines());pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
    for(var road:ClientToolIndex.near(camera.x,camera.z,true))if(!road.record.railway()&&ClientRoads.active(road.record.id()))for(var p:LaneTopology.visiblePoints(road.record)){var v=p.position();if(camera.distanceToSqr(v.x(),v.y(),v.z())>96*96)continue;LevelRenderer.renderLineBox(pose,lines,new AABB(v.x()-.24,v.y()+.04,v.z()-.24,v.x()+.24,v.y()+.53,v.z()+.24),.12f,.45f,1f,1f);var d=LanePoints.lane(road.mesh,p).direction();var tip=v.add(d.mul(.9));var rear=v.add(d.mul(.45));for(var end:java.util.List.of(v,rear.add(d.left().mul(.22)),rear.sub(d.left().mul(.22)))){var a=tip.add(new com.sora.splineroads.core.RoadGeometry.V(0,.12,0));var b=end.add(new com.sora.splineroads.core.RoadGeometry.V(0,.12,0));var n=b.sub(a).horizontalUnit();lines.vertex(pose.last().pose(),(float)a.x(),(float)a.y(),(float)a.z()).color(.12f,.55f,1f,1f).normal(pose.last().normal(),(float)n.x(),0,(float)n.z()).endVertex();lines.vertex(pose.last().pose(),(float)b.x(),(float)b.y(),(float)b.z()).color(.12f,.55f,1f,1f).normal(pose.last().normal(),(float)n.x(),0,(float)n.z()).endVertex();}}
    pose.popPose();buffer.endBatch(RenderType.lines());}
}
