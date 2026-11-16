package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
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

/** One actual assembly, initial cloverleaf construction followed by seven real UI updates. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Interchange204SequenceVisual {
  private static final Preset[] ORDER={Preset.CLOVERLEAF,Preset.TURBINE,Preset.STACK,Preset.HYBRID,Preset.DIAMOND,Preset.ROUNDABOUT,Preset.SPUI,Preset.DOUBLE_TRUMPET};
  private static final Path OUT=Path.of(System.getProperty("road.interchange204SequenceOutput","feedback204/sequence"));
  private static boolean started,done;private static int ticks,ready,stage,choice,checks,shots;
  private static long deadline,stepStart,repliesBefore,resumeGeneration;private static CompletableFuture<?> pending;
  private static UUID group;private static CompoundTag payload;private static InterchangeScreen submitting;
  private static InterchangePlanner.Plan plan;private static ListTag fitted;
  private static String previewStatus;
  private static Interchange204SequenceScene.State saved;
  private static int detailView,stableFrames;
  private static final String REVIEW_WORLD=System.getProperty("road.interchange204ReviewWorld","");
  private static final Set<UUID> seen=new HashSet<>();private static final List<Preset> completed=new ArrayList<>();
  private static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  private static void next(int value,int delay){stage=value;ready=ticks+delay;}
  private static Object field(Object value,String name)throws Exception{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
  private static Object stat(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
  private static void log(String value)throws Exception{Files.writeString(OUT.resolve("results.txt"),value+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("SR204 SEQUENCE "+value);}
  private static net.minecraft.server.level.ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
  private static net.minecraft.world.level.levelgen.WorldDimensions dimensions(net.minecraft.core.RegistryAccess r){var f=net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(r.registryOrThrow(Registries.BIOME).asLookup(),r.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),r.registryOrThrow(Registries.PLACED_FEATURE).asLookup());return r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions().replaceOverworldGenerator(r,new net.minecraft.world.level.levelgen.FlatLevelSource(f));}
  private static Button button(InterchangeScreen screen,String prefix){return screen.children().stream().filter(v->v instanceof Button b&&b.getMessage().getString().startsWith(prefix)).map(v->(Button)v).findFirst().orElseThrow(()->new AssertionError("missing widget "+prefix));}
  private static void press(InterchangeScreen screen,String prefix){var b=button(screen,prefix);ok(b.active,"disabled widget "+prefix);b.onPress();}
  private static InterchangePlanner.Options options(InterchangeScreen screen)throws Exception{return (InterchangePlanner.Options)field(screen,"options");}
  private static boolean calculating(InterchangeScreen screen)throws Exception{return (Boolean)field(screen,"computing")||(Boolean)field(screen,"needsPlan");}
  private static ListTag tags(List<Node> nodes){var out=new ListTag();nodes.forEach(n->out.add(RoadRecord.writeNode(n)));return out;}
  private static void shot(Minecraft mc,String suffix)throws Exception{String name=String.format("%02d-%s-%s",choice+1,ORDER[choice],suffix);
    try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}shots++;log("CAPTURE "+name+" position="+mc.player.position()+" yaw="+mc.player.getYRot()+" pitch="+mc.player.getXRot());}
  private static void camera(Minecraft mc,V target,double radius){
    double x=target.x()-radius*.72,z=target.z()-radius*.72,y=target.y()+radius*.68;
    float yaw=(float)Math.toDegrees(Math.atan2(x-target.x(),target.z()-z));float pitch=(float)Math.toDegrees(Math.atan2(y+mc.player.getEyeHeight()-target.y(),Math.hypot(x-target.x(),z-target.z())));
    mc.getSingleplayerServer().execute(()->{var p=player(mc);p.getAbilities().flying=true;p.setNoGravity(true);p.onUpdateAbilities();p.teleportTo(p.serverLevel(),x,y,z,yaw,pitch);});
    mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;
  }
  private static void planCamera(Minecraft mc){
    var ramps=plan.legs().stream().filter(l->l.mesh().settings().style().ramp()).map(Leg::mesh).toList();
    double extent=ramps.stream().flatMap(m->m.samples().stream()).mapToDouble(s->s.center().sub(plan.center()).horizontalLength()).max().orElse(120);
    // Show the crossing and its ramps; long donor tails are deliberately outside the focus.
    double radius=Math.min(300,Math.max(150,extent*.78));camera(mc,plan.center().add(new V(0,8,0)),radius);
  }
  private static void doubleDetailCamera(Minecraft mc,int view){
    String prefix=view==0?"AB 侧 ":view==1?"CD 侧 ":DoubleTrumpetPlanner.CONNECTOR;
    var meshes=plan.legs().stream().filter(l->l.name().startsWith(prefix))
        .filter(l->view==2||l.mesh().settings().style().ramp()).map(Leg::mesh).toList();
    ok(!meshes.isEmpty(),"double trumpet detail has no geometry "+prefix);
    double minX=meshes.stream().mapToDouble(m->m.min().x()).min().orElseThrow(),maxX=meshes.stream().mapToDouble(m->m.max().x()).max().orElseThrow();
    double minZ=meshes.stream().mapToDouble(m->m.min().z()).min().orElseThrow(),maxZ=meshes.stream().mapToDouble(m->m.max().z()).max().orElseThrow();
    double y=meshes.stream().mapToDouble(m->m.max().y()).max().orElseThrow();
    camera(mc,new V((minX+maxX)*.5,y+8,(minZ+maxZ)*.5),Math.min(290,Math.max(170,Math.max(maxX-minX,maxZ-minZ)*.85)));
    stableFrames=0;
  }
  private static double turn(Mesh mesh){double sum=0;for(int i=1;i<mesh.samples().size();i++){
    var a=mesh.samples().get(i-1).left();var b=mesh.samples().get(i).left();sum+=Math.abs(Math.atan2(a.x()*b.z()-a.z()*b.x(),a.dot(b)));}return sum;}
  private static void loopCamera(Minecraft mc,int view)throws Exception{
    String prefix=view==0?"AB 侧 ":"CD 侧 ";
    var leg=plan.legs().stream().filter(l->l.name().startsWith(prefix)&&l.mesh().settings().style().ramp()).max(Comparator.comparingDouble(l->turn(l.mesh()))).orElseThrow();
    var mesh=leg.mesh();ok(turn(mesh)>Math.PI,"selected detail is not the looping ramp");
    var center=mesh.min().add(mesh.max()).mul(.5).add(new V(0,8,0));
    double radius=Math.max(95,Math.min(170,Math.max(mesh.max().x()-mesh.min().x(),mesh.max().z()-mesh.min().z())*.85));
    camera(mc,center,radius);stableFrames=0;log("REVIEW LOOP "+prefix+"name="+leg.name()+" turnRadians="+turn(mesh)+" center="+center+" radius="+radius);
  }
  private static String renderedDetail="unrequested";
  private static boolean rendered()throws Exception{
    var allocations=(Map<?,?>)stat(RoadRenderer.class,"allocatedRoadBytes");var jobs=(Map<?,?>)stat(RoadRenderer.class,"jobs");
    var versions=(Map<?,?>)stat(RoadRenderer.class,"versions");var completeVersions=(Map<?,?>)stat(RoadRenderer.class,"meshVersions");
    var preparations=(Map<?,?>)stat(RoadRenderer.class,"preparations");var atomic=(Map<?,?>)stat(RoadRenderer.class,"atomicUpdates");
    var eye=(V)stat(RoadRenderer.class,"cameraPosition");int buildDistance=Minecraft.getInstance().options.getEffectiveRenderDistance()+2;
    var groupIds=new HashSet<UUID>();saved.members().forEach(r->groupIds.add(r.id()));
    var requiredIds=new HashSet<UUID>();var currentVisible=new HashSet<UUID>();var ownerReady=new HashSet<UUID>();var reasons=new ArrayList<String>();
    int visible=0,required=0,unloaded=0,dirty=0,uploading=0,mismatches=0,versionWait=0,windowWait=0,offscopeWindows=0,partialOwners=0,retired=0,staleRecords=0,missingRecords=0,readyRamps=0;
    Object pending=stat(RoadRenderer.class,"pendingUpload");Object pendingSection=pending==null?null:field(pending,"section");
    for(var section:(Collection<?>)stat(RoadRenderer.class,"visible")){
      var uploaded=(Map<?,?>)field(section,"uploadedSources");var pieces=(Map<?,?>)field(section,"pieces");
      var owners=pieces.keySet().stream().filter(groupIds::contains).map(v->(UUID)v).toList();
      if(owners.isEmpty())continue;visible++;
      var key=(RoadRenderMesh.Section)field(section,"key");
      if(!RoadTerrainModels.regionLoaded(key)){unloaded++;continue;}
      required++;requiredIds.addAll(owners);
      if((Boolean)field(section,"dirty"))dirty++;
      if(section==pendingSection)uploading++;
      for(var id:owners){
        Object identity=field(pieces.get(id),"identity");var preparation=preparations.get(id);
        var current=preparation==null?null:((Map<?,?>)field(preparation,"pieces")).get(key);
        Object currentIdentity=current==null?null:field(current,"identity");
        if(Objects.equals(uploaded.get(id),identity)&&Objects.equals(currentIdentity,identity))currentVisible.add(id);
        else {mismatches++;if(reasons.size()<8)reasons.add(key+"/"+id+" source="+identity+" uploaded="+uploaded.get(id)+" currentPreparation="+currentIdentity);}
      }
    }
    // meshVersions is whole-road completion, not the visible-window generation.
    // Normal streaming uses the visible CPU build scope. Replacing old pieces
    // must finish the complete window union before old coverage can be retired.
    for(var id:requiredIds){
      var p=preparations.get(id);int version=versions.get(id) instanceof Number n?n.intValue():0;
      boolean current=p!=null&&((Number)field(p,"version")).intValue()==version
          &&field(p,"source")==ClientRoads.INDEX.roads.get(id);
      if(jobs.containsKey(id)||atomic.containsKey(id)||!current){
        versionWait++;if(reasons.size()<8)reasons.add(id+" job="+jobs.containsKey(id)+" atomic="+atomic.containsKey(id)+" generation="+version+"/"+(p==null?null:field(p,"version"))+" sourceCurrent="+current);continue;
      }
      var complete=(Set<?>)field(p,"surfaces");int missing=0,outside=0,inScope=0;
      boolean finishingPrevious=(Boolean)field(p,"finishPrevious")&&complete.size()<((Collection<?>)field(p,"windows")).size();
      for(var item:(Collection<?>)field(p,"windows")){
        var w=(RoadMeshSurfaces.Window)item;
        if(finishingPrevious||RoadVisibility.within(w.min().x(),w.min().z(),w.max().x(),w.max().z(),eye.x(),eye.z(),buildDistance)){
          inScope++;if(!complete.contains(w))missing++;
        }else outside++;
      }
      windowWait+=missing;offscopeWindows+=outside;
      if(missing>0){if(reasons.size()<8)reasons.add(id+" missingSchedulableWindows="+missing+"/"+inScope+" outsideBuildScope="+outside);continue;}
      ownerReady.add(id);
      if(!Objects.equals(version,completeVersions.get(id)))partialOwners++;
    }
    synchronized(ClientRoads.INDEX){
      for(var id:seen)if(!saved.allIds().contains(id)&&ClientRoads.INDEX.roads.containsKey(id))retired++;
      for(var r:saved.members()){
        var b=ClientRoads.INDEX.roads.get(r.id());if(b==null){if(requiredIds.contains(r.id()))missingRecords++;continue;}
        if(!b.record.header().equals(r.header())||!b.record.structures().equals(r.structures())){staleRecords++;continue;}
        var bytes=allocations.get(r.id());
        if(r.settings().style().ramp()&&bytes instanceof Number n&&n.longValue()>0&&ownerReady.contains(r.id())&&currentVisible.contains(r.id()))readyRamps++;
      }
    }
    renderedDetail="visible="+visible+" required="+required+" unloadedVisible="+unloaded+" dirty="+dirty+" pending="+uploading
        +" identityMismatch="+mismatches+" ownerVersionWait="+versionWait+" schedulableWindowWait="+windowWait+" outsideBuildScopeWindows="+offscopeWindows+" currentPartialOwners="+partialOwners
        +" retired="+retired+" staleRecords="+staleRecords+" missingVisibleRecords="+missingRecords+" readyRamps="+readyRamps+" reasons="+reasons;
    return required>0&&dirty==0&&uploading==0&&mismatches==0&&versionWait==0&&windowWait==0&&retired==0&&staleRecords==0&&missingRecords==0&&readyRamps>0;
  }
  private static void finish(Minecraft mc)throws Exception{
    ok(completed.equals(List.of(ORDER)),"actual completed order differs: "+completed);ok(shots==27,"incomplete captures "+shots);
    ok(Interchanges.read(saved.saved().getCompound("Options")).preset()==Preset.DOUBLE_TRUMPET,"double trumpet was not the final saved object");
    log("PASS checks="+checks+" group="+group+" actualBuiltOrder="+completed+" screenshots="+shots);
    Files.writeString(OUT.resolve("passed.txt"),"SR204 NATIVE SEQUENCE ASSERTIONS PASS checks="+checks+" group="+group+"\n"+completed+"\nAll eight were actually previewed and submitted through UI packets. Same authoritative assembly UUID; fresh saved Points each update; exact preview/saved/moved-marker endpoint equality; two real donor sources; received records and target ramp GPU upload verified. Screenshots still require inspection.\n");
    done=true;mc.stop();
  }
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
    if(!Boolean.getBoolean("road.interchange204SequenceVisual")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);
        if(Files.exists(OUT.resolve("passed.txt"))||Files.exists(OUT.resolve("failure.txt")))throw new AssertionError("choose a fresh run/output directory");
        deadline=System.nanoTime()+2_700_000_000_000L;mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.pauseOnLostFocus=false;
        mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.options.renderDistance().set(24);mc.options.framerateLimit().set(60);mc.resizeDisplay();
        if(!REVIEW_WORLD.isEmpty()){stage=100;mc.createWorldOpenFlows().loadLevel(mc.screen,REVIEW_WORLD);return;}
        mc.createWorldOpenFlows().createFreshLevel("sr204-sequence-"+System.currentTimeMillis(),new LevelSettings("SR204 eight actual sequential interchanges",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20408L,false,false),Interchange204SequenceVisual::dimensions);return;}
      if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage+" preset="+ORDER[choice]);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
      if(stage==100){mc.setScreen(null);mc.options.hideGui=true;RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.VBO);
        pending=mc.getSingleplayerServer().submit(()->Interchange204SequenceScene.review(player(mc)));next(101,20);return;}
      if(stage==101){if(!pending.isDone())return;saved=(Interchange204SequenceScene.State)pending.join();group=saved.group();seen.addAll(saved.allIds());choice=7;
        var tags=saved.saved().getList("Nodes",Tag.TAG_COMPOUND);var nodes=new Node[4];for(int i=0;i<4;i++)nodes[i]=RoadRecord.readNode(tags.getCompound(i));
        plan=InterchangePlanner.plan(nodes,RoadRecord.readSettings(saved.saved().getCompound("Main1")),RoadRecord.readSettings(saved.saved().getCompound("Main2")),Interchanges.read(saved.saved().getCompound("Options")));
        log("REVIEW RELOADED SAME GROUP "+group+" without construction or editor submission");detailView=0;loopCamera(mc,detailView);next(102,200);return;}
      if(stage==102){if(!rendered()){stableFrames=0;if(ticks%100==0)log("WAIT RELOADED LOOP "+renderedDetail);return;}if(++stableFrames<20)return;
        shot(mc,detailView==0?"reloaded-AB-loop-close":"reloaded-CD-loop-close");log("REVIEW RENDER READY "+renderedDetail);
        if(++detailView<2){loopCamera(mc,detailView);next(102,200);return;}
        pending=mc.getSingleplayerServer().submit(()->Interchange204SequenceScene.review(player(mc)));next(103,1);return;}
      if(stage==103){if(!pending.isDone())return;var after=(Interchange204SequenceScene.State)pending.join();
        ok(saved.saved().equals(after.saved())&&saved.allIds().equals(after.allIds()),"review changed saved assembly");
        var headers=new HashMap<UUID,CompoundTag>();after.members().forEach(r->headers.put(r.id(),r.header()));
        for(var r:saved.members())ok(r.header().equals(headers.get(r.id())),"review modified actual road "+r.id());
        Files.writeString(OUT.resolve("passed.txt"),"SR204 RELOADED LOOP REVIEW PASS group="+group+" checks="+checks+" screenshots="+shots+"\nExisting final sequence world; no construction or save packet; assembly and member headers unchanged.\n");
        log("REVIEW PASS sameGroup="+group+" checks="+checks+" screenshots="+shots);done=true;mc.stop();return;}
      if(stage==0){mc.setScreen(null);RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.VBO);camera(mc,new V(Interchange204SequenceScene.X,-40,Interchange204SequenceScene.Z),180);
        pending=mc.getSingleplayerServer().submit(()->Interchange204SequenceScene.create(player(mc)));next(1,20);return;}
      if(stage==1){if(!pending.isDone())return;payload=(CompoundTag)pending.join();ok(payload.getList("ContinuousSources",Tag.TAG_COMPOUND).size()==2,"two donors not captured");
        ok(!payload.hasUUID("Id"),"first fixture is already built");InterchangeScreen.open(payload);
        ok(field(mc.screen,"plan")==null&&calculating((InterchangeScreen)mc.screen),"fresh initial payload reused a cached preview");next(2,15);return;}
      if(stage==2){var screen=(InterchangeScreen)mc.screen;stepStart=System.nanoTime();
        for(int n=0;n<12&&options(screen).preset()!=ORDER[choice];n++)press(screen,options(screen).preset().label);
        ok(options(screen).preset()==ORDER[choice],"requested preset unavailable "+ORDER[choice]);
        if(options(screen).aboveAvailable()&&!options(screen).allowAbove())press(screen,"允许匝道高于主路");
        ok(options(screen).adjustEndpoints(),"endpoint adjustment disabled");ok(options(screen).junctionAxis()==1,"ordinary road is not CD service axis");
        next(3,12);return;}
      if(stage==3){var screen=(InterchangeScreen)mc.screen;if(calculating(screen))return;
        plan=(InterchangePlanner.Plan)field(screen,"plan");ok(plan!=null,"preview failed "+ORDER[choice]+": "+field(screen,"status"));
        ok(options(screen).preset()==ORDER[choice],"preview preset changed");ok(plan.movements()>0,"preview has no turning movements");
        ok(((Button)field(screen,"save")).active&&((Button)field(screen,"preview")).active,"preview/build controls not ready");
        if(choice==0)ok(((Button)field(screen,"save")).getMessage().getString().equals("整体建造"),"first cloverleaf is not a fresh actual build");
        else ok(((Button)field(screen,"save")).getMessage().getString().equals("整体更新"),"later step is not an update");
        ok(field(screen,"plannedInputs").equals(field(screen,"payload")),"ready plan does not match complete current inputs");
        previewStatus=(String)field(screen,"status");fitted=tags(plan.anchors());planCamera(mc);log("READY "+ORDER[choice]+" group="+group+" movements="+plan.movements()+" legs="+plan.legs().size()+" anchors="+plan.anchors());next(4,15);return;}
      if(stage==4){shot(mc,"ui-ready");var screen=(InterchangeScreen)mc.screen;((Button)field(screen,"preview")).onPress();ok(mc.screen==null,"actual preview widget did not enter world");
        ok(ClientRoads.nodePreviews.size()==plan.legs().size(),"world preview lost legs");mc.options.hideGui=true;next(5,100);return;}
      if(stage==5){ok(stat(RoadRenderer.class,"ghostBuffer")!=null,"preview never uploaded native geometry");shot(mc,"world-preview");
        resumeGeneration=(Long)stat(InterchangeScreen.class,"generation");
        var resume=new CompoundTag();resume.putString("Kind","interchangeResume");resume.putInt("GeneratorArms",4);InterchangeScreen.open(resume);ok(mc.screen instanceof InterchangeScreen,"preview return failed");mc.options.hideGui=false;next(6,12);return;}
      if(stage==6){var screen=(InterchangeScreen)mc.screen;if(calculating(screen))return;var resumed=(InterchangePlanner.Plan)field(screen,"plan");
        ok(resumed!=null&&tags(resumed.anchors()).equals(fitted),"resume changed confirmed preview endpoints");ok(options(screen).preset()==ORDER[choice],"resume changed preset");
        ok(previewStatus.equals(field(screen,"status")),"resume lost completed preview status");
        ok(field(screen,"plannedInputs").equals(field(screen,"payload")),"resume accepted mismatched complete inputs");
        log("RESUMED SAME PLAN "+ORDER[choice]+" generation="+resumeGeneration+" anchors="+resumed.anchors());
        ok(resumed==plan,"unchanged preview return recomputed the plan");
        ok((Long)stat(InterchangeScreen.class,"generation")==resumeGeneration,"unchanged preview return started a worker generation");
        ok(resumed.legs().size()==plan.legs().size(),"resume lost preview geometry");
        for(int i=0;i<plan.legs().size();i++)ok(resumed.legs().get(i).mesh().samples().equals(plan.legs().get(i).mesh().samples()),"resume changed preview leg "+i);
        var save=(Button)field(screen,"save");ok(save.active,"real save disabled after preview");submitting=screen;repliesBefore=(Long)stat(ClientRoads.class,"acceptedReplies");
        save.onPress();ok((Boolean)field(screen,"pending"),"save button did not submit a request");log("SUBMITTED "+ORDER[choice]+" expectedSameGroup="+group);next(7,20);return;}
      if(stage==7){if((Boolean)field(submitting,"pending"))return;
        ok((Long)stat(ClientRoads.class,"acceptedReplies")>repliesBefore,"save completed without a correlated server reply");
        ok(ClientRoads.error.isEmpty()&&mc.screen==null,"server rejected UI save: "+ClientRoads.error+" / "+field(submitting,"status"));
        var expected=ORDER[choice];var old=group;var targets=fitted.copy();pending=mc.getSingleplayerServer().submit(()->Interchange204SequenceScene.readCompleted(player(mc),old,expected,targets));next(8,12);return;}
      if(stage==8){if(!pending.isDone())return;saved=(Interchange204SequenceScene.State)pending.join();
        ok(group==null||group.equals(saved.group()),"server group identity changed");group=saved.group();completed.add(ORDER[choice]);
        ok(completed.size()==choice+1,"sequence completion skipped a step");seen.addAll(saved.members().stream().map(RoadRecord::id).toList());
        log("SERVER SAVED "+ORDER[choice]+" group="+group+" actualParts="+saved.members().size()+" points="+Arrays.toString(saved.saved().getLongArray("Points"))+" seconds="+(System.nanoTime()-stepStart)/1e9);
        NbtIo.writeCompressed(saved.saved(),OUT.resolve(String.format("%02d-%s-saved.nbt",choice+1,ORDER[choice])).toFile());
        mc.options.hideGui=true;planCamera(mc);next(9,140);return;}
      if(stage==9){if(!rendered()){if(ticks%100==0)log("WAIT RENDER "+ORDER[choice]+" "+renderedDetail);return;}log("RENDER READY "+ORDER[choice]+" "+renderedDetail);ok(true,"target group records and ramp uploads ready");shot(mc,"actually-built");
        if(choice==ORDER.length-1){detailView=0;doubleDetailCamera(mc,detailView);next(10,160);return;}
        // The next editor uses current server Points/Nodes, including STACK's moved endpoints.
        payload=saved.reopen().copy();ok(payload.getUUID("Id").equals(group),"reopen lost assembly identity");choice++;mc.options.hideGui=false;InterchangeScreen.open(payload);
        ok(field(mc.screen,"plan")==null&&calculating((InterchangeScreen)mc.screen),"fresh saved payload reused cached preview");next(2,12);return;}
      if(stage==10){if(!rendered()){stableFrames=0;if(ticks%100==0)log("WAIT DOUBLE DETAIL "+detailView+" "+renderedDetail);return;}
        if(++stableFrames<20)return;log("DOUBLE DETAIL READY "+detailView+" "+renderedDetail);
        shot(mc,detailView==0?"actually-built-AB-trumpet":detailView==1?"actually-built-CD-trumpet":"actually-built-connector");
        if(++detailView==3){finish(mc);return;}doubleDetailCamera(mc,detailView);next(10,160);}
    }catch(Throwable failure){done=true;failure.printStackTrace();try{Files.createDirectories(OUT);if(mc.player!=null&&mc.level!=null)shot(mc,"failure-stage-"+stage);
      Files.writeString(OUT.resolve("failure.txt"),"stage="+stage+" preset="+ORDER[choice]+" checks="+checks+" completed="+completed+"\n"+failure+"\n");}catch(Exception ignored){}mc.stop();}
  }
  private Interchange204SequenceVisual(){}
}
