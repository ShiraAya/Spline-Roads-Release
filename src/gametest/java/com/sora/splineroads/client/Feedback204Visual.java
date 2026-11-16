package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.world.Feedback204VisualScene.BridgeCase;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
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

/** One opt-in fresh client world. Calls actual widgets; never uses system mouse input. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Feedback204Visual {
  private static final Path OUT=Path.of(System.getProperty("road.feedback204Output","feedback204"));
  private static Path source;private static String sourceHash;
  private static boolean started,done;private static int ticks,ready,stage,checks,bridgeIndex,shots;
  private static long deadline;private static CompletableFuture<?> pending;
  private static List<BridgeCase> bridges;private static InterchangePlanner.Plan stackPlan;
  private static final Map<String,Map<String,Integer>> upperGeometry=new HashMap<>();
  private static void ok(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
  private static void next(int value,int delay){stage=value;ready=ticks+delay;}
  private static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
  private static Object stat(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
  private static Object call(Object target,String name)throws Exception{var m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
  private static Button button(Screen screen,String prefix){return screen.children().stream()
      .filter(c->c instanceof Button b&&b.getMessage().getString().startsWith(prefix)).map(c->(Button)c)
      .findFirst().orElseThrow(()->new AssertionError("missing button "+prefix));}
  private static void press(Screen screen,String prefix){var b=button(screen,prefix);ok(b.active,"inactive button "+prefix);b.onPress();}
  private static RoadProfile.Options options(RoadScreen screen)throws Exception{return (RoadProfile.Options)field(screen,"options");}
  private static void page(RoadScreen screen,int n)throws Exception{for(int i=0;i<9&&(int)field(screen,"optionsPage")!=n;i++)press(screen,"▶");ok((int)field(screen,"optionsPage")==n,"page "+n+" unavailable");}
  private static net.minecraft.server.level.ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
  private static net.minecraft.world.level.levelgen.WorldDimensions dimensions(net.minecraft.core.RegistryAccess r){
    var flat=net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(r.registryOrThrow(Registries.BIOME).asLookup(),r.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),r.registryOrThrow(Registries.PLACED_FEATURE).asLookup());
    return r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions().replaceOverworldGenerator(r,new net.minecraft.world.level.levelgen.FlatLevelSource(flat));
  }
  private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
  private static void log(String text)throws Exception{Files.writeString(OUT.resolve("results.txt"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("SR204 VISUAL "+text);}
  private static void shot(Minecraft mc,String name)throws Exception{
    try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}
    shots++;log("CAPTURE "+name+" camera="+mc.player.position()+" yaw="+mc.player.getYRot()+" pitch="+mc.player.getXRot()+" readiness="+Feedback204SceneReady.detail());
  }
  private static void camera(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){
    // yaw is world north/south; y is feet position and the target compensates the normal eye height.
    float yaw=(float)Math.toDegrees(Math.atan2(x-tx,tz-z));
    float pitch=(float)Math.toDegrees(Math.atan2(y+mc.player.getEyeHeight()-ty,Math.hypot(x-tx,z-tz)));
    mc.getSingleplayerServer().execute(()->{var p=player(mc);p.getAbilities().flying=true;p.setNoGravity(true);p.onUpdateAbilities();p.teleportTo(p.serverLevel(),x,y,z,yaw,pitch);});
    mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;
  }
  private static boolean settled()throws Exception{return ((Map<?,?>)stat(RoadRenderer.class,"jobs")).isEmpty()
      &&stat(RoadRenderer.class,"pendingUpload")==null&&((Collection<?>)stat(RoadRenderer.class,"dirty")).isEmpty();}
  private static RoadIndex.Built built(UUID id){synchronized(ClientRoads.INDEX){return ClientRoads.INDEX.roads.get(id);}}
  private static boolean uploaded(UUID id)throws Exception{
    Object bytes=((Map<?,?>)stat(RoadRenderer.class,"allocatedRoadBytes")).get(id);
    return bytes instanceof Number n&&n.longValue()>0;
  }
  private static boolean sceneReady(BridgeCase c)throws Exception{
    // Offscreen saved roads intentionally remain dirty until visible. Only wait
    // for this scene's completed GPU upload; a global empty queue never settles.
    boolean ready=Feedback204SceneReady.ready(c);
    boolean present=built(c.id())!=null,job=((Map<?,?>)stat(RoadRenderer.class,"jobs")).containsKey(c.id()),gpu=uploaded(c.id());
    if(ticks%100==0&&!ready)log("WAIT stage="+stage+" bridge="+c.name()+" present="+present+" job="+job+" gpu="+gpu+" "+Feedback204SceneReady.detail());
    return present&&!job&&gpu&&ready;
  }
  private static boolean under(Part p,double y){return p.base().stream().anyMatch(v->v.y()<y-1e-6);}
  private static boolean above(Part p,double y){return p.top().stream().anyMatch(v->v.y()>y+1e-6);}
  private static boolean contains(RoadIndex.Built b,V p){
    var pos=BlockPos.containing(p.x(),p.y(),p.z());double x=p.x()-pos.getX(),y=p.y()-pos.getY(),z=p.z()-pos.getZ();
    return b.boxes(pos).stream().anyMatch(q->x>q.x0()-1e-6&&x<q.x1()+1e-6&&y>q.y0()-1e-6&&y<q.y1()+1e-6&&z>q.z0()-1e-6&&z<q.z1()+1e-6);
  }
  private static boolean worldContains(Minecraft mc,V p){var pos=BlockPos.containing(p.x(),p.y(),p.z());
    return mc.level.getBlockState(pos).getCollisionShape(mc.level,pos).toAabbs().stream()
        .anyMatch(q->q.contains(p.x()-pos.getX(),p.y()-pos.getY(),p.z()-pos.getZ()));}
  private static String geometry(Part p,BridgeCase c){
    var b=new StringBuilder(p.material()+"/"+p.pier()+"/"+p.model());
    for(var face:List.of(p.base(),p.top()))for(var v:face)b.append('/').append(Math.round((v.x()-c.x())*1e5))
        .append(',').append(Math.round((v.y()-c.deck())*1e5)).append(',').append(Math.round((v.z()-c.z())*1e5));
    return b.toString();
  }
  private static void checkBridge(Minecraft mc,BridgeCase c)throws Exception{
    var b=built(c.id());var mesh=b.mesh;var parts=b.record.structures();var infra=b.record.settings().options().infrastructure();
    double underside=c.deck()-mesh.settings().thickness();
    ok(infra.bridge()==c.kind()&&infra.supportsEnabled()==c.supports()&&infra.autoSpan()==c.auto(),"packet settings differ "+c.name());
    ok(Math.abs(mesh.first().center().y()-c.deck())<1e-6
        &&Math.abs(b.record.end().position().distance(b.record.start().position())-120)<1e-5
        &&Math.abs(mesh.length()-121)<1e-5,"server changed authored bridge or its half-block end caps "+c.name());
    ok(!parts.isEmpty(),"empty client parts "+c.name());ok(uploaded(c.id()),"no native GPU upload "+c.name());
    var lower=parts.stream().filter(p->under(p,underside)).toList();
    long longitudinal=lower.stream().filter(p->Math.abs(p.b().z()-p.a().z())>2*Math.abs(p.b().x()-p.a().x())+1e-6).count();
    long transverse=lower.stream().filter(p->Math.abs(p.b().x()-p.a().x())>mesh.settings().width()/2).count();
    ok(c.supports()?!lower.isEmpty():lower.isEmpty(),"lower geometry state wrong "+c.name()+" lower="+lower.size());
    if(c.supports())ok(longitudinal>0,"enabled positive control lacks longitudinal girder "+c.name());
    else ok(RoadInfrastructure.girderDepth(mesh)==0,"phantom clearance depth "+c.name());
    var upper=new TreeMap<String,Integer>();for(var p:parts)if(above(p,c.deck()))upper.merge(geometry(p,c),1,Integer::sum);
    upperGeometry.put(c.name(),upper);
    if(c.kind()==RoadInfrastructure.Bridge.TEMPLATE){
      ok(upper.isEmpty(),"empty bridge retained upper decoration "+c.name());
      var stations=new TreeSet<Long>();for(var p:lower)if(p.pier())stations.add(Math.round((p.a().z()-c.z())*1e5));
      int expected=c.supports()?(c.auto()?2:7):0;ok(stations.size()==expected,"template support stations "+c.name()+" "+stations);
      var s=RoadStructures.sample(mesh,60);ok(Math.abs(RoadInfrastructure.edgeReach(mesh,s)-2.1)<1e-7,"template edge reach not 2.1 "+c.name());
      for(int side:new int[]{-1,1}){
        var inside=s.center().add(s.left().mul(side*(s.halfWidth()+1.8))).add(new V(0,-.5,0));
        var outside=s.center().add(s.left().mul(side*(s.halfWidth()+2.6))).add(new V(0,-.5,0));
        ok(contains(b,inside),"edge raster missing "+c.name()+" side="+side);
        ok(worldContains(mc,inside),"real client edge collider missing "+c.name()+" side="+side);
        ok(!contains(b,outside)&&!worldContains(mc,outside),"outside negative control acquired collision "+c.name()+" side="+side);
        // Check persisted exact slab vertices too: the 2.1 figure cannot pass by metadata alone.
        double reach=parts.stream().filter(p->!under(p,underside)&&!above(p,c.deck()))
            .filter(p->Math.min(p.a().z(),p.b().z())<=c.z()+60&&Math.max(p.a().z(),p.b().z())>=c.z()+60)
            .flatMap(p->p.top().stream()).mapToDouble(v->side*v.sub(s.center()).dot(s.left())-s.halfWidth()).max().orElse(-1);
        ok(Math.abs(reach-2.1)<1e-5,"persisted exact edge differs "+c.name()+" side="+side+" reach="+reach);
      }
      log(c.name()+" supportStations="+stations+" exactEdge=2.1 upperParts=0");
    }else{
      ok(!upper.isEmpty(),"support switch removed all upper geometry "+c.name());
      ok(parts.stream().anyMatch(p->p.pier()&&p.material()==Material.CONCRETE&&above(p,c.deck()+10)),"upper concrete towers missing "+c.name());
      ok(parts.stream().anyMatch(p->(p.material()==Material.STEEL||p.material()==Material.DARK_STEEL)&&above(p,c.deck()+3)),"upper cables missing "+c.name());
      if(c.supports())ok(transverse>0,"enabled control lacks tower-bottom crossbeam "+c.name());
      else {
        String control=c.kind()==RoadInfrastructure.Bridge.CABLE?"cable-on-control":"suspension-on-control";
        ok(upper.equals(upperGeometry.get(control)),"disabling supports altered upper geometry "+c.name());
      }
    }
    // Mid-span inside the former girder: native client index and placed block shape must agree.
    var mid=RoadStructures.sample(mesh,60);double depth=c.supports()?RoadInfrastructure.girderDepth(mesh):1.2;
    var girder=mid.center().add(mid.left().mul(mid.halfWidth()*.62)).add(new V(0,-mesh.settings().thickness()-depth*.5,0));
    ok(contains(b,girder)==c.supports(),"girder raster control failed "+c.name());
    ok(worldContains(mc,girder)==c.supports(),"real girder collider control failed "+c.name());
    log(c.name()+" id="+c.id()+" lower="+lower.size()+" longitudinal="+longitudinal+" transverse="+transverse+" upper="+upper.values().stream().mapToInt(Integer::intValue).sum());
  }
  private static void overview(Minecraft mc,BridgeCase c){camera(mc,c.x()+46,c.deck()+30,c.z()-34,c.x(),c.deck()+8,c.z()+52);}
  private static void underside(Minecraft mc,BridgeCase c){camera(mc,c.x()+16,c.deck()-7,c.z()+11,c.x(),c.deck()-.8,c.z()+45);}
  private static void edge(Minecraft mc,BridgeCase c){camera(mc,c.x()+20,c.deck()+3,c.z()+45,c.x()+11.8,c.deck(),c.z()+65);}
  private static void finish(Minecraft mc)throws Exception{
    ok(sha(source).equals(sourceHash),"source snapshot changed");ok(shots==28,"screenshot coverage incomplete "+shots);
    log("ASSERTIONS PASS checks="+checks+" captures="+shots+" sourceSHA256="+sourceHash);
    Files.writeString(OUT.resolve("passed.txt"),"SR204 NATIVE ASSERTIONS PASS checks="+checks+" captures="+shots+"\nSnapshot object 0: 600/600, 7 -> 19, STACK adjustEndpoints=true, actual UI and world preview. Seven built bridge scenes, real client index/collision and positive GPU bytes; on/off controls, exact 2.1 edge and native controls. Original snapshot SHA256 unchanged. Screenshots still require human/agent inspection; do not equate this marker or BUILD SUCCESS with visual approval.\n");
    done=true;mc.stop();
  }
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
    if(!Boolean.getBoolean("road.feedback204Visual")||done||event.phase!=TickEvent.Phase.END)return;
    var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){
        if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
        started=true;Files.createDirectories(OUT);
        if(Files.exists(OUT.resolve("passed.txt"))||Files.exists(OUT.resolve("failure.txt")))throw new AssertionError("output directory already contains an old run; choose fresh roadRunDir/output");
        source=Path.of(System.getProperty("road.feedback204Snapshot"));sourceHash=sha(source);
        deadline=System.nanoTime()+1_200_000_000_000L;log("START snapshot="+source+" sha256="+sourceHash);
        mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.pauseOnLostFocus=false;
        mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.options.renderDistance().set(16);mc.options.framerateLimit().set(60);mc.resizeDisplay();
        mc.createWorldOpenFlows().createFreshLevel("sr204-native-"+System.currentTimeMillis(),new LevelSettings("SR204 isolated feedback",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(204L,false,false),Feedback204Visual::dimensions);return;
      }
      if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage+" bridge="+bridgeIndex);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
      if(stage==0){mc.setScreen(null);RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.VBO);
        pending=mc.getSingleplayerServer().submit(()->{try{return Feedback204VisualScene.snapshot(player(mc),source);}catch(Exception e){throw new RuntimeException(e);}});next(1,20);return;}
      if(stage==1){if(!pending.isDone())return;var payload=(CompoundTag)pending.join();
        ok(payload.hasUUID("Id")&&payload.getUUID("Id").equals(Feedback204VisualScene.SAVED_ID),"wrong editor identity");
        var nodes=payload.getList("Nodes",Tag.TAG_COMPOUND);double delta=Math.abs(RoadRecord.readNode(nodes.getCompound(0)).position().y()-RoadRecord.readNode(nodes.getCompound(2)).position().y());
        ok(Math.abs(delta-7)<1e-6,"payload source height is not 7");ok(payload.getCompound("Options").getBoolean("AdjustEndpoints"),"payload lost endpoint adjustment");
        log("snapshot object0="+Feedback204VisualScene.SAVED_ID+" sourceHeight="+delta+" source axes=600/600");
        camera(mc,-230,60,-455,-135,-45,-350);InterchangeScreen.open(payload);next(2,4);return;}
      if(stage==2){var screen=(InterchangeScreen)mc.screen;
        for(int n=0;n<12;n++){var current=(InterchangePlanner.Options)field(screen,"options");if(current.preset()==InterchangePlanner.Preset.STACK)break;press(screen,current.preset().label);}
        var current=(InterchangePlanner.Options)field(screen,"options");ok(current.preset()==InterchangePlanner.Preset.STACK,"STACK selection failed");ok(current.adjustEndpoints(),"STACK discarded adjustEndpoints");next(3,12);return;}
      if(stage==3){var screen=(InterchangeScreen)mc.screen;if((Boolean)field(screen,"computing")||(Boolean)field(screen,"needsPlan"))return;
        var o=(InterchangePlanner.Options)field(screen,"options");ok(o.preset()==InterchangePlanner.Preset.STACK&&o.adjustEndpoints(),"STACK state changed");
        stackPlan=(InterchangePlanner.Plan)field(screen,"plan");ok(stackPlan!=null,"STACK failed: "+field(screen,"status"));
        ok(stackPlan.anchors().size()==4,"STACK lacks anchors");double delta=Math.abs(stackPlan.anchors().get(0).position().y()-stackPlan.anchors().get(2).position().y());
        ok(Math.abs(delta-19)<1e-5,"STACK did not raise source 7 to required 19: "+delta);ok(stackPlan.movements()==8,"STACK missing turning movements");
        ok(((Button)field(screen,"preview")).active,"STACK preview disabled");ok(((Button)field(screen,"save")).active,"STACK update disabled");
        ok(field(screen,"status").toString().contains("四层堆栈"),"STACK ready status missing");
        log("STACK READY delta="+delta+" anchors="+stackPlan.anchors()+" legs="+stackPlan.legs().size()+" status="+field(screen,"status"));next(4,12);return;}
      if(stage==4){shot(mc,"01-stack-object0-ready-height7-to19");var screen=(InterchangeScreen)mc.screen;((Button)field(screen,"preview")).onPress();
        ok(mc.screen==null,"real preview button did not close screen");ok(!ClientRoads.nodePreviews.isEmpty(),"real world preview absent");
        mc.options.hideGui=true;next(5,100);return;}
      if(stage==5){ok(ClientRoads.nodePreviews.size()==stackPlan.legs().size(),"preview lost road legs");ok(stat(RoadRenderer.class,"ghostBuffer")!=null,"native preview GPU buffer missing");shot(mc,"02-stack-world-preview");
        var resume=new CompoundTag();resume.putString("Kind","interchangeResume");resume.putInt("GeneratorArms",4);InterchangeScreen.open(resume);
        ok(mc.screen instanceof InterchangeScreen,"return from preview failed");((InterchangeScreen)mc.screen).onClose();
        ok(ClientRoads.nodePreviews.isEmpty(),"closing preview left geometry");mc.options.hideGui=true;mc.options.renderDistance().set(8);
        pending=mc.getSingleplayerServer().submit(()->Feedback204VisualScene.bridges(player(mc)));next(6,20);return;}
      if(stage==6){if(!pending.isDone())return;@SuppressWarnings("unchecked") var made=(List<BridgeCase>)pending.join();bridges=made;ok(bridges.size()==7,"bridge case count");
        overview(mc,bridges.get(0));next(7,140);return;}
      if(stage==7){if(!sceneReady(bridges.get(0)))return;
        pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);RoadTool.openRoad(p,RoadData.get(p.serverLevel()).index.roads.get(bridges.get(0).id()).record,false);return true;});next(8,20);return;}
      if(stage==8){if(!pending.isDone()||!(mc.screen instanceof RoadScreen screen))return;pending.join();mc.options.hideGui=false;page(screen,2);
        ok(RoadInfrastructure.Bridge.TEMPLATE.label.equals("空桥梁"),"TEMPLATE label not updated");ok(button(screen,"空桥梁 ▸").active,"empty bridge control missing");
        ok(options(screen).infrastructure().supportsEnabled()&&options(screen).infrastructure().autoSpan(),"initial template state not auto");next(9,25);return;}
      if(stage==9){var screen=(RoadScreen)mc.screen;shot(mc,"03-ui-empty-bridge-auto");press(screen,"配跨：自动");
        ok(options(screen).infrastructure().supportsEnabled()&&!options(screen).infrastructure().autoSpan(),"auto -> manual failed");next(10,25);return;}
      if(stage==10){var screen=(RoadScreen)mc.screen;shot(mc,"04-ui-empty-bridge-manual");press(screen,"配跨：手动");ok(!options(screen).infrastructure().supportsEnabled(),"manual -> off failed");next(11,25);return;}
      if(stage==11){var screen=(RoadScreen)mc.screen;shot(mc,"05-ui-empty-bridge-off");press(screen,"配跨：关闭");
        ok(options(screen).infrastructure().supportsEnabled()&&options(screen).infrastructure().autoSpan(),"off -> auto cycle failed");next(12,25);return;}
      if(stage==12){var screen=(RoadScreen)mc.screen;shot(mc,"06-ui-empty-bridge-auto-cycle-restored");press(screen,"配跨：自动");press(screen,"配跨：手动");
        press(screen,(String)call(screen,"structureName"));press(screen,(String)call(screen,"structureName"));ok(field(screen,"structure")==Structure.AUTO,"structure did not cycle to AUTO");
        ok(!((Settings)call(screen,"settings")).options().infrastructure().supportsEnabled(),"AUTO discarded disabled support state");next(13,25);return;}
      if(stage==13){shot(mc,"07-ui-auto-structure-supports-off-retained");press((RoadScreen)mc.screen,"取消");mc.setScreen(null);mc.options.hideGui=true;
        bridgeIndex=0;overview(mc,bridges.get(bridgeIndex));next(20,120);return;}
      if(stage==20){var c=bridges.get(bridgeIndex);if(!sceneReady(c))return;checkBridge(mc,c);shot(mc,String.format("%02d-%s-overview",8+bridgeIndex*3,c.name()));
        underside(mc,c);next(21,100);return;}
      if(stage==21){var c=bridges.get(bridgeIndex);if(!sceneReady(c))return;shot(mc,String.format("%02d-%s-under-deck",9+bridgeIndex*3,c.name()));
        edge(mc,c);next(22,100);return;}
      if(stage==22){var c=bridges.get(bridgeIndex);if(!sceneReady(c))return;shot(mc,String.format("%02d-%s-edge-band",10+bridgeIndex*3,c.name()));
        if(++bridgeIndex<bridges.size()){overview(mc,bridges.get(bridgeIndex));next(20,120);}else finish(mc);}
    }catch(Throwable failure){done=true;failure.printStackTrace();try{
      Files.createDirectories(OUT);if(mc.level!=null&&mc.player!=null)shot(mc,"failure-stage-"+stage);
      Files.writeString(OUT.resolve("failure.txt"),"stage="+stage+" bridge="+bridgeIndex+" checks="+checks+" captures="+shots+"\n"+failure+"\n");
    }catch(Exception ignored){}mc.stop();}
  }
  private Feedback204Visual(){}
}
