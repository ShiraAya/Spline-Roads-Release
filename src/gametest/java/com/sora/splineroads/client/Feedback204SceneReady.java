package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.world.Feedback204VisualScene.BridgeCase;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Diagnostic test harness only: stable actual visible uploads, same-camera screenshots. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Feedback204SceneReady {
  private static BridgeCase target;private static String stableKey="",detail="unrequested";
  private static int stableFrames,srFrames;private static boolean ready;
  private static String srKey="";
  private static final Map<Object,Long> generations=new IdentityHashMap<>();
  private static final Set<String> diagnosticShots=new HashSet<>();
  private static long generation(Object value){return value==null?0:generations.computeIfAbsent(value,k->(long)generations.size()+1);}
  private record Vanilla(boolean ready,String key,String detail){}
  private static Vanilla vanilla(Minecraft mc,BridgeCase c)throws Exception{
    var eye=mc.gameRenderer.getMainCamera().getPosition();
    double minX=Math.min(c.x()-16,eye.x)-16,maxX=Math.max(c.x()+16,eye.x)+16;
    double minZ=Math.min(c.z(),eye.z)-16,maxZ=Math.max(c.z(),eye.z)+16;
    // Include the ground seen directly behind/below the near cap in this flat test world.
    // FULL chunks under the cap alone omit the longer camera-to-ground projection.
    double dy=c.deck()-.8-eye.y;
    if(dy<-.1){double scale=(c.ground()-.01-eye.y)/dy;
      if(scale>0&&scale<8)for(double x:new double[]{c.x()-16,c.x()+16})for(double z:new double[]{c.z()-1,c.z()+1}){
        double gx=eye.x+(x-eye.x)*scale,gz=eye.z+(z-eye.z)*scale;
        minX=Math.min(minX,gx-16);maxX=Math.max(maxX,gx+16);minZ=Math.min(minZ,gz-16);maxZ=Math.max(maxZ,gz+16);
      }
    }
    var visible=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
    for(Object info:(Collection<?>)field(mc.levelRenderer,"renderChunksInFrustum"))visible.add(field(info,"chunk"));
    var area=(net.minecraft.client.renderer.ViewArea)field(mc.levelRenderer,"viewArea");
    var tokens=new ArrayList<String>();int required=0,ground=0,uncompiled=0,dirty=0,excluded=0;
    int groundY=(int)Math.floor(c.ground()-1)>>4,deckY=(int)Math.floor(c.deck())>>4;
    for(var chunk:area.chunks){var origin=chunk.getOrigin();int y=origin.getY()>>4;
      if(y<groundY||y>deckY||origin.getX()+16<minX||origin.getX()>maxX||origin.getZ()+16<minZ||origin.getZ()>maxZ)continue;
      if(!visible.contains(chunk)){excluded++;continue;}
      required++;if(y==groundY)ground++;
      var compiled=chunk.getCompiledChunk();boolean initial=compiled==net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk.UNCOMPILED;
      if(initial)uncompiled++;if(chunk.isDirty())dirty++;
      tokens.add(origin+":gen="+generation(compiled)+" task="+generation(field(chunk,"lastRebuildTask"))+" uncompiled="+initial+" dirty="+chunk.isDirty());
    }
    Collections.sort(tokens);var dispatcher=mc.levelRenderer.getChunkRenderDispatcher();
    int expected=Math.max(1,Math.min(mc.is64Bit()?Runtime.getRuntime().availableProcessors():Math.min(Runtime.getRuntime().availableProcessors(),4),
      Math.max(1,(int)(Runtime.getRuntime().maxMemory()*.3)/(net.minecraft.client.renderer.RenderType.chunkBufferLayers().stream().mapToInt(net.minecraft.client.renderer.RenderType::bufferSize).sum()*4)-1)));
    int free=dispatcher.getFreeBufferCount(),queued=dispatcher.getToBatchCount(),uploads=dispatcher.getToUpload();
    Object mailbox=field(dispatcher,"mailbox");int mailboxStatus=((java.util.concurrent.atomic.AtomicInteger)field(mailbox,"status")).get();
    int mailboxSize=((net.minecraft.util.thread.ProcessorMailbox<?>)mailbox).size();
    // Each running asynchronous compile retains one builder through GPU upload completion.
    // Equality to the constructor's allocation upper bound proves no such worker remains.
    // OOM-reduced allocation fails closed; never infer idle merely from the highest observed free count.
    boolean idle=free==expected&&queued==0&&uploads==0&&mailboxStatus==0&&mailboxSize==0;
    boolean valid=required>0&&ground>0&&uncompiled==0&&dirty==0&&idle;
    String info="vanillaRequired="+required+" ground="+ground+" excludedInvisible="+excluded+" uncompiled="+uncompiled+" dirty="+dirty
      +" poolFree="+free+"/"+expected+" occupiedOrReduced="+(expected-free)+" queued="+queued+" upload="+uploads
      +" mailbox="+mailboxStatus+"/"+mailboxSize+" queueOnlyEmpty="+mc.levelRenderer.hasRenderedAllChunks()+" sections="+tokens;
    return new Vanilla(valid,tokens.toString(),info);
  }
  private static void diagnostic(Minecraft mc,BridgeCase c,String camera,String phase,String state)throws Exception{
    if(mc.screen!=null)return;String key=c.id()+" "+camera+" "+phase;if(!diagnosticShots.add(key))return;
    var out=java.nio.file.Path.of(System.getProperty("road.feedback204Output","feedback204"),"terrain-readiness");java.nio.file.Files.createDirectories(out);
    String name=c.name()+"-"+Integer.toHexString(camera.hashCode())+"-"+phase;
    try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(out.resolve(name+".png"));}
    java.nio.file.Files.writeString(out.resolve("results.txt"),name+" camera="+camera+" "+state+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
  }
  private static Object field(Object value,String name)throws Exception{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
  private static Object stat(String name)throws Exception{var f=RoadRenderer.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
  static boolean ready(BridgeCase value){
    if(target==null||!target.id().equals(value.id())){target=value;stableFrames=0;stableKey="";ready=false;}
    return ready;
  }
  static String detail(){return detail;}
  @SubscribeEvent public static void frame(RenderLevelStageEvent event){
    if(!(Boolean.getBoolean("road.feedback204Visual")||Boolean.getBoolean("road.feedback204TerrainProbe"))||target==null||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
    var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
    try{
      var c=target;var tokens=new ArrayList<String>();boolean valid=true;int count=0,visibleCount=0,unloadedVisible=0;
      valid&=!((Map<?,?>)stat("jobs")).containsKey(c.id());
      Object pending=stat("pendingUpload");Object pendingSection=pending==null?null:field(pending,"section");
      for(Object section:(Collection<?>)stat("visible")){
        var pieces=(Map<?,?>)field(section,"pieces");Object piece=pieces.get(c.id());if(piece==null)continue;
        visibleCount++;long wanted=((Number)field(piece,"identity")).longValue();
        Object actual=((Map<?,?>)field(section,"uploadedSources")).get(c.id());
        boolean matched=actual instanceof Number n&&n.longValue()==wanted;
        boolean dirty=(Boolean)field(section,"dirty");
        var region=(RoadRenderMesh.Section)field(section,"key");
        // Renderer visibility deliberately has a +32-block conservative fringe.
        // uploadVisible cannot upload a region with no actual FULL terrain chunk.
        // Keep that distant fringe observable, without waiting for impossible work.
        boolean loadedRegion=RoadTerrainModels.regionLoaded(region);
        tokens.add(region+":"+wanted+"/"+actual+" dirty="+dirty+" pending="+(section==pendingSection)+" loaded="+loadedRegion);
        if(!loadedRegion){unloadedVisible++;continue;}
        count++;valid&=matched&&!dirty&&section!=pendingSection;
      }
      valid&=count>0;Collections.sort(tokens);
      // Only the near-end/camera corridor needed for this observation, not the
      // invisible far end beyond the configured render-distance horizon.
      var p=mc.player.position();int x0=(int)Math.floor(Math.min(c.x()-16,p.x)-16)>>4,x1=(int)Math.floor(Math.max(c.x()+16,p.x)+16)>>4;
      int z0=(int)Math.floor(Math.min(c.z(),p.z)-16)>>4,z1=(int)Math.floor(Math.max(c.z(),p.z)+16)>>4;
      int loaded=0,total=0;for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++){
        total++;boolean present=mc.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false)!=null;if(present)loaded++;else valid=false;
      }
      String camera=p+"/"+mc.player.getYRot()+"/"+mc.player.getXRot();
      String roadKey=c.id()+" "+camera+" "+tokens;
      if(!valid||!roadKey.equals(srKey))srFrames=0;else srFrames++;srKey=roadKey;
      var terrain=vanilla(mc,c);
      if(valid&&srFrames>=20&&!terrain.ready())diagnostic(mc,c,camera,"before-vanilla-ready",terrain.detail());
      valid&=terrain.ready();
      String key=roadKey+" "+terrain.key();
      if(!valid||!key.equals(stableKey))stableFrames=0;else stableFrames++;
      stableKey=key;ready=valid&&stableFrames>=20;
      var node=BlockPos.containing(c.x(),c.deck(),c.z());
      detail="frames="+stableFrames+" ready="+ready+" visible="+visibleCount+" required="+count+" unloadedVisible="+unloadedVisible+" FULL="+loaded+"/"+total+" camera="+camera+" node="+mc.level.getBlockState(node)+" hand="+mc.player.getMainHandItem()+" error="+ClientRoads.error+" sections="+tokens+" "+terrain.detail();
      if(ready)diagnostic(mc,c,camera,"after-vanilla-ready",detail);
    }catch(Exception failure){stableFrames=0;ready=false;detail="readiness reflection failure: "+failure;}
  }
  private Feedback204SceneReady(){}
}
