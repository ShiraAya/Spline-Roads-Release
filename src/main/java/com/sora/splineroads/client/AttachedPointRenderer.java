package com.sora.splineroads.client;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class AttachedPointRenderer {
  private static boolean accepts(ItemStack s){return s.getItem() instanceof RoadTool||s.getItem() instanceof ElevatedPointTool||s.getItem() instanceof AttachedPointTool||s.getItem() instanceof LaneLineTool||s.getItem() instanceof InterchangeTool;}
  @SubscribeEvent public static void render(RenderLevelStageEvent e){
    if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;var mc=Minecraft.getInstance();if(mc.player==null||LanePointRenderer.accepts(mc.player.getMainHandItem())||LanePointRenderer.accepts(mc.player.getOffhandItem())||!(accepts(mc.player.getMainHandItem())||accepts(mc.player.getOffhandItem())))return;
    var camera=e.getCamera().getPosition();var pose=e.getPoseStack();var buffer=mc.renderBuffers().bufferSource();var lines=buffer.getBuffer(RenderType.lines());
    pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
    for(var road:ClientToolIndex.near(camera.x,camera.z,false))if(ClientRoads.active(road.record.id()))for(var p:road.record.settings().options().attachments().points()){
      if(road.record.settings().options().routing().controls().stream().anyMatch(c->c.id().equals(p.id())))continue;var v=p.position();if(camera.distanceToSqr(v.x(),v.y(),v.z())>96*96)continue;
      LevelRenderer.renderLineBox(pose,lines,new AABB(v.x()-.22,v.y()+1.65,v.z()-.22,v.x()+.22,v.y()+2.15,v.z()+.22),p.elevated()?.2f:1f,p.elevated()?.8f:.12f,p.elevated()?1f:.12f,1f);
      LevelRenderer.renderLineBox(pose,lines,new AABB(v.x()-.025,v.y()+.05,v.z()-.025,v.x()+.025,v.y()+1.65,v.z()+.025),1f,.45f,.35f,1f);
    }pose.popPose();buffer.endBatch(RenderType.lines());
  }
}
