package com.sora.splineroads.client;

import com.sora.splineroads.net.RoadNetwork;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.world.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = SplineRoads.ID, value = Dist.CLIENT)
public final class ClientRoads {
  @SubscribeEvent public static void curveControl(net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered e){
    var mc=Minecraft.getInstance();
    if(!e.isUseItem()||e.getHand()!=net.minecraft.world.InteractionHand.MAIN_HAND||mc.screen!=null||mc.player==null||mc.level==null||!(mc.player.getMainHandItem().getItem() instanceof RoadTool)||!com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT))return;
    e.setCanceled(true);e.setSwingHand(false);
    if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit){var pos=RoadTool.endpointAtHit(mc.level,hit.getBlockPos(),hit.getLocation());var t=new CompoundTag();t.putString("Action","curveControl");t.putLong("Pos",pos.asLong());beginWorldEdit();sendAction(t);}
  }
  public static final RoadIndex INDEX = new RoadIndex(true);
  static {INDEX.changed=id->{ClientToolIndex.changed(id);SmartSignClient.candidatesChanged(id);SidewalkBlockModels.changed(INDEX,id);};}
  public static CompoundTag draft;
  private static final com.sora.splineroads.core.RoadEditRequests editRequests = new com.sora.splineroads.core.RoadEditRequests();
  private static final Object WORLD_EDITOR = new Object();
  private static long acceptedReplies,staleReplies;

  /** Every generic result is bound to the editor and draft that submitted it. */
  static void sendAction(CompoundTag value) {
    CompoundTag command=value.copy();
    ManualPlannerClient.decorate(command);
    String action=command.getString("Action");
    boolean generic=switch(action){
      case "splitPreview","splitCancel","tollPreview","tollCancelPreview","interchangePreview",
           "laneRampPreview","laneRampCancel","lanePointReady","lanePointResync",
           "roadBatchAck","roadSyncRetry","manualOpen","manualCancelOpen","manualClose","manualRefresh" -> false;
      default -> true;
    };
    if(generic){
      var mc=Minecraft.getInstance();Object owner=mc.screen==null?WORLD_EDITOR:mc.screen;
      if(owner instanceof ManualPlannerScreen)ManualPlannerClient.abandonMapPreview();
      var request=editRequests.submit(owner,action,com.sora.splineroads.net.RoadActionContext.target(command));
      com.sora.splineroads.net.RoadActionContext.attach(command,request);
    }
    RoadNetwork.CHANNEL.sendToServer(new RoadNetwork.Action(command));
  }

  static void beginWorldEdit(){editRequests.reset();editRequests.open(WORLD_EDITOR);}
  static void beginEditor(Object owner){editRequests.open(owner);}
  static boolean ownsPreview(Object owner){return editRequests.owns(owner);}
  static boolean endEditor(Object owner){if(!editRequests.owns(owner))return false;editRequests.reset();return true;}
  static void editorChanged(Object owner){if(editRequests.changed(owner)&&owner instanceof RoadScreen screen)screen.requestSuperseded();}
  static boolean toolScreen(Object screen){
    return screen instanceof ManualPlannerScreen.PointDetails || screen instanceof ManualAccessScreen || screen instanceof ManualPlannerScreen || screen instanceof RoadScreen || screen instanceof RoadDeleteScreen
        || screen instanceof SmartTextScreen || screen instanceof TollScreen || screen instanceof RoadSplitScreen
        || screen instanceof InterchangeScreen || screen instanceof CorridorScreen || screen instanceof LaneRampScreen
        || screen instanceof JunctionScreen || screen instanceof JunctionRoadScreen || screen instanceof RoundaboutScreen
        || screen instanceof ElevatedAccessScreen || screen instanceof LanePointScreen || screen instanceof LaneLineScreen || screen instanceof AttachedPointScreen
        || screen instanceof YJunctionScreen || screen instanceof GantryScreen;
  }
  private static final RoadInbox inbox = new RoadInbox(ClientRoads::recoverInbox,()->ClientRoads.dimension);
  private static void recoverInbox(){TollScreen.deliveryRecovery();LaneRampScreen.deliveryRecovery();CorridorScreen.deliveryRecovery();RoadNetwork.requestRoadSync();}

  public static void fragment(com.sora.splineroads.net.RoadWire.Fragment fragment) {
    ensureWorld();
    inbox.submit(fragment);
  }

  public static void control(com.sora.splineroads.net.RoadWire.Fragment fragment) {
    ensureWorld();inbox.control(fragment,event->{if(event.error()!=null){error=event.error();ManualPlannerClient.deliveryFailure(error);}else receive(event.tag());});
  }

  public static void storeDraft(CompoundTag value) {
    if (value.getBoolean("DeleteOnly")) return;
    if(draft!=null&&!draft.equals(value))editorChanged(Minecraft.getInstance().screen);
    draft = value.copy();

  }

  public static void discardDraft() {

    draft = null;
  }

  public static RoadGeometry.Mesh preview;
  public static List<RoadGeometry.Mesh> nodePreviews = List.of();
  public static boolean conflictPreview;
  public static String error = "";
  private static String dimension = "";
  private static Object currentLevel;
  private static int ticks;
  private static final ArrayDeque<UUID> prune=new ArrayDeque<>();
  private static final Set<Long> residencyChunks=new LinkedHashSet<>();
  private static final Set<UUID> residencyRoads=new LinkedHashSet<>();
  private static final RoadColdSync coldSync=new RoadColdSync();
  private static final LinkedHashMap<UUID,Long> cold=new LinkedHashMap<>();
  private static long coldBytes,coldDerivedBytes,coldHits,pruned,derivedEvictions,coldSyncRequests,coldSyncAcks;
  private static Map<UUID,Long> coldWeights=Map.of();
  private static final Set<UUID> coldGpuTrimmed=new HashSet<>();
  private static int coldCursor;
  private static long coldRevision=-1,activityRevision,accountingPasses,trimVisits,trimNanos;
  private static void coldChanged(){coldRevision=-1;activityRevision++;}
  private static void resetCold(){cold.clear();coldWeights=Map.of();coldGpuTrimmed.clear();coldCursor=0;coldBytes=coldDerivedBytes=0;residencyChunks.clear();residencyRoads.clear();coldSync.clear();coldChanged();}
  static void chunkChanged(long chunk){residencyChunks.add(chunk);RoadRenderer.chunkChanged(chunk);}
  private static boolean chunkLoaded(net.minecraft.client.multiplayer.ClientLevel level,long chunk){
    return level.getChunkSource().getChunk((int)chunk,(int)(chunk>>32),net.minecraft.world.level.chunk.ChunkStatus.FULL,false)!=null;
  }
  private static void refreshResidency(net.minecraft.client.multiplayer.ClientLevel level,UUID id){
    var road=INDEX.roads.get(id);if(road==null)return;
    if(road.chunks.stream().anyMatch(chunk->chunkLoaded(level,chunk)))awaken(id);else cool(id,road);
  }
  static boolean active(UUID id){return INDEX.roads.containsKey(id)&&!cold.containsKey(id);}
  static long visibilityRevision(){return INDEX.contentRevision()+activityRevision;}
  static String cacheMetrics(){return "cold roads="+cold.size()+" sourceMiB="+(coldBytes/1048576)+" renderMiB="+(coldDerivedBytes/1048576)+" renderEvicted="+derivedEvictions+" hits="+coldHits+" evicted="+pruned+" accounting="+accountingPasses+" trimVisits="+trimVisits+" trimMs="+(trimNanos/1000000)+" coldSync="+coldSync.pending()+" "+inbox.metrics();}
  private static void awaken(UUID id){var weight=cold.remove(id);if(weight!=null){coldChanged();coldGpuTrimmed.remove(id);coldBytes-=weight;coldHits++;TollNameRenderer.clear();RoadRenderer.resume(id);}}
  private static void cool(UUID id,RoadIndex.Built road){
    if(cold.containsKey(id))return;
    long weight=4096L+road.mesh.samples().size()*384L+road.record.structures().size()*512L;
    cold.put(id,weight);coldChanged();coldBytes+=weight;TollNameRenderer.clear();RoadRenderer.suspend(id);
  }
  private static void trimCold(){
    if(cold.isEmpty()){coldDerivedBytes=0;coldWeights=Map.of();coldRevision=RoadRenderer.retentionRevision();return;}
    long started=System.nanoTime(),limit=com.sora.splineroads.config.RoadClientConfig.COLD_ROAD_CACHE_MIB.get()*1048576L;
    long observed=RoadRenderer.retentionRevision();
    if(coldRevision!=observed){
      coldWeights=RoadRenderer.retainedRoadBytes(cold.keySet());coldDerivedBytes=0;for(long weight:coldWeights.values())coldDerivedBytes+=weight;
      // If a worker finished while this snapshot was read, the next tick must
      // reconcile it; do not stamp a mixed snapshot with the newer generation.
      coldRevision=observed;accountingPasses++;
    }
    if(coldBytes+coldDerivedBytes<=limit&&cold.size()<=512){trimNanos+=System.nanoTime()-started;return;}
    // A stable cache does no geometry accounting. Under pressure, retire at most
    // four owners per tick and yield after 0.5 ms; shared releases are recounted
    // once on the next tick, not after every victim.
    int work=0;long deadline=System.nanoTime()+500_000L;var owners=new ArrayList<>(cold.keySet());
    for(int checked=0;checked<owners.size()&&checked<16;checked++){
      if(coldBytes+coldDerivedBytes<=limit&&cold.size()<=512)break;
      if(work>=4||checked>0&&System.nanoTime()>=deadline)break;
      UUID id=owners.get(Math.floorMod(coldCursor++,owners.size()));
      // A load event may still be waiting behind other coalesced events. Never
      // evict its source before that event has had a chance to awaken the road.
      var present=INDEX.roads.get(id);var level=Minecraft.getInstance().level;
      if(present!=null&&level!=null&&present.chunks.stream().anyMatch(chunk->chunkLoaded(level,chunk))){awaken(id);continue;}
      trimVisits++;long before=coldWeights.getOrDefault(id,0L);
      if(coldBytes>limit||cold.size()>512){
        var road=INDEX.roads.get(id);if(road!=null)road.chunks.forEach(coldSync::evicted);
        coldDerivedBytes-=before;pruned++;evictSource(id);work++;continue;
      }
      if(before==0)continue;
      if(coldGpuTrimmed.add(id))RoadRenderer.discardColdGpu(id);else {RoadRenderer.discardDerived(id);derivedEvictions++;}
      long after=RoadRenderer.retainedRoadBytes(id);coldDerivedBytes-=Math.max(0,before-after);work++;
    }
    trimNanos+=System.nanoTime()-started;
  }

  @Mod.EventBusSubscriber(
      modid = SplineRoads.ID,
      value = Dist.CLIENT,
      bus = Mod.EventBusSubscriber.Bus.MOD)
  public static final class Setup {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
      e.enqueueWork(
          () -> {
            RoadBlocks.clientIndex = l -> INDEX;
            RoadTool.openClient = ClientRoads::open;RoadSplitTool.activateClient=RoadSplitterClient::activate;
          });
    }
  }

  public static void receive(CompoundTag t) { receive(t, null); }

  private static void receive(CompoundTag t, RoadIndex.Built decoded) {receive(t,decoded,List.of());}
  private static void receive(CompoundTag t, RoadIndex.Built decoded,List<RoadIndex.Built> batch) {receive(t,decoded,batch,null);}
  private static void receive(CompoundTag t, RoadIndex.Built decoded,List<RoadIndex.Built> batch,RoadPreviewData prepared) {
    Minecraft mc = Minecraft.getInstance();
    String type = t.getString("Type");
    if (type.equals("open")) {
      if(t.contains("Dimension")&&(mc.level==null||!mc.level.dimension().location().toString().equals(t.getString("Dimension"))))return;
      open(t.getCompound("Payload"),prepared);
      return;
    }
    if(type.equals("resultPreview")){
      if(t.contains("Dimension")&&(mc.level==null||!mc.level.dimension().location().toString().equals(t.getString("Dimension"))))return;
      if(!t.hasUUID("ReplyId")||t.getBoolean("Success"))return;
      var receipt=editRequests.completed(t.getUUID("ReplyId"),t.getLong("ReplyGeneration"),t.getString("ReplyAction"),t.getString("ReplyTarget"));
      if(receipt==null)return;Object owner=receipt.owner();
      if(mc.screen!=null&&mc.screen!=owner&&toolScreen(mc.screen)&&!ManualPlannerClient.previewing(owner))return;
      // These editors own their special result handling and do not show generic road conflicts.
      if(owner instanceof TollScreen||owner instanceof SmartTextScreen)return;
      var conflicts=prepared==null?conflictRoads(t):prepared.conflicts();if(conflicts.isEmpty())return;
      preview=conflicts.get(0);nodePreviews=conflicts;conflictPreview=true;RoadRenderer.previewCandidates(List.of());
      String message=t.getString("Message");
      if(owner instanceof RoadScreen screen)screen.failureGeometry(conflicts);
      if(owner instanceof LaneRampScreen screen)screen.failureGeometry(message,conflicts);
      return;
    }
    if (type.equals("result")) {
      if(t.contains("Dimension")&&(mc.level==null
          ||!mc.level.dimension().location().toString().equals(t.getString("Dimension"))))return;
      if(ManualPlannerClient.openResult(t))return;
      if(!t.hasUUID("ReplyId"))return;
      var request=editRequests.take(t.getUUID("ReplyId"),t.getLong("ReplyGeneration"),
          t.getString("ReplyAction"),t.getString("ReplyTarget"));
      if(request==null){staleReplies++;return;}
      acceptedReplies++;
      Object owner=request.owner();
      if(mc.screen!=null&&mc.screen!=owner&&toolScreen(mc.screen))return;
      boolean success=t.getBoolean("Success");String message=t.getString("Message");
      if(owner instanceof ManualPlannerScreen.PointDetails screen){screen.result(success,message);return;}
      if(owner instanceof ManualPlannerScreen screen){screen.result(success,message);return;}
      if(owner instanceof SmartTextScreen screen){SmartTextScreen.result(screen,success,message);return;}
      if(owner instanceof TollScreen screen){TollScreen.result(screen,success,message);return;}
      if(success){
        if(owner instanceof net.minecraft.client.gui.screens.Screen screen)ManualPlannerClient.completed(screen);
        if(owner instanceof LaneRampScreen screen)screen.completed();
        if(owner instanceof RoadScreen screen)screen.completed();
        if(owner instanceof InterchangeScreen screen)screen.completed();
        if(owner instanceof CorridorScreen screen)screen.completed();
        if(owner instanceof YJunctionScreen screen)screen.completed();
        if(owner instanceof InterchangeScreen)InterchangeScreen.clear();
        if(owner instanceof LaneRampScreen)LaneRampScreen.clear();
        if(owner instanceof CorridorScreen)CorridorScreen.clear();
        if(owner instanceof JunctionScreen || owner instanceof JunctionRoadScreen)JunctionScreen.clear();
        if(owner instanceof RoadScreen || owner instanceof RoadDeleteScreen)discardDraft();
        preview=null;nodePreviews=List.of();conflictPreview=false;error="";
        RoadRenderer.previewCandidates(List.of());
        if(mc.screen==owner)mc.setScreen(null);
      }else{
        error=message;
        var conflicts=prepared==null?conflictRoads(t):prepared.conflicts();
        if(!conflicts.isEmpty()){
          preview=conflicts.get(0);nodePreviews=conflicts;conflictPreview=true;
          RoadRenderer.previewCandidates(List.of());
        }
        // The original owner may be closed. Its diagnostic still belongs to the
        // current edit, but cannot attach itself to an editor opened afterwards.
        if(owner instanceof InterchangeScreen screen)screen.failed(error);
        if(owner instanceof CorridorScreen screen)screen.failed(error);
        if(owner instanceof RoadScreen screen)screen.failed(error,conflicts);
        if(owner instanceof LaneLineScreen screen)screen.failed(error);
        if(owner instanceof ElevatedAccessScreen screen)screen.failed(error);
        if(owner instanceof AttachedPointScreen screen)screen.failed(error);
        if(owner instanceof LanePointScreen screen)screen.failed(error);
        if(owner instanceof LaneRampScreen screen)screen.failed(error,conflicts);
        if(owner instanceof JunctionRoadScreen screen)screen.failed(error);
        if(owner instanceof YJunctionScreen screen)screen.failed(error);
        if(owner instanceof GantryScreen screen)screen.failed(error);
        if(owner instanceof ManualAccessScreen screen)screen.failed(error);
        if(owner instanceof JunctionScreen screen)screen.failed(error);
        if(owner instanceof RoundaboutScreen screen)screen.failed(error);
        if(owner instanceof RoadDeleteScreen screen)screen.failed(error);
      }
      return;
    }
    if (mc.level == null
        || !mc.level.dimension().location().toString().equals(t.getString("Dimension"))) return;
    ensureWorld();
    try {
      if(type.equals("syncReset")){
        // The inbox has discarded queued tool replies with its old epoch.
        // Release preview-only waits; this ACK must not request another sync.
        TollScreen.deliveryRecovery();LaneRampScreen.deliveryRecovery();CorridorScreen.deliveryRecovery();
        if(!t.getBoolean("ResetRoads")){if(coldSync.pending())coldSyncAcks++;coldSync.acknowledged();}
        if(t.getBoolean("ResetRoads")){RoadRenderer.reset();INDEX.clear();SidewalkBlockModels.clear();ClientToolIndex.clear();prune.clear();resetCold();SmartSignClient.clear();receivedBatches.clear();}
        return;
      }
      if(type.equals("roadBatch")){receiveBatch(t,batch);return;}
      if(type.equals("smartSigns")){SmartSignClient.receive(t);return;}
      if (type.equals("road")) {
        RoadRecord r = decoded == null ? RoadRecord.load(t.getCompound("Road")) : decoded.record;
        var previous = INDEX.roads.get(r.id());
        awaken(r.id());
        residencyRoads.add(r.id());
        if (previous == null || !previous.record.equals(r)) {
          Set<UUID> changed = new HashSet<>();
          changed.add(r.id());
          if (previous != null) INDEX.surfaceNeighbors(previous).forEach(b -> changed.add(b.record.id()));
          var built = decoded == null ? new RoadIndex.Built(r, true) : decoded;
          SmartSignClient.roadChanged(r.id());INDEX.put(built);SidewalkBlockModels.changed(INDEX,built.record.id());residencyRoads.add(r.id());
          INDEX.surfaceNeighbors(built).forEach(b -> changed.add(b.record.id()));
          RoadRenderer.changed(changed);
        }

      } else if (type.equals("lanePoints")) {
        var old=INDEX.roads.get(t.getUUID("Id"));
        if(old!=null&&old.record.header().hashCode()==t.getInt("Signature"))
          INDEX.lanePoints(old.record.id(),LanePointCodec.read(t.getCompound("Points")));
        else {
          var request=new CompoundTag();request.putString("Action","lanePointResync");request.putUUID("Id",t.getUUID("Id"));
          com.sora.splineroads.net.RoadNetwork.CHANNEL.sendToServer(new com.sora.splineroads.net.RoadNetwork.Action(request));
        }
      } else if (type.equals("delete")) {
        remove(t.getUUID("Id"));
        if (draft != null && draft.hasUUID("Id") && draft.getUUID("Id").equals(t.getUUID("Id"))) {
          preview = null;
        }
      }
    } catch (IllegalArgumentException e) {
      error = e.getMessage();
    }
  }

  static List<RoadGeometry.Mesh> conflictRoads(CompoundTag reply){
    var roads=new ArrayList<RoadGeometry.Mesh>();
    for(var value:reply.getList("ConflictRoads",net.minecraft.nbt.Tag.TAG_COMPOUND))
      try{roads.add(RoadRecord.load((CompoundTag)value).mesh());}catch(IllegalArgumentException ignored){}
    return List.copyOf(roads);
  }

  private static final LinkedHashSet<UUID> receivedBatches=new LinkedHashSet<>();
  private static void receiveBatch(CompoundTag packet,List<RoadIndex.Built> decoded){
    UUID token=packet.getUUID("Batch");if(receivedBatches.contains(token)){RoadNetwork.ackBatch(token);return;}
    var batch=decoded.isEmpty()?packet.getList("Roads",net.minecraft.nbt.Tag.TAG_COMPOUND).stream().map(t->new RoadIndex.Built(RoadRecord.load((CompoundTag)t),true)).toList():decoded;

    var ids=new HashSet<UUID>();for(var r:batch)ids.add(r.record.id());var changed=new HashSet<>(ids);
    for(var entry:packet.getList("Replaced",net.minecraft.nbt.Tag.TAG_COMPOUND)){UUID id=((CompoundTag)entry).getUUID("Id");changed.add(id);var old=INDEX.roads.get(id);if(old!=null)INDEX.surfaceNeighbors(old).forEach(r->changed.add(r.record.id()));if(!ids.contains(id)){var weight=cold.remove(id);if(weight!=null){coldBytes-=weight;coldChanged();coldGpuTrimmed.remove(id);}INDEX.remove(id);SmartSignClient.remove(id);}}
    for(var r:batch){awaken(r.record.id());SmartSignClient.roadChanged(r.record.id());INDEX.put(r);SidewalkBlockModels.changed(INDEX,r.record.id());residencyRoads.add(r.record.id());}
    for(var r:batch)INDEX.surfaceNeighbors(r).forEach(b->changed.add(b.record.id()));
    for(var entry:packet.getList("SignPackets",net.minecraft.nbt.Tag.TAG_COMPOUND))SmartSignClient.receive((CompoundTag)entry);
    var level=Minecraft.getInstance().level;if(level!=null&&packet.contains("Node")){var pos=net.minecraft.core.BlockPos.of(packet.getLong("NodePos"));if(level.getBlockEntity(pos) instanceof NodeEntity marker)marker.load(packet.getCompound("Node"));}
    RoadRenderer.changedBatch(changed,ids);receivedBatches.add(token);while(receivedBatches.size()>256)receivedBatches.remove(receivedBatches.iterator().next());RoadNetwork.ackBatch(token);
  }
  private static void remove(UUID id) {remove(id,false);}
  private static void evictSource(UUID id) {remove(id,true);}
  private static void remove(UUID id,boolean localEviction) {
    var weight=cold.remove(id);if(weight!=null){coldBytes-=weight;coldChanged();coldGpuTrimmed.remove(id);}
    var old = INDEX.roads.get(id);
    Set<UUID> changed = new HashSet<>();
    changed.add(id);
    if (old != null) INDEX.surfaceNeighbors(old).forEach(b -> changed.add(b.record.id()));
    INDEX.remove(id);if(localEviction)SmartSignClient.evictSource(id);else SmartSignClient.remove(id);
    if(localEviction)RoadRenderer.sourceEvicted(changed);else RoadRenderer.changed(changed);
  }

  private static void ensureWorld() {
    Minecraft mc = Minecraft.getInstance();
    String d = mc.level == null ? "" : mc.level.dimension().location().toString();
    if (currentLevel != mc.level || !d.equals(dimension)) {
      InterchangeScreen.clear();TollScreen.clear();RailbedScreen.reset();
      LaneRampScreen.clear();
      CorridorScreen.clear();
      JunctionScreen.clear();TollScreen.clear();
      receivedBatches.clear();

      RoadRenderer.reset();
      INDEX.clear();SidewalkBlockModels.clear();ClientToolIndex.clear();prune.clear();resetCold();SmartSignClient.clear();

      inbox.reset();
      editRequests.reset();
      discardDraft();
      preview = null;
      nodePreviews=List.of();conflictPreview=false;
      error = "";
      dimension = d;
      currentLevel = mc.level;
    }
  }

  public static void open(CompoundTag t) {
    open(t,null);
  }
  private static void open(CompoundTag t,RoadPreviewData prepared) {
    ensureWorld();
    Minecraft mc = Minecraft.getInstance();
    if(ManualPlannerClient.receive(t))return;
    if(t.hasUUID("ReplyId")){
      var request=editRequests.take(t.getUUID("ReplyId"),t.getLong("ReplyGeneration"),t.getString("ReplyAction"),t.getString("ReplyTarget"));
      if(request==null||mc.screen!=null&&mc.screen!=request.owner()&&toolScreen(mc.screen)&&!ManualPlannerClient.previewing(request.owner())){staleReplies++;return;}
      acceptedReplies++;
    }
    String kind = t.getString("Kind");
    if(kind.equals("reset")||kind.endsWith("Reset")&&!kind.equals("laneRampReset")&&!kind.equals("tollReset"))editRequests.reset();
    // A reply updates its existing request owner; it does not open a new editor.
    // In particular, an ignored late reply must not erase another road's failure.
    switch(kind){
      case "splitCheck" -> {RoadSplitterClient.checked(t);return;}
      case "tollCheck" -> {TollScreen.open(t,prepared);return;}
      case "corridorCheck" -> {CorridorScreen.open(t);return;}
      case "laneRampCheck" -> {LaneRampScreen.checkedReply(t,prepared);return;}
    }
    var previousScreen=mc.screen;var previousPreview=preview;var previousRoads=nodePreviews;
    String previousError=error;boolean previousConflict=conflictPreview;
    dispatchOpen(t);
    ManualPlannerClient.opened(t);
    // Reset and a resume without a draft do not acquire a different screen.
    // Keep geometry published by the new editor, clearing only inherited red
    // geometry that it did not replace. LaneRampScreen restores its own saved
    // conflict preview in open/resume and remains responsible for that state.
    if(mc.screen!=null&&mc.screen!=previousScreen){
      beginEditor(mc.screen);
      if(!(mc.screen instanceof LaneRampScreen)){
        conflictPreview=false;
        if(previousConflict&&preview==previousPreview)preview=null;
        if(previousConflict&&nodePreviews==previousRoads)nodePreviews=List.of();
        if(previousConflict&&error==previousError)error="";
      }
    }
  }

  private static void dispatchOpen(CompoundTag t) {
    Minecraft mc = Minecraft.getInstance();
    String kind = t.getString("Kind");
    if(kind.equals("elevatedAccess")){mc.setScreen(new ElevatedAccessScreen(t));return;}
    if(kind.equals("manualAccess")){mc.setScreen(new ManualAccessScreen(t));return;}
    if(kind.startsWith("railbed")){RailbedScreen.open(t);return;}
    if(kind.startsWith("toll")){TollScreen.open(t);return;}
    if(kind.equals("laneRampReset")){if(LaneRampScreen.clear())error="";if(mc.screen instanceof LaneRampScreen)mc.setScreen(null);return;}
    if(kind.equals("lanePoint")){
      mc.setScreen(new LanePointScreen(t));
      if(t.hasUUID("Interaction")){var ack=new CompoundTag();ack.putString("Action","lanePointReady");ack.putUUID("Interaction",t.getUUID("Interaction"));com.sora.splineroads.net.RoadNetwork.CHANNEL.sendToServer(new com.sora.splineroads.net.RoadNetwork.Action(ack));}
      return;
    }
    if(kind.equals("laneRamp")){LaneRampScreen.open(t);return;}
    if(kind.equals("laneRampResume")){LaneRampScreen.resume(t);return;}
    if(kind.equals("armRoad")||kind.equals("sideRoad")){Minecraft.getInstance().setScreen(new RoadScreen(t));return;}
    if(kind.equals("attachedPoint")){Minecraft.getInstance().setScreen(new AttachedPointScreen(t));return;}
    if(kind.equals("laneLines")){Minecraft.getInstance().setScreen(new LaneLineScreen(t));return;}
    if(kind.equals("yJunction")){Minecraft.getInstance().setScreen(new YJunctionScreen(t));return;}
    if(kind.startsWith("smartText")){SmartTextScreen.open(t);return;}
    if(kind.equals("gantry")){Minecraft.getInstance().setScreen(new GantryScreen(t));return;}
    if(kind.equals("roundabout")){Minecraft.getInstance().setScreen(new RoundaboutScreen(t));return;}
    if(kind.startsWith("corridor")){CorridorScreen.open(t);return;}
    if (kind.startsWith("junction")) { JunctionScreen.open(t); return; }
    if (kind.startsWith("interchange")) {
      InterchangeScreen.open(t);
      return;
    }
    if (kind.equals("reset")) {
      editRequests.reset();


      discardDraft();
      preview = null;
      nodePreviews=List.of();conflictPreview=false;
      error = "";
      return;
    }
    if (kind.equals("resume")) {
      CompoundTag saved = draft;
      if (saved != null) {
        draft = saved.copy();
        mc.setScreen(new RoadScreen(draft));
      } else if (mc.player != null)
        mc.player.displayClientMessage(Component.literal("先右键选择两个道路端点"), true);
      return;
    }
    if (t.getBoolean("DeleteOnly")) {
      preview = null;
      nodePreviews=List.of();conflictPreview=false;
      error = "";
      mc.setScreen(new RoadDeleteScreen(t));
      return;
    }
    t = com.sora.splineroads.net.RoadDraft.merge(t, draft);
    storeDraft(t);
    preview = null;
    error = "";
    mc.setScreen(new RoadScreen(draft));
  }

  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
    InterchangeScreen.clear();TollScreen.clear();RailbedScreen.reset();
      LaneRampScreen.clear();
      CorridorScreen.clear();
    RoadRenderer.reset();
    INDEX.clear();SidewalkBlockModels.clear();ClientToolIndex.clear();prune.clear();resetCold();SmartSignClient.clear();

    inbox.reset();
    editRequests.reset();
    discardDraft();
    preview = null;
    nodePreviews=List.of();conflictPreview=false;
    error = "";
    dimension = "";
    currentLevel = null;
  }

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    if (e.phase != TickEvent.Phase.END) return;
    ensureWorld();
    inbox.drain(event -> {
      if (event.error() != null) error = event.error();
      else receive(event.tag(), event.built(),event.batch(),event.preview());
    });
    var level = Minecraft.getInstance().level;
    if (level == null) return;
    long streamDeadline=System.nanoTime()+500_000;
    var chunks=new RoadScanBudget(16,streamDeadline);
    while(!residencyChunks.isEmpty()&&chunks.take()){
      var it=residencyChunks.iterator();long chunk=it.next();it.remove();
      residencyRoads.addAll(INDEX.inChunk(chunk));
      if(chunkLoaded(level,chunk))coldSync.arrived(chunk);
    }
    var roads=new RoadScanBudget(16,streamDeadline);
    while(!residencyRoads.isEmpty()&&roads.take()){
      var it=residencyRoads.iterator();UUID id=it.next();it.remove();refreshResidency(level,id);
    }
    if(++ticks%100==0&&prune.isEmpty())prune.addAll(INDEX.roads.keySet());
    long deadline=System.nanoTime()+500_000;
    for(int n=0;n<4&&!prune.isEmpty()&&System.nanoTime()<deadline;n++){
      refreshResidency(level,prune.removeFirst());
    }
    trimCold();
    // The server can still be watching a client-cache-evicted chunk after a
    // render-distance change. Keep retrying the existing soft resync until its
    // acknowledgement arrives; a throttled request must not strand lost roads.
    if(coldSync.request(level.getGameTime())){coldSyncRequests++;RoadNetwork.requestRoadSync();}
  }
}
