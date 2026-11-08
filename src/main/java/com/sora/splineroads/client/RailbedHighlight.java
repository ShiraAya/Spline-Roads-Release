package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.world.RailbedTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class RailbedHighlight {
  @SubscribeEvent public static void render(RenderLevelStageEvent event){
    if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;var mc=Minecraft.getInstance();if(mc.player==null)return;
    if(!(mc.player.getMainHandItem().getItem() instanceof RailbedTool||mc.player.getOffhandItem().getItem() instanceof RailbedTool))return;
    var paths=new LinkedHashMap<String,RailbedPath.Path>();RailbedScreen.candidates.forEach(p->paths.put(RailbedCandidates.key(p),p));var selected=new HashSet<String>();RailbedScreen.highlighted.forEach(p->{paths.put(RailbedCandidates.key(p),p);selected.add(RailbedCandidates.key(p));});
    var camera=event.getCamera().getPosition();var pose=event.getPoseStack();pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);var buffers=mc.renderBuffers().bufferSource();var line=buffers.getBuffer(RenderType.lines());
    for(var entry:paths.entrySet()){boolean active=selected.contains(entry.getKey());var points=entry.getValue().positions();for(int i=1;i<points.size();i++){var a=points.get(i-1);var b=points.get(i);if(camera.distanceToSqr(a.x(),a.y(),a.z())>128*128&&camera.distanceToSqr(b.x(),b.y(),b.z())>128*128)continue;var n=b.sub(a).horizontalUnit();
      for(var p:List.of(a,b))line.vertex(pose.last().pose(),(float)p.x(),(float)p.y()+.2f,(float)p.z()).color(active?.15f:1f,active?1f:.7f,active?.3f:.15f,1f).normal(pose.last().normal(),(float)n.x(),0,(float)n.z()).endVertex();
    }}pose.popPose();buffers.endBatch(RenderType.lines());
  }
  @SubscribeEvent public static void logout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e){RailbedScreen.reset();}
  private RailbedHighlight(){}
}
