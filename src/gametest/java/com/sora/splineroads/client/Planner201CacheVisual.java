package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opt-in real native client; creates only a fresh isolated world. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Planner201CacheVisual {
  private static final Path OUT=Path.of("feedback201-cache");
  private static boolean started,done;private static int stage,ticks,ready,checks;
  private static long deadline,closedAt;private static CompletableFuture<?> pending;
  private static Object texture;private static UUID previousSession;private static int color,height;
  private static double anchorX,anchorZ;private static int wheelStep;private static boolean ctrlInjected;private static double wheelZoom;private static int focusedTicks;
  public interface NativeKeys extends com.sun.jna.win32.StdCallLibrary {
    void keybd_event(byte vk,byte scan,int flags,com.sun.jna.Pointer extra);
    com.sun.jna.Pointer GetForegroundWindow();
    short GetAsyncKeyState(int key);
  }
  private static final class WindowsInput {
    static final NativeKeys KEYS=com.sun.jna.Native.load("user32",NativeKeys.class);
  }
  private static void controlKey(boolean down){
    // Forge forces AWT headless, but the real GLFW window still consumes native key input.
    WindowsInput.KEYS.keybd_event((byte)0xA2,(byte)0x1D,down?0:2,com.sun.jna.Pointer.NULL);
    ctrlInjected=down;
  }
  private static boolean foreground(Minecraft mc){
    long expected=org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow());
    return com.sun.jna.Pointer.nativeValue(WindowsInput.KEYS.GetForegroundWindow())==expected
        &&mc.isWindowActive()&&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_FOCUSED)==org.lwjgl.glfw.GLFW.GLFW_TRUE;
  }
  private static String keyDiagnostic(Minecraft mc){return " own="+org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow())
      +" foreground="+com.sun.jna.Pointer.nativeValue(WindowsInput.KEYS.GetForegroundWindow())+" windowActive="+mc.isWindowActive()
      +" OSLeftCtrl="+Integer.toHexString(WindowsInput.KEYS.GetAsyncKeyState(0xA2)&0xffff)
      +" GLFWLeftCtrl="+org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL);}
  private static Object field(Object target,String name)throws Exception{var cls=target instanceof Class<?> c?c:target.getClass();var f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class<?>?null:target);}
  private static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  private static void next(int n,int delay){stage=n;ready=ticks+delay;}
  private static net.minecraft.server.level.ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
  private static net.minecraft.world.level.levelgen.WorldDimensions dimensions(net.minecraft.core.RegistryAccess r){var flat=net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(r.registryOrThrow(Registries.BIOME).asLookup(),r.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),r.registryOrThrow(Registries.PLACED_FEATURE).asLookup());return r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions().replaceOverworldGenerator(r,new net.minecraft.world.level.levelgen.FlatLevelSource(flat));}
  private static ManualPlannerScreen map(Minecraft mc){return mc.screen instanceof ManualPlannerScreen p?p:null;}
  private static void open(){KeyMapping.click(ManualPlannerClient.KEY.getKey());}
  private static void setting(ManualPlannerScreen map,int seconds){var t=new CompoundTag();t.putString("Action","manualSettings");t.putUUID("PlannerSession",map.session);t.putInt("Radius",32);t.putInt("MaxUndo",30);t.putInt("CacheSeconds",seconds);ClientRoads.sendAction(t);}
  private static void teleport(Minecraft mc,double x,double z){mc.getSingleplayerServer().execute(()->{var p=player(mc);p.teleportTo(p.serverLevel(),x,-25,z,0,90);});mc.player.setPos(x,-25,z);}
  private static int[] pixel(ManualPlannerScreen map,double x,double z)throws Exception{int ox=(Integer)field(map,"terrainX"),oz=(Integer)field(map,"terrainZ"),step=(Integer)field(map,"terrainStep"),px=((int)Math.floor(x)-ox)/step,pz=((int)Math.floor(z)-oz)/step;var texture=(net.minecraft.client.renderer.texture.DynamicTexture)field(map,"terrain");return new int[]{texture.getPixels().getPixelRGBA(px,pz),((int[])field(map,"heights"))[pz*1024+px]};}
  private static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}}
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
    if(!Boolean.getBoolean("road.planner201Cache")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;FabricBridgeProbe.main(new String[]{"false"});Files.createDirectories(OUT);deadline=System.nanoTime()+300_000_000_000L;mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.options.renderDistance().set(5);mc.options.framerateLimit().set(60);mc.resizeDisplay();mc.createWorldOpenFlows().createFreshLevel("sr201-cache-"+System.currentTimeMillis(),new LevelSettings("SR201 isolated cache",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(201L,false,false),Planner201CacheVisual::dimensions);return;}
      if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
      if(stage==17){if(mc.level!=null)return;ok(field(ManualPlannerClient.class,"retainedSurface")==null,"disconnect retained old world surface");Files.writeString(OUT.resolve("passed.txt"),"SR202 UI CACHE NATIVE PASS checks="+checks+"\nActual GL texture identity, cached pixel/height, plain wheel zoom, real Ctrl wheel four-mode cycle, slider hover screenshots, late packet suppression, real timed expiry, closed/open/child distance expiry and disconnect tested.\nScreenshots require inspection.\n");done=true;System.out.println("SR202 UI CACHE NATIVE PASS "+checks);mc.stop();return;}
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      if(stage==0){mc.setScreen(null);pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.teleportTo(p.serverLevel(),.5,-25,.5,0,90);p.getPersistentData().putInt("SRPlannerRadius",32);p.getPersistentData().putInt("SRPlannerCacheSeconds",60);return true;});next(1,40);return;}
      if(stage==1){if(!pending.isDone())return;pending.join();open();next(2,30);return;}
      if(stage==2){var map=map(mc);if(map==null||map.loading())return;anchorX=mc.player.getX();anchorZ=mc.player.getZ();var p=pixel(map,anchorX,anchorZ);if(p[1]==Integer.MIN_VALUE)return;color=p[0];height=p[1];texture=field(map,"terrain");previousSession=map.session;shot(mc,"01-original");ok(map.cacheSeconds()==60,"60 second setting not persisted");ManualPlannerClient.close();next(3,2);return;}
      if(stage==3){ok(!ManualPlannerClient.hasMap()&&mc.screen==null,"close retained editor");ok(field(ManualPlannerClient.class,"retainedSurface")!=null,"close failed to retain surface");var late=new CompoundTag();late.putString("Kind","manualTerrain");late.putUUID("PlannerSession",previousSession);late.putInt("ChunkX",0);late.putInt("ChunkZ",0);late.putIntArray("Colors",new int[256]);late.putIntArray("Heights",new int[256]);ManualPlannerClient.receive(late);ok(!ManualPlannerClient.hasMap(),"late terrain reopened map");open();next(4,2);return;}
      if(stage==4){var map=map(mc);if(map==null)return;ok(field(map,"terrain")==texture,"reopen did not reuse GL texture object");var p=pixel(map,anchorX,anchorZ);ok(p[0]==color&&p[1]==height,"reopen lost cached color/height");ok(field(ManualPlannerClient.class,"retainedSurface")==null,"surface has duplicate owner");shot(mc,"02-reopened");ManualPlannerClient.close();next(18,4);return;}
      if(stage==18){pending=mc.getSingleplayerServer().submit(()->{var level=player(mc).serverLevel();for(int x=(int)Math.floor(anchorX)-2;x<=(int)Math.floor(anchorX)+2;x++)for(int z=(int)Math.floor(anchorZ)-2;z<=(int)Math.floor(anchorZ)+2;z++)level.setBlock(new net.minecraft.core.BlockPos(x,height+3,z),net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(),3);return true;});next(19,4);return;}
      if(stage==19){if(!pending.isDone())return;pending.join();open();next(20,2);return;}
      if(stage==20){var map=map(mc);if(map==null||map.loading())return;var p=pixel(map,anchorX,anchorZ);if(p[1]!=height+4)return;ok(p[0]!=color,"new sampling did not replace cached color");ok(p[1]==height+4,"new sampling did not replace cached height");map.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_2,0,0);next(21,8);return;}
      if(stage==21){var map=map(mc);if(map==null||(Integer)field(map,"tool")!=2)return;ok((Integer)field(map,"endpointMode")==0,"initial endpoint mode");ok(!net.minecraft.client.gui.screens.Screen.hasControlDown(),"unexpected Ctrl before plain scroll");ok(RoadScreen.displayWidth(27.999999999999996).equals("28"),"total width exposes floating-point tail");ok(RoadScreen.displayWidth(27.125).equals("27.125"),"fractional width lost precision");wheelZoom=(Double)field(map,"zoom");map.mouseScrolled(200,160,1);ok((Double)field(map,"zoom")>wheelZoom,"plain wheel did not zoom");ok((Integer)field(map,"endpointMode")==0,"plain wheel changed endpoint type");wheelZoom=(Double)field(map,"zoom");wheelStep=0;focusedTicks=0;deadline=System.nanoTime()+600_000_000_000L;System.out.println("SR202 UI WAIT FOCUS: activate this test window after other client tests finish."+keyDiagnostic(mc));next(27,2);return;}
      if(stage==27){if(!foreground(mc)){focusedTicks=0;return;}if(++focusedTicks<2)return;System.out.println("SR202 UI FOREGROUND CONFIRMED"+keyDiagnostic(mc));controlKey(true);next(24,4);return;}
      if(stage==24){var map=map(mc);if(map==null)return;ok(net.minecraft.client.gui.screens.Screen.hasControlDown(),"real Ctrl key not seen by GLFW"+keyDiagnostic(mc));map.mouseScrolled(200,160,1);next(22,8);return;}
      if(stage==22){var map=map(mc);if(map==null)return;ok(net.minecraft.client.gui.screens.Screen.hasControlDown(),"Ctrl released during cycle");ok((Double)field(map,"zoom")==wheelZoom,"Ctrl wheel changed zoom");int expected=(wheelStep+1)%4;ok((Integer)field(map,"endpointMode")==expected,"wheel mode "+expected+" not reflected by UI");var state=(CompoundTag)field(map,"state");ok(state.getInt("EndpointMode")==expected,"wheel mode "+expected+" not confirmed by server");wheelStep++;if(wheelStep<4){map.mouseScrolled(200,160,1);next(22,8);return;}
        controlKey(false);var command=new CompoundTag();command.putString("Action","manualClick");command.putUUID("PlannerSession",map.session);command.putBoolean("Ctrl",true);command.putBoolean("Alt",true);command.putDouble("X",anchorX+8);command.putDouble("Y",-22);command.putDouble("Z",anchorZ+8);
        pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);var result=com.sora.splineroads.world.ManualPlanner.action(p,command);var pos=net.minecraft.core.BlockPos.containing(command.getDouble("X"),command.getDouble("Y"),command.getDouble("Z"));if(!(p.serverLevel().getBlockEntity(pos) instanceof com.sora.splineroads.world.NodeEntity node)||node.junctionSource!=null||!result.equals("已放置端点"))throw new AssertionError("Ctrl/Alt overrode selected endpoint mode on server");return true;});next(23,10);return;}
      if(stage==23){ok(!net.minecraft.client.gui.screens.Screen.hasControlDown(),"Ctrl remained stuck");if(!pending.isDone())return;pending.join();ok(true,"Ctrl/Alt retain endpoint creation");var map=map(mc);ok((Integer)field(map,"endpointMode")==0,"Ctrl/Alt changed UI mode");shot(mc,"04-endpoint-wheel");setting(map,1);next(5,20);return;}
      if(stage==5){var map=map(mc);if(map==null||map.loading()||map.cacheSeconds()!=1)return;ManualPlannerClient.close();closedAt=net.minecraft.Util.getMillis();next(6,1);return;}
      if(stage==6){if(net.minecraft.Util.getMillis()-closedAt<1250)return;ok(field(ManualPlannerClient.class,"retainedSurface")==null,"1 second expiry did not free surface");open();next(7,20);return;}
      if(stage==7){var map=map(mc);if(map==null||map.loading())return;setting(map,60);next(8,20);return;}
      if(stage==8){var map=map(mc);if(map==null||map.cacheSeconds()!=60)return;anchorX=mc.player.getX();anchorZ=mc.player.getZ();ManualPlannerClient.close();teleport(mc,anchorX+33,anchorZ);next(9,8);return;}
      if(stage==9){ok(field(ManualPlannerClient.class,"retainedSurface")==null,"closed distance expiry ignored");open();next(10,30);return;}
      if(stage==10){var map=map(mc);if(map==null||map.loading())return;texture=field(map,"terrain");anchorX=mc.player.getX();anchorZ=mc.player.getZ();mc.setScreen(map.new PlannerSettings());org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),8,8);teleport(mc,anchorX+33,anchorZ);next(11,10);return;}
      if(stage==11){shot(mc,"03-cache-settings-unhovered");var map=ManualPlannerClient.map;ok(map!=null&&field(map,"terrain")!=texture,"child-screen distance expiry retained old GL texture");ok((Boolean)field(ManualPlannerClient.class,"openedRangeExpired"),"distance lease not expired");int gx=mc.screen.width/2,gy=mc.screen.height/2-10;org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),gx*mc.getWindow().getGuiScale(),gy*mc.getWindow().getGuiScale());next(25,10);return;}
      if(stage==25){shot(mc,"05-cache-settings-hovered");org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),8,8);next(26,10);return;}
      if(stage==26){shot(mc,"06-cache-settings-mouse-left");ManualPlannerClient.close();ok(field(ManualPlannerClient.class,"retainedSurface")==null,"expired open range retained at close");open();next(12,30);return;}
      if(stage==12){var map=map(mc);if(map==null||map.loading())return;setting(map,0);next(13,20);return;}
      if(stage==13){var map=map(mc);if(map==null||map.cacheSeconds()!=0)return;ManualPlannerClient.close();ok(field(ManualPlannerClient.class,"retainedSurface")==null,"zero seconds did not immediately free surface");open();next(14,30);return;}
      if(stage==14){var map=map(mc);if(map==null||map.loading())return;setting(map,60);next(15,20);return;}
      if(stage==15){var map=map(mc);if(map==null||map.cacheSeconds()!=60)return;ManualPlannerClient.close();ok(field(ManualPlannerClient.class,"retainedSurface")!=null,"reopen did not renew lease");mc.level.disconnect();mc.clearLevel();mc.setScreen(new TitleScreen());next(17,2);}
    }catch(Throwable failure){if(ctrlInjected)controlKey(false);done=true;failure.printStackTrace();try{Files.writeString(OUT.resolve("failure.txt"),"stage="+stage+" "+failure);}catch(Exception ignored){}mc.stop();}
  }
}
