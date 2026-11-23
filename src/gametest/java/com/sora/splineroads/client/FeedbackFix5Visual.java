package com.sora.splineroads.client;

import java.nio.file.*;
import java.util.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Actual network submit while decoration preview is busy, followed by visual QA. */
@Mod.EventBusSubscriber(modid="splineroads",value=Dist.CLIENT)
public final class FeedbackFix5Visual {
 static final Path OUT=Path.of("visual-fix5");
 static final BlockPos A=new BlockPos(6000,56,6000),B=new BlockPos(6000,56,6600);
 static boolean started,done;static int ticks,stage,ready,shot;static long deadline,submitted;
 static volatile Throwable error;static volatile boolean built;static volatile UUID builtId;
 static final String[] NAMES={"02-turbine-side","03-turbine-crossing","04-turbine-top","05-stack-side","06-stack-top"};
 static final double[][] CAMERAS={{148,92,1708,0,74,1800},{71,89,1918,0,72,1790},{0,320,1780,0,56,1781},{4148,96,3930,4000,70,4000},{4000,320,4000,4000,56,4001}};
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 static void log(String s)throws Exception{System.out.println("FIX5_CLIENT "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
 static Object field(Class<?> type,String name)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
  if(!Boolean.getBoolean("road.feedbackFix5Client")||done||event.phase!=TickEvent.Phase.END)return;
  var mc=Minecraft.getInstance();ticks++;long now=System.nanoTime();
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=now+900_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(24);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.hideGui=false;if(Boolean.getBoolean("road.feedbackFix5Shots")){stage=5;mc.options.hideGui=true;}mc.createWorldOpenFlows().loadLevel(mc.screen,"world");return;}
   if(now>deadline)throw new AssertionError("timeout stage="+stage);if(error!=null)throw new AssertionError("server action",error);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);
   if(ticks%100==0)log("stage="+stage+" shot="+shot+" error="+ClientRoads.error);
   if(ticks<ready)return;
   if(stage==0){org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1280,720);mc.setScreen(null);FeedbackFix3Visual.camera(mc,6000,65,5980,6000,56,6100);
    mc.getSingleplayerServer().execute(()->{try{var server=mc.getSingleplayerServer();var level=server.overworld();level.setDayTime(6000);level.setWeatherParameters(12000,0,false,false);var p=server.getPlayerList().getPlayer(mc.player.getUUID());server.getPlayerList().op(p.getGameProfile());p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(SplineRoads.CONNECTOR.get()));p.getMainHandItem().getOrCreateTag().putLong("Start",A.asLong());p.getMainHandItem().getOrCreateTag().putString("Dimension",level.dimension().location().toString());
     for(var pos:List.of(A,B)){level.setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),18);var node=(NodeEntity)level.getBlockEntity(pos);node.owner=p.getUUID();node.heightExplicit=true;node.offsetY=0;node.setChanged();}
    }catch(Throwable failure){error=failure;}});stage=1;ready=ticks+200;return;}
   if(stage==1){mc.getSingleplayerServer().execute(()->{try{var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());var level=p.serverLevel();var data=RoadData.get(level);var t=new CompoundTag();t.putString("Kind","road");t.putLong("A",A.asLong());t.putLong("B",B.asLong());t.put("StartNode",RoadRecord.writeNode(data.connectionNode(level,A,true,null,((NodeEntity)level.getBlockEntity(B)).constructionNode().position())));t.put("EndNode",RoadRecord.writeNode(data.connectionNode(level,B,false,null,((NodeEntity)level.getBlockEntity(A)).constructionNode().position())));t.put("Settings",RoadRecord.writeSettings(new Settings(Mode.STRAIGHT,Style.O6_GREEN,28,1,.4,90)));RoadNetwork.open(p,t);}catch(Throwable failure){error=failure;}});stage=2;return;}
   if(stage==2){if(!(mc.screen instanceof RoadScreen screen)||ClientRoads.preview==null)return;
    if(!(Boolean)field(RoadRenderer.class,"furnitureRunning"))return;
    var button=screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("建造 / 保存")).map(w->(Button)w).findFirst().orElseThrow();
    check(button.active,"busy preview disabled build");check(ClientRoads.error.isEmpty(),"progress treated as error");
    // State controls supplement the real busy task; they do not replace it.
    screen.furnitureStatus("test invalid facility",true);check(!button.active,"real error did not block");screen.furnitureStatus("");
    RoadRenderer.previewFurnitureStatus(ClientRoads.preview);check(button.active,"cleared error stayed blocked");
    shot(mc,"01-preview-busy-build-enabled");log("BUSY_SAVE_PASS previewSamples="+ClientRoads.preview.samples().size()+" status="+field(RoadRenderer.class,"furnitureFailure"));
    submitted=now;button.onPress();check(!button.active,"pending request stayed clickable");stage=3;return;}
   if(stage==3){if(mc.screen!=null){check(now-submitted<15_000_000_000L,"pending receipt did not close editor");return;}log("PENDING_CLOSED_EDITOR elapsedMs="+(now-submitted)/1e6);stage=4;return;}
   if(stage==4){mc.getSingleplayerServer().execute(()->{try{var data=RoadData.get(mc.getSingleplayerServer().overworld());for(var b:data.index.roads.values())if(b.record.a().equals(A)&&b.record.b().equals(B)){builtId=b.record.id();built=true;}}catch(Throwable failure){error=failure;}});if(!built)return;check(ClientRoads.error.isEmpty(),"server rejected build: "+ClientRoads.error);log("NETWORK_BUILD_PASS id="+builtId+" elapsedMs="+(now-submitted)/1e6);mc.options.hideGui=true;stage=5;}
   if(stage==5){if(shot>0){shot(mc,NAMES[shot-1]);check(ClientRoads.error.isBlank(),"visual error: "+ClientRoads.error);}if(shot==CAMERAS.length){log("PASS");done=true;mc.stop();return;}var c=CAMERAS[shot++];mc.setScreen(null);Feedback212Visual.camera(mc,c[0],c[1],c[2],c[3],c[4],c[5]);ready=ticks+320;}
  }catch(Throwable failure){done=true;failure.printStackTrace();try{log("FAIL "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
