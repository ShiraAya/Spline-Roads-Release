package com.sora.splineroads.client;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.world.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class RoadSplitterClient {
  private static RoadSplitter.Hit hit;private static UUID token;private static String status="";private static long request,stableSince,sent;private static boolean wanted;private static Object world;
  private static List<RoadSplitGeometry.Range> ranges=List.of(),cachedRanges=List.of();private static RoadRecord rangeRoad;private static Mesh ownPreview;
  private static boolean holding(){var p=Minecraft.getInstance().player;return p!=null&&(p.getMainHandItem().getItem() instanceof RoadSplitTool||p.getOffhandItem().getItem() instanceof RoadSplitTool);}
  public static void clear(){if(ownPreview!=null&&ClientRoads.preview==ownPreview){ClientRoads.preview=null;ClientRoads.nodePreviews=List.of();ClientRoads.conflictPreview=false;ClientRoads.error="";}ownPreview=null;hit=null;token=null;status="";wanted=false;sent=0;request++;ranges=List.of();}
  public static void activate(){var mc=Minecraft.getInstance();if(mc.player==null)return;
    if(mc.player.isShiftKeyDown()){clear();var t=new CompoundTag();t.putString("Action","splitCancel");send(t);mc.player.displayClientMessage(Component.literal("已取消分割"),true);return;}
    if(hit==null){mc.setScreen(new RoadSplitScreen());return;}if(wanted){if(token!=null)commit();return;}ClientRoads.beginWorldEdit();wanted=true;preview();
  }
  private static void send(CompoundTag tag){ClientRoads.sendAction(tag);}
  private static void preview(){if(hit==null)return;var t=new CompoundTag();t.putString("Action","splitPreview");t.putUUID("Id",hit.road().record.id());t.putInt("Signature",hit.road().record.header().hashCode());t.putDouble("Station",hit.station());t.putString("Mode",RoadClientConfig.SPLIT_CONNECTION.get().name());t.putLong("Request",++request);sent=System.nanoTime();status="正在复核分割位置…";send(t);}
  public static void checked(CompoundTag reply){var mc=Minecraft.getInstance();if(reply.getLong("Request")!=request||mc.level==null||!mc.level.dimension().location().toString().equals(reply.getString("Dimension")))return;
    if(reply.contains("Error")){status=reply.getString("Error");wanted=false;token=null;return;}token=reply.getUUID("Token");var paths=reply.getList("PreviewRoads",net.minecraft.nbt.Tag.TAG_COMPOUND).stream().map(t->RoadRecord.load((CompoundTag)t).mesh()).toList();if(!paths.isEmpty()){ClientRoads.nodePreviews=paths;ownPreview=paths.get(0);ClientRoads.preview=ownPreview;ClientRoads.conflictPreview=false;ClientRoads.error="";}status="位置已锁定 · 再次右键分割 · Shift＋右键取消";
  }
  private static void commit(){if(token==null)return;var t=new CompoundTag();t.putString("Action","splitCommit");t.putUUID("Token",token);send(t);clear();status="正在提交分割…";}
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){if(event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();if(world!=mc.level){clear();rangeRoad=null;cachedRanges=List.of();world=mc.level;}if(!holding()||mc.screen!=null||mc.level==null){if(hit!=null)clear();return;}
    if(wanted)return; // A click owns this station until commit/cancel, independent of the cursor.
    var eye=mc.player.getEyePosition();var look=mc.player.getLookAngle();var origin=new V(eye.x,eye.y,eye.z);var direction=new V(look.x,look.y,look.z);var next=RoadSplitter.hit(ClientRoads.INDEX.rayCandidates(origin,direction,64).stream().filter(r->ClientRoads.active(r.record.id())).toList(),origin,direction,64);
    boolean same=next!=null&&hit!=null&&next.road().record==hit.road().record&&Math.abs(next.station()-hit.station())<.005;
    if(!same){clear();hit=next;stableSince=System.nanoTime();if(hit!=null){if(rangeRoad!=hit.road().record){rangeRoad=hit.road().record;cachedRanges=RoadSplitGeometry.protection(rangeRoad);}ranges=cachedRanges;try{RoadSplitGeometry.requireClear(ranges,hit.station(),hit.station());status="右键锁定此位置";}catch(IllegalArgumentException ex){status=ex.getMessage();}}}
    // No expensive world transaction is prepared merely by hovering over a road.
  }
  @SubscribeEvent public static void overlay(RenderGuiOverlayEvent.Post event){var mc=Minecraft.getInstance();if(!holding()||hit==null||mc.screen!=null||!event.getOverlay().id().toString().equals("minecraft:crosshair"))return;var g=event.getGuiGraphics();int x=12,y=mc.getWindow().getGuiScaledHeight()-62;
    String label=RoadClientConfig.SPLIT_CONNECTION.get()==RoadSlicing.Connection.CONTINUOUS?"连续":"路口";g.drawString(mc.font,String.format(Locale.ROOT,"分割 %s · %s · A–C %.2f / C–B %.2f 格",hit.road().record.id().toString().substring(0,8),label,hit.station(),hit.road().mesh.length()-hit.station()),x,y,0xffffff,true);g.drawWordWrap(mc.font,Component.literal(status),x,y+15,mc.getWindow().getGuiScaledWidth()-24,token==null?0xffcb81:0x82ffb0);
  }
  @SubscribeEvent public static void render(RenderLevelStageEvent event){if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES||hit==null||!holding())return;var mc=Minecraft.getInstance();var pose=event.getPoseStack();var camera=event.getCamera().getPosition();pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);var buffers=mc.renderBuffers().bufferSource();var lines=buffers.getBuffer(RenderType.lines());
    var mesh=hit.road().mesh;var at=RoadSlicing.at(mesh,hit.station());line(pose,lines,at.at(-at.halfWidth(),-.08),at.at(at.halfWidth(),-.08),token==null?1:.2f,token==null?.35f:1,.2f);var node=RoadSplitGeometry.node(mesh,hit.station()).position();LevelRenderer.renderLineBox(pose,lines,node.x()-.15,node.y()+.03,node.z()-.15,node.x()+.15,node.y()+.5,node.z()+.15,.2f,1,.3f,1);
    for(var range:ranges)if(range.intersects(hit.station(),hit.station()))for(double d:new double[]{Math.max(0,range.from()),Math.min(mesh.length(),range.to())}){var end=RoadSlicing.at(mesh,d);line(pose,lines,end.at(-end.halfWidth(),-.1),end.at(end.halfWidth(),-.1),1,.1f,.1f);}
    pose.popPose();buffers.endBatch(RenderType.lines());
  }
  private static void line(com.mojang.blaze3d.vertex.PoseStack pose,com.mojang.blaze3d.vertex.VertexConsumer out,V a,V b,float r,float g,float blue){V normal=b.sub(a).horizontalUnit();for(var p:List.of(a,b))out.vertex(pose.last().pose(),(float)p.x(),(float)p.y(),(float)p.z()).color(r,g,blue,1).normal(pose.last().normal(),(float)normal.x(),(float)normal.y(),(float)normal.z()).endVertex();}
  private RoadSplitterClient(){}
}
