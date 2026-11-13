package com.sora.splineroads.net;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.client.ClientRoads;
import com.sora.splineroads.world.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

public final class RoadNetwork {
  private static final String PROTOCOL = "128";
  public static final SimpleChannel CHANNEL =
      NetworkRegistry.newSimpleChannel(
          ResourceLocation.fromNamespaceAndPath(SplineRoads.ID, "roads"),
          () -> PROTOCOL,
          PROTOCOL::equals,
          PROTOCOL::equals);

  public record ServerMessage(RoadWire.Fragment fragment) {
    public void encode(FriendlyByteBuf buf) {
      buf.writeUUID(fragment.id());
      buf.writeVarInt(fragment.index());
      buf.writeVarInt(fragment.count());
      buf.writeByteArray(fragment.bytes());
    }

    public static ServerMessage decode(FriendlyByteBuf buf) {
      return new ServerMessage(
          new RoadWire.Fragment(
              buf.readUUID(),
              buf.readVarInt(),
              buf.readVarInt(),
              buf.readByteArray(RoadWire.PART_BYTES)));
    }

    public static void handle(ServerMessage msg, Supplier<NetworkEvent.Context> supplier) {
      var ctx = supplier.get();
      ctx.enqueueWork(
          () ->
              DistExecutor.unsafeRunWhenOn(
                  Dist.CLIENT, () -> () -> ClientRoads.fragment(msg.fragment)));
      ctx.setPacketHandled(true);
    }
  }

  /** Small tool replies use their own ordered stream, independent of road decoding. */
  public record ControlMessage(RoadWire.Fragment fragment) {
    public void encode(FriendlyByteBuf buf){new ServerMessage(fragment).encode(buf);}
    public static ControlMessage decode(FriendlyByteBuf buf){return new ControlMessage(ServerMessage.decode(buf).fragment());}
    public static void handle(ControlMessage msg,Supplier<NetworkEvent.Context> supplier){
      var ctx=supplier.get();ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->ClientRoads.control(msg.fragment())));ctx.setPacketHandled(true);
    }
  }

  public record Action(CompoundTag tag) {
    public void encode(FriendlyByteBuf buf) {
      CompoundTag command=tag.copy();
      command.remove("JunctionGraph"); // Preview context is rebuilt from authoritative roads.
      buf.writeNbt(command);
    }

    public static Action decode(FriendlyByteBuf buf) {
      if (buf.readableBytes() > 65536) throw new IllegalArgumentException("道路操作消息过大");
      CompoundTag t = buf.readNbt();
      return new Action(t == null ? new CompoundTag() : t);
    }

    public static void handle(Action msg, Supplier<NetworkEvent.Context> supplier) {
      var ctx = supplier.get();
      // The server can be inside pre-write validation. Cancellation must not queue
      // behind that validation: this path touches only the owner's atomic job token,
      // never world, inventory or UI state on the network thread.
      if(msg.tag.getString("Action").equals("laneRampCancel")){
        var sender=ctx.getSender();
        if(sender!=null){
          UUID owner=sender.getUUID();long request=msg.tag.getLong("Request");
          RoadPlanningJobs.cancel(owner,request);
          // Also cover a preview packet already queued but not registered yet.
          // This nonblocking recheck follows its begin task in packet order.
          ctx.enqueueWork(()->RoadPlanningJobs.cancel(owner,request));
        }
      }else if(msg.tag.getString("Action").equals("railbedCancel")&&msg.tag.hasUUID("Token")){
        var sender=ctx.getSender();if(sender!=null)RailbedTool.cancelSignal(sender.getUUID(),msg.tag.getUUID("Token"));
        ctx.enqueueWork(()->{if(ctx.getSender()!=null)act(ctx.getSender(),msg.tag);});
      }else ctx.enqueueWork(
          () -> {
            if (ctx.getSender() != null) act(ctx.getSender(), msg.tag);
          });
      ctx.setPacketHandled(true);
    }
  }

  public static void register() {
    CHANNEL.registerMessage(2,ControlMessage.class,ControlMessage::encode,ControlMessage::decode,ControlMessage::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    CHANNEL.registerMessage(
        0,
        ServerMessage.class,
        ServerMessage::encode,
        ServerMessage::decode,
        ServerMessage::handle,
        Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    CHANNEL.registerMessage(
        1,
        Action.class,
        Action::encode,
        Action::decode,
        Action::handle,
        Optional.of(NetworkDirection.PLAY_TO_SERVER));
  }

  public static void send(ServerPlayer player, CompoundTag t) {
    if(t.getString("Type").equals("result")&&!t.getList("ConflictRoads",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty()){
      // Complete the operation independently of optional, heavy diagnostic geometry.
      var header=t.copy();header.remove("ConflictRoads");send(player,header);
      var preview=t.copy();preview.putString("Type","resultPreview");send(player,preview);return;
    }
    boolean control=Set.of("open","result","delete","batchBarrier","syncReset","cancelTransfer").contains(t.getString("Type"))
        &&!com.sora.splineroads.client.RoadPreviewData.queued(t);
    for (var fragment : RoadWire.split(t))
      if(control)CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new ControlMessage(fragment));
      else CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ServerMessage(fragment));
  }

  public static void open(ServerPlayer p, CompoundTag payload) {
    ManualPlanner.decorate(p,payload);
    CompoundTag t = new CompoundTag();
    t.putString("Type", "open");
    t.putString("Dimension",p.serverLevel().dimension().location().toString());
    t.put("Payload", payload);
    send(p, t);
  }

  private static final class Watching {
    final String dimension;
    final Set<Long> chunks = new HashSet<>();
    final Map<UUID, RoadRecord> sent = new HashMap<>();
    final Map<UUID, Transfer> pending = new LinkedHashMap<>();
    final Set<UUID> pendingSigns=new LinkedHashSet<>();
    final Set<UUID> forceSigns=new HashSet<>();
    final Set<UUID> activeSigns=new HashSet<>();
    final Map<UUID,Long> sentSigns=new HashMap<>();
    final Map<UUID, SignTransfer> signTransfers = new LinkedHashMap<>();
    final Map<UUID, PointTransfer> pointTransfers = new LinkedHashMap<>();
    BatchTransfer batch;
    long lastResync = Long.MIN_VALUE / 2;
    long lastServed, maxWaitTicks;
    final RoadPublicationBudget publication=new RoadPublicationBudget();
    UUID activeRoad,activeSign,activePoints;
    int signPreparations;


    Watching(String dimension) {
      this.dimension = dimension;
    }
  }

  private static final class Transfer {
    final RoadRecord record;List<RoadWire.Fragment> parts;int next;
    Transfer(RoadRecord record){this.record=record;}
  }
  private static final class SignTransfer {
    final UUID road; final long version; final boolean active; final List<com.sora.splineroads.core.SmartSigns.State> states;
    java.util.concurrent.CompletableFuture<List<RoadWire.Fragment>> encoded;
    List<RoadWire.Fragment> parts; int next;
    SignTransfer(UUID road,long version,List<com.sora.splineroads.core.SmartSigns.State> states){this.road=road;this.version=version;this.states=List.copyOf(states);this.active=states.stream().anyMatch(s->s.mode()!=com.sora.splineroads.core.SmartSigns.Mode.NORMAL);}
  }
  private static final class PointTransfer {
    final RoadRecord before,after;
    java.util.concurrent.CompletableFuture<List<RoadWire.Fragment>> encoded;
    List<RoadWire.Fragment> parts;int next;
    PointTransfer(RoadRecord before,RoadRecord after){this.before=before;this.after=after;}
  }
  private static final class BatchTransfer {
    final Set<UUID> ids; final Map<UUID,RoadRecord> records;
    final Map<UUID,Long> signVersions; final BlockPos node; final CompoundTag packet;
    java.util.concurrent.CompletableFuture<List<RoadWire.Fragment>> encoded;
    List<RoadWire.Fragment> parts; int next; long sentTick, historyBytes, checkedContentRevision=-1; final int generations;
    BatchTransfer(Set<UUID> ids,Map<UUID,RoadRecord> records,Map<UUID,Long> signs,BlockPos node,CompoundTag packet,List<RoadWire.Fragment> parts,int generations,long historyBytes){
      this.ids=Set.copyOf(ids);this.records=Map.copyOf(records);this.signVersions=Map.copyOf(signs);this.node=node;this.packet=packet;this.parts=parts;this.generations=generations;this.historyBytes=historyBytes;
    }
  }
  private static int playerCursor,levelCursor;
  private static long sentBytes, sentFragments, publicationNanos, publicationMaxNanos, maintenanceNanos, maintenanceMaxNanos, resyncs, hardResyncs;
  private static final RoadPacketCache PACKETS=new RoadPacketCache();
  private static final Map<UUID, Watching> WATCHING = new HashMap<>();

  private static Watching watching(ServerPlayer player) {
    String dimension = player.level().dimension().location().toString();
    Watching state = WATCHING.get(player.getUUID());
    if (state == null || !state.dimension.equals(dimension)) {
      state = new Watching(dimension);
      WATCHING.put(player.getUUID(), state);
    }
    return state;
  }

  public static void dimensionChanged(ServerPlayer player) {
    RoadPlanningJobs.cancel(player.getUUID());
    watching(player);
    LAST_ACTION.remove(player.getUUID());
    LAST_PREVIEW.remove(player.getUUID());
  }

  public static void watch(ServerPlayer player, long chunk, boolean added) {
    var state = watching(player);
    if (added) state.chunks.add(chunk);
    else {
      state.chunks.remove(chunk);
      var index = RoadData.get(player.serverLevel()).index;
      for(UUID id:index.inChunk(chunk)) {
        var built=index.roads.get(id);
        if(built==null||Collections.disjoint(built.chunks,state.chunks))forgetTransport(player,state,id);
      }
    }
  }

  public static void broadcastSmart(ServerLevel level,UUID id){
    var built=RoadData.get(level).index.roads.get(id);
    for(var p:level.players())if(built!=null&&!Collections.disjoint(built.chunks,watching(p).chunks))watching(p).pendingSigns.add(id);
  }
  public static void road(ServerPlayer p, RoadRecord r) {
    var state=watching(p);
    if(state.batch!=null&&state.batch.ids.contains(r.id())){
      if(state.batch.records.get(r.id())!=r)refreshBatch(p,state,state.batch.ids,state.batch.node,null);
      return;
    }
    var points=state.pointTransfers.remove(r.id());if(points!=null)cancelTransfer(p,points.parts,points.next,r.id());
    if(r==state.sent.get(r.id()))return;
    var pending=state.pending.get(r.id());
    if(pending==null||pending.record!=r){
      if(pending!=null)cancelTransfer(p,pending.parts,pending.next,r.id());
      state.pending.put(r.id(),new Transfer(r));state.pendingSigns.add(r.id());
    }
  }

  private static void cancelTransfer(ServerPlayer player,List<RoadWire.Fragment> parts,int next,UUID road){
    if(parts==null||parts.isEmpty()||next==0)return;
    var tag=new CompoundTag();tag.putString("Type","cancelTransfer");tag.putString("Dimension",watching(player).dimension);
    tag.putUUID("Transfer",parts.get(0).id());if(road!=null)tag.putUUID("Id",road);send(player,tag);
  }
  private static int fragment(ServerPlayer player,RoadWire.Fragment fragment){
    CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new ServerMessage(fragment));
    sentBytes+=fragment.bytes().length;sentFragments++;return fragment.bytes().length;
  }
  private static boolean ready(java.util.concurrent.CompletableFuture<?> value){return value!=null&&value.isDone();}

  /** World maintenance and transport have separate bounded turns and observable costs. */
  public static void tick(net.minecraft.server.MinecraftServer server){
    long maintained=System.nanoTime(),maintenanceDeadline=maintained+2_000_000L;
    var levels=new ArrayList<ServerLevel>();server.getAllLevels().forEach(levels::add);
    if(!levels.isEmpty()){
      int start=Math.floorMod(levelCursor,levels.size()),visited=0;
      for(int offset=0;offset<levels.size()&&System.nanoTime()<maintenanceDeadline;offset++){
        int position=(start+offset)%levels.size();levelCursor=(position+1)%levels.size();visited++;
        var level=levels.get(position);RoadData.get(level).smartSigns.tick(level,maintenanceDeadline);
      }
      if(visited==levels.size())levelCursor=(start+1)%levels.size();
    }
    long maintenance=System.nanoTime()-maintained;maintenanceNanos+=maintenance;maintenanceMaxNanos=Math.max(maintenanceMaxNanos,maintenance);
    long started=System.nanoTime();
    try{publishPlayers(server.getPlayerList().getPlayers(),started+1_500_000L);}
    finally{
      long elapsed=System.nanoTime()-started;publicationNanos+=elapsed;publicationMaxNanos=Math.max(publicationMaxNanos,elapsed);
      if(Boolean.getBoolean("sr.profile")&&server.getTickCount()%200==0)System.getLogger("SplineRoads/network").log(System.Logger.Level.INFO,metrics()+" "+RoadPlanningJobs.metrics());
    }
  }

  /** Package scope lets actual Forge connection tests exercise the production scheduler. */
  static void publishPlayers(List<ServerPlayer> players,long deadline){
    int count=players.size();if(count==0)return;
    int start=Math.floorMod(playerCursor,count),served=0;
    for(int offset=0;offset<count&&System.nanoTime()<deadline;offset++){
      int position=(start+offset)%count;playerCursor=(position+1)%count;served++;
      var player=players.get(position);var state=watching(player);
      long tick=player.serverLevel().getGameTime();if(state.lastServed!=0)state.maxWaitTicks=Math.max(state.maxWaitTicks,tick-state.lastServed);state.lastServed=tick;
      publish(player,state,deadline,tick);
    }
    // Even a complete pass rotates its starting player: the shared encoder may
    // have only four vacancies, so repeatedly starting player zero is not fair.
    if(served==count)playerCursor=(start+1)%count;
  }

  private static void publish(ServerPlayer player,Watching state,long deadline,long tick){
    var data=RoadData.get(player.serverLevel());state.signPreparations=0;
    state.publication.publish(deadline,(family,remaining)->switch(family){
      case 0 -> publishBatch(player,state,data,deadline,tick,remaining);
      case 1 -> publishPoints(player,state,data,deadline,remaining);
      case 2 -> publishSign(player,state,data,deadline,remaining);
      default -> publishRoad(player,state,data,deadline,remaining);
    });
  }

  /** Rotate unready/stale entries, but finish the one active fragment stream per
   * family before opening another. At most four partials can accumulate in the
   * ordered client assembler (four maximum payloads fit its 16 MiB limit). */
  private static <T> Map.Entry<UUID,T> next(Map<UUID,T> queued,UUID active){
    if(active!=null&&queued.containsKey(active))return Map.entry(active,queued.get(active));
    if(queued.isEmpty())return null;
    var it=queued.entrySet().iterator();var entry=it.next();UUID id=entry.getKey();T value=entry.getValue();it.remove();queued.put(id,value);return Map.entry(id,value);
  }
  private static boolean canSend(List<RoadWire.Fragment> parts,int next,int remaining,long deadline){
    return parts!=null&&next<parts.size()&&parts.get(next).bytes().length<=remaining&&System.nanoTime()<deadline;
  }
  private static int publishBatch(ServerPlayer player,Watching state,RoadData data,long deadline,long tick,int remaining){
    var batch=state.batch;if(batch==null)return 0;
    long revision=data.index.contentRevision();boolean stale=false;
    if(batch.checkedContentRevision!=revision){
      for(UUID id:batch.ids){var current=data.index.roads.get(id);if(batch.records.get(id)!=(current==null?null:current.record)){stale=true;break;}}
      if(!stale)batch.checkedContentRevision=revision;
    }
    if(stale||batch.sentTick!=0&&tick-batch.sentTick>100){refreshBatch(player,state,batch.ids,batch.node,null);batch=state.batch;if(batch==null)return 0;}
    if(batch.parts==null){
      if(batch.encoded==null)batch.encoded=PACKETS.requestTag(batch.packet);
      if(!ready(batch.encoded))return 0;
      try{batch.parts=RoadWire.reidentify(batch.encoded.join());batch.historyBytes=Math.max(batch.historyBytes,packedBytes(batch.parts));}
      catch(java.util.concurrent.CompletionException|java.util.concurrent.CancellationException e){resync(player,state,true);return 0;}
    }
    if(!canSend(batch.parts,batch.next,remaining,deadline))return 0;
    int bytes=fragment(player,batch.parts.get(batch.next++));if(batch.next==batch.parts.size()&&batch.sentTick==0)batch.sentTick=tick;return bytes;
  }
  static CompoundTag pointPacket(String dimension,RoadRecord before,RoadRecord after){
    var tag=new CompoundTag();tag.putString("Type","lanePoints");tag.putString("Dimension",dimension);tag.putUUID("Id",before.id());
    tag.putInt("Signature",before.header().hashCode());tag.put("Points",LanePointCodec.write(LaneTopology.metadata(after)));return tag;
  }
  private static int publishPoints(ServerPlayer player,Watching state,RoadData data,long deadline,int remaining){
    var entry=next(state.pointTransfers,state.activePoints);if(entry==null){state.activePoints=null;return 0;}
    var transfer=entry.getValue();var built=data.index.roads.get(entry.getKey());
    if(built==null||built.record!=transfer.after||Collections.disjoint(built.chunks,state.chunks)){
      cancelTransfer(player,transfer.parts,transfer.next,entry.getKey());state.pointTransfers.remove(entry.getKey());state.activePoints=null;return 0;
    }
    if(transfer.parts==null){
      if(transfer.encoded==null){String dimension=state.dimension;var before=transfer.before;var after=transfer.after;transfer.encoded=PACKETS.requestTag(()->pointPacket(dimension,before,after));}
      if(!ready(transfer.encoded))return 0;
      try{transfer.parts=RoadWire.reidentify(transfer.encoded.join());}
      catch(java.util.concurrent.CompletionException|java.util.concurrent.CancellationException e){state.pointTransfers.remove(entry.getKey());state.pending.put(entry.getKey(),new Transfer(transfer.after));state.activePoints=null;return 0;}
    }
    if(!canSend(transfer.parts,transfer.next,remaining,deadline))return 0;
    int bytes=fragment(player,transfer.parts.get(transfer.next++));state.activePoints=entry.getKey();
    if(transfer.next==transfer.parts.size()){state.sent.put(entry.getKey(),transfer.after);state.pointTransfers.remove(entry.getKey());state.activePoints=null;}return bytes;
  }
  private static void prepareSign(ServerPlayer player,Watching state,RoadData data){
    if(state.pendingSigns.isEmpty()||state.signPreparations++>=8)return;
    var it=state.pendingSigns.iterator();UUID id=it.next();it.remove();
    if(state.batch!=null&&state.batch.ids.contains(id)||state.signTransfers.size()>=64&&!state.signTransfers.containsKey(id)){state.pendingSigns.add(id);return;}
    var built=data.index.roads.get(id);
    if(built==null||Collections.disjoint(built.chunks,state.chunks)){state.sentSigns.remove(id);state.activeSigns.remove(id);return;}
    long version=data.smartSigns.version(id);if(!state.forceSigns.contains(id)&&Objects.equals(state.sentSigns.get(id),version))return;
    var old=state.signTransfers.get(id);if(old!=null&&old.version==version)return;
    if(old!=null)cancelTransfer(player,old.parts,old.next,id);
    var transfer=new SignTransfer(id,version,data.smartSigns.road(id));
    // Unwatch forgets transport history, while the client may retain its cold
    // semantic state. A new subscription needs a full baseline even when empty.
    if(transfer.active||state.activeSigns.contains(id)||state.forceSigns.contains(id)||!state.sentSigns.containsKey(id))state.signTransfers.put(id,transfer);else state.sentSigns.put(id,version);
  }
  private static int publishSign(ServerPlayer player,Watching state,RoadData data,long deadline,int remaining){
    prepareSign(player,state,data);
    var entry=next(state.signTransfers,state.activeSign);if(entry==null){state.activeSign=null;return 0;}
    var transfer=entry.getValue();var built=data.index.roads.get(entry.getKey());
    if(built==null||Collections.disjoint(built.chunks,state.chunks)||data.smartSigns.version(entry.getKey())!=transfer.version){
      cancelTransfer(player,transfer.parts,transfer.next,entry.getKey());state.signTransfers.remove(entry.getKey());state.activeSign=null;if(built!=null)state.pendingSigns.add(entry.getKey());return 0;
    }
    if(transfer.parts==null){
      if(transfer.encoded==null)transfer.encoded=PACKETS.requestSigns(state.dimension,transfer.road,transfer.version,transfer.states);
      if(!ready(transfer.encoded))return 0;
      try{transfer.parts=RoadWire.reidentify(transfer.encoded.join());}
      catch(java.util.concurrent.CompletionException|java.util.concurrent.CancellationException e){state.signTransfers.remove(entry.getKey());state.pendingSigns.add(entry.getKey());state.activeSign=null;return 0;}
    }
    if(!canSend(transfer.parts,transfer.next,remaining,deadline))return 0;
    int bytes=fragment(player,transfer.parts.get(transfer.next++));state.activeSign=entry.getKey();
    if(transfer.next==transfer.parts.size()){
      state.sentSigns.put(entry.getKey(),transfer.version);state.forceSigns.remove(entry.getKey());if(transfer.active)state.activeSigns.add(entry.getKey());else state.activeSigns.remove(entry.getKey());
      state.signTransfers.remove(entry.getKey());state.activeSign=null;
    }return bytes;
  }
  private static int publishRoad(ServerPlayer player,Watching state,RoadData data,long deadline,int remaining){
    var entry=next(state.pending,state.activeRoad);if(entry==null){state.activeRoad=null;return 0;}
    var transfer=entry.getValue();var built=data.index.roads.get(entry.getKey());
    if(built==null||built.record!=transfer.record||Collections.disjoint(built.chunks,state.chunks)){
      cancelTransfer(player,transfer.parts,transfer.next,entry.getKey());state.pending.remove(entry.getKey());state.activeRoad=null;return 0;
    }
    if(transfer.parts==null){
      var encoded=PACKETS.request(state.dimension,transfer.record);if(!ready(encoded))return 0;
      try{transfer.parts=RoadWire.reidentify(encoded.join());}
      catch(java.util.concurrent.CompletionException|java.util.concurrent.CancellationException e){state.pending.remove(entry.getKey());state.activeRoad=null;player.displayClientMessage(Component.literal("道路同步失败："+(e.getCause()==null?e.getMessage():e.getCause().getMessage())),false);return 0;}
    }
    if(!canSend(transfer.parts,transfer.next,remaining,deadline))return 0;
    int bytes=fragment(player,transfer.parts.get(transfer.next++));state.activeRoad=entry.getKey();
    if(transfer.next==transfer.parts.size()){state.sent.put(entry.getKey(),transfer.record);state.pending.remove(entry.getKey());state.activeRoad=null;}return bytes;
  }
  public static String metrics(){return "wireFragments="+sentFragments+" wireMiB="+(sentBytes/1048576)+" publishMs="+(publicationNanos/1000000)+" publishMaxMs="+(publicationMaxNanos/1000000)+" resync="+resyncs+" hardResync="+hardResyncs+" maintenanceMs="+(maintenanceNanos/1000000)+" maintenanceMaxMs="+(maintenanceMaxNanos/1000000)+" encodings="+PACKETS.encodings()+" signEncodings="+PACKETS.signEncodings()+" maxWaitTicks="+WATCHING.values().stream().mapToLong(s->s.maxWaitTicks).max().orElse(0);}
  public static void stop(){RoadRecord.clearMeshCaches();com.sora.splineroads.core.RoadClearance.clearPreparedCache();RoadPlanningJobs.stop();LaneRamps.clearPreviewSnapshots();TollTool.clearPreviews();WATCHING.clear();PACKETS.clear();LAST_ACTION.clear();LAST_PREVIEW.clear();playerCursor=levelCursor=0;}

  public static void broadcastRoad(ServerLevel level, RoadRecord r) {
    var built = RoadData.get(level).index.roads.get(r.id());
    if (built == null) return;
    for(ServerPlayer player:level.players())changedRoad(player,built);
  }
  private static void changedRoad(ServerPlayer player,RoadIndex.Built built){
    var r=built.record;var state=watching(player);
    if(!Collections.disjoint(built.chunks,state.chunks))road(player,r);
    // Old client bounds can still overlap watched chunks after a server-side move.
    // Send an ordered delete barrier before discarding that player's old transfer.
    else if(state.sent.containsKey(r.id())||state.pending.containsKey(r.id())||state.activeSigns.contains(r.id())||state.batch!=null&&state.batch.ids.contains(r.id()))forget(player,state,r.id());
  }

  /** Ordered, small point delta; a client still loading the host gets a full latest snapshot. */
  public static CompoundTag batchPacket(ServerLevel level,List<RoadRecord> roads,Set<UUID> replaced,BlockPos node){
    var packet=new CompoundTag();packet.putString("Type","roadBatch");packet.putString("Dimension",level.dimension().location().toString());packet.putUUID("Batch",UUID.randomUUID());
    var ids=new net.minecraft.nbt.ListTag();for(UUID id:replaced){var t=new CompoundTag();t.putUUID("Id",id);ids.add(t);}packet.put("Replaced",ids);
    var list=new net.minecraft.nbt.ListTag();var signs=new net.minecraft.nbt.ListTag();var data=RoadData.get(level);
    for(var road:roads){list.add(road.save());signs.add(data.smartSigns.packet(level,road.id()));}packet.put("Roads",list);packet.put("SignPackets",signs);packet.putLong("NodePos",node.asLong());
    if(level.getBlockEntity(node) instanceof NodeEntity marker)packet.put("Node",marker.getUpdateTag());return packet;
  }
  public record PreparedBatch(CompoundTag packet,List<RoadWire.Fragment> fragments){}
  public static PreparedBatch prepareBatch(ServerLevel level,List<RoadRecord> roads,Set<UUID> replaced,BlockPos node){var packet=batchPacket(level,roads,replaced,node);return new PreparedBatch(packet,RoadWire.split(packet));}
  private static long packedBytes(List<RoadWire.Fragment> parts){return parts.stream().mapToLong(p->p.bytes().length).sum();}
  private static void refreshBatch(ServerPlayer player,Watching state,Set<UUID> affected,BlockPos node,PreparedBatch prepared){
    var old=state.batch;
    int generations=(old==null?0:old.generations)+(prepared==null?0:1);
    long historyBytes=(old==null?0:old.historyBytes)+(prepared==null?0:packedBytes(prepared.fragments()));
    // Individually valid transactions may form an invalid oversized union while
    // a slow client has not ACKed the first one. A bounded hard recovery epoch
    // discards that obsolete history instead of retrying the same union forever.
    if(generations>4||historyBytes>(long)RoadWire.PART_BYTES*RoadWire.MAX_PARTS){resync(player,state,true);return;}
    if(old!=null)cancelTransfer(player,old.parts,old.next,null);
    var data=RoadData.get(player.serverLevel());var ids=new LinkedHashSet<>(affected);
    if(old!=null)ids.addAll(old.ids);
    if(ids.size()>4096){resync(player,state,true);return;}
    var records=new LinkedHashMap<UUID,RoadRecord>();var signs=new HashMap<UUID,Long>();
    for(UUID id:ids){
      var pending=state.pending.remove(id);if(pending!=null)cancelTransfer(player,pending.parts,pending.next,id);
      var sign=state.signTransfers.remove(id);if(sign!=null)cancelTransfer(player,sign.parts,sign.next,id);
      var points=state.pointTransfers.remove(id);if(points!=null)cancelTransfer(player,points.parts,points.next,id);
      state.sent.remove(id);state.pendingSigns.remove(id);state.sentSigns.remove(id);
      var built=data.index.roads.get(id);if(built!=null){records.put(id,built.record);signs.put(id,data.smartSigns.version(id));}
    }
    var packet=prepared!=null&&old==null?prepared.packet():batchPacket(player.serverLevel(),List.copyOf(records.values()),ids,node);
    // Prepared packets can contain the full touched group beyond a watch boundary.
    // Track exactly what will be published so the ACK covers all included records.
    if(prepared!=null&&old==null){records.clear();signs.clear();for(var entry:packet.getList("Roads",net.minecraft.nbt.Tag.TAG_COMPOUND)){UUID id=((CompoundTag)entry).getUUID("Id");var built=data.index.roads.get(id);if(built!=null){records.put(id,built.record);signs.put(id,data.smartSigns.version(id));}}}
    var barrier=new CompoundTag();barrier.putString("Type","batchBarrier");barrier.putString("Dimension",state.dimension);barrier.putUUID("Batch",packet.getUUID("Batch"));var list=new net.minecraft.nbt.ListTag();for(UUID id:ids){var value=new CompoundTag();value.putUUID("Id",id);list.add(value);}barrier.put("Ids",list);send(player,barrier);
    state.batch=new BatchTransfer(ids,records,signs,node,packet,prepared!=null&&old==null?RoadWire.reidentify(prepared.fragments()):null,generations,historyBytes);
  }
  public static void broadcastBatch(ServerLevel level,List<RoadRecord> roads,Set<UUID> replaced,PreparedBatch prepared){
    var data=RoadData.get(level);var ids=new LinkedHashSet<>(replaced);for(var road:roads)ids.add(road.id());
    for(var player:level.players()){
      var state=watching(player);boolean relevant=ids.stream().anyMatch(id->state.sent.containsKey(id)||state.pending.containsKey(id)||state.batch!=null&&state.batch.ids.contains(id))||roads.stream().anyMatch(r->{var built=data.index.roads.get(r.id());return built!=null&&!Collections.disjoint(built.chunks,state.chunks);});
      if(relevant)refreshBatch(player,state,ids,BlockPos.of(prepared.packet().getLong("NodePos")),prepared);
    }
  }
  /** ACK is sent only after the client has atomically applied the whole batch. */
  private static void acknowledgeBatch(ServerPlayer player,CompoundTag command){
    var state=watching(player);var batch=state.batch;
    if(batch==null||!state.dimension.equals(command.getString("Dimension"))||!command.hasUUID("Batch")||!batch.packet.getUUID("Batch").equals(command.getUUID("Batch"))||batch.parts==null||batch.next!=batch.parts.size())return;
    var data=RoadData.get(player.serverLevel());
    for(var entry:batch.records.entrySet()){
      var current=data.index.roads.get(entry.getKey());if(current==null||current.record!=entry.getValue()){refreshBatch(player,state,batch.ids,batch.node,null);return;}
    }
    state.sent.putAll(batch.records);state.sentSigns.putAll(batch.signVersions);state.activeSigns.addAll(batch.records.keySet());state.forceSigns.removeAll(batch.records.keySet());
    for(UUID id:batch.ids)if(!batch.records.containsKey(id))state.activeSigns.remove(id);
    for(UUID id:batch.records.keySet())if(!Objects.equals(state.sentSigns.get(id),data.smartSigns.version(id)))state.pendingSigns.add(id);
    for(UUID id:batch.ids){var current=data.index.roads.get(id);if(current==null||Collections.disjoint(current.chunks,state.chunks))forgetTransport(player,state,id);}
    state.batch=null;
  }
  public static void broadcastLanePoints(ServerLevel level, RoadRecord before, RoadRecord after) {
    var built = RoadData.get(level).index.roads.get(after.id());
    if(built==null||built.record!=after)return;
    for(var player:level.players())lanePoints(player,before,built);
  }
  static void lanePoints(ServerPlayer player,RoadRecord before,RoadIndex.Built built){
    var after=built.record;var state=watching(player);
    if(Collections.disjoint(built.chunks,state.chunks))return;
    if(state.batch!=null&&state.batch.ids.contains(before.id())){refreshBatch(player,state,state.batch.ids,state.batch.node,null);return;}
    var queued=state.pointTransfers.get(before.id());
    if((state.sent.get(before.id())==before||queued!=null&&queued.after==before)&&!state.pending.containsKey(before.id())){
      var base=queued==null?before:queued.before;
      if(queued!=null)cancelTransfer(player,queued.parts,queued.next,before.id());
      state.pointTransfers.put(after.id(),new PointTransfer(base,after));
    }else road(player,after);
  }

  /** Normal unwatch retires transport only; the client may retain its cold road. */
  private static void forgetTransport(ServerPlayer player,Watching state,UUID id){
    var pending=state.pending.remove(id);if(pending!=null)cancelTransfer(player,pending.parts,pending.next,id);
    var sign=state.signTransfers.remove(id);if(sign!=null)cancelTransfer(player,sign.parts,sign.next,id);
    var points=state.pointTransfers.remove(id);if(points!=null)cancelTransfer(player,points.parts,points.next,id);
    state.sent.remove(id);state.pendingSigns.remove(id);state.forceSigns.remove(id);state.sentSigns.remove(id);state.activeSigns.remove(id);
  }
  private static void forget(ServerPlayer player,Watching state,UUID id){
    if(state.batch!=null&&state.batch.ids.contains(id)){refreshBatch(player,state,state.batch.ids,state.batch.node,null);return;}
    forgetTransport(player,state,id);
    var packet=new CompoundTag();packet.putString("Type","delete");packet.putUUID("Id",id);packet.putString("Dimension",state.dimension);send(player,packet);
  }
  public static void broadcastDelete(ServerLevel level, UUID id) {
    for (ServerPlayer player:level.players())forget(player,watching(player),id);
  }

  public static void result(ServerPlayer p, boolean success, String message) {
    result(p, success, message, new CompoundTag());
  }

  private static void result(ServerPlayer p, boolean success, String message, CompoundTag command) {
    CompoundTag t = new CompoundTag();
    t.putString("Type", "result");
    t.putBoolean("Success", success);
    t.putString("Message", message);
    t.putString("Dimension",p.level().dimension().location().toString());
    RoadActionContext.echo(command,t);
    if(command.getString("Action").equals("manualOpen")&&command.hasUUID("OpenRequest"))t.putUUID("OpenRequest",command.getUUID("OpenRequest"));
    if(!success)LaneRamps.conflictRoads(t,p,message);
    send(p, t);
    p.displayClientMessage(Component.literal(com.sora.splineroads.core.RoadConflictIds.display(message)), false);
  }

  private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
  private static final Map<UUID, Long> LAST_PREVIEW = new HashMap<>();

  public static void forget(UUID id) {
    RoadPlanningJobs.cancel(id);RoadSplitter.cancel(id);
    LAST_ACTION.remove(id);
    LAST_PREVIEW.remove(id);
    WATCHING.remove(id);
  }

  public static void requestRoadSync(){
    var level=net.minecraft.client.Minecraft.getInstance().level;if(level==null)return;
    var tag=new CompoundTag();tag.putString("Action","roadSyncRetry");tag.putString("Dimension",level.dimension().location().toString());CHANNEL.sendToServer(new Action(tag));
  }
  public static void ackBatch(UUID id){
    var level=net.minecraft.client.Minecraft.getInstance().level;if(level==null)return;
    var tag=new CompoundTag();tag.putString("Action","roadBatchAck");tag.putString("Dimension",level.dimension().location().toString());tag.putUUID("Batch",id);CHANNEL.sendToServer(new Action(tag));
  }
  private static void resync(ServerPlayer player,Watching state){resync(player,state,false);}
  private static void resync(ServerPlayer player,Watching state,boolean resetRoads){
    resyncs++;if(resetRoads)hardResyncs++;var index=RoadData.get(player.serverLevel()).index;var oldBatch=state.batch;
    var reset=new CompoundTag();reset.putString("Type","syncReset");reset.putString("Dimension",state.dimension);reset.putBoolean("ResetRoads",resetRoads);send(player,reset);
    state.sent.clear();state.pending.clear();state.pendingSigns.clear();state.forceSigns.clear();state.signTransfers.clear();state.pointTransfers.clear();state.sentSigns.clear();state.activeSigns.clear();state.batch=null;
    // A failed batch may still leave its old replaced road on the client. Resend
    // that transaction as one latest atomic snapshot, then all other watched roads.
    if(oldBatch!=null&&!resetRoads){state.batch=oldBatch;refreshBatch(player,state,oldBatch.ids,oldBatch.node,null);}
    var ids=new LinkedHashSet<UUID>();for(long chunk:state.chunks)ids.addAll(index.inChunk(chunk));
    state.forceSigns.addAll(ids);
    for(UUID id:ids){var built=index.roads.get(id);if(built!=null)road(player,built.record);}
  }
  private static void act(ServerPlayer player, CompoundTag t) {
    if(t.getString("Action").startsWith("manual")){try{String message=ManualPlanner.action(player,t);if(!message.isEmpty())result(player,true,message,t);}catch(RuntimeException failure){if(!(failure instanceof IllegalArgumentException))org.apache.logging.log4j.LogManager.getLogger("SplineRoads/planner").error("Map action failed",failure);result(player,false,failure.getMessage()==null?"地图操作失败":failure.getMessage(),t);}return;}
    if(t.getString("Action").startsWith("street"))return; // Retired planner packets cannot resume saved tasks.
    if(t.getString("Action").startsWith("railbed")){RailbedTool.action(player,t);return;}
    if(t.getString("Action").equals("roadBatchAck")){acknowledgeBatch(player,t);return;}
    if(t.getString("Action").equals("roadSyncRetry")){
      var state=watching(player);long now=player.serverLevel().getGameTime();
      if(state.dimension.equals(t.getString("Dimension"))&&now-state.lastResync>=40){state.lastResync=now;resync(player,state);}return;
    }
    if(t.getString("Action").startsWith("split")){if(t.getString("Action").equals("splitCancel")){RoadSplitter.cancel(player.getUUID());return;}try{if(t.getString("Action").equals("splitPreview")){Long last=LAST_PREVIEW.get(player.getUUID());long now=player.serverLevel().getGameTime();if(last!=null&&now-last<5)throw new IllegalArgumentException("操作过快，请稍候重试");LAST_PREVIEW.put(player.getUUID(),now);open(player,RoadSplitter.preview(player,t));}else if(t.getString("Action").equals("splitCommit"))result(player,true,RoadSplitter.commit(player,t),t);}catch(RuntimeException ex){if(!(ex instanceof IllegalArgumentException))System.getLogger("SplineRoads/splitter").log(System.Logger.Level.ERROR,"Split action failed",ex);if(t.getString("Action").equals("splitPreview")){var reply=new CompoundTag();reply.putString("Kind","splitCheck");reply.putLong("Request",t.getLong("Request"));reply.putString("Dimension",player.level().dimension().location().toString());reply.putString("Error",ex.getMessage()==null?"分割规划失败":ex.getMessage());open(player,reply);}else result(player,false,ex.getMessage()==null?"分割失败，已恢复原道路":ex.getMessage(),t);}return;}
    if(t.getString("Action").equals("tollCancelPreview")){TollTool.cancelPreview(player,t);return;}
    if(t.getString("Action").equals("laneRampCancel")){RoadPlanningJobs.cancel(player.getUUID(),t.getLong("Request"));return;}
    if(t.getString("Action").equals("lanePointReady")) {
      if(t.hasUUID("Interaction")) LanePointTool.acknowledge(player,t.getUUID("Interaction"));
      return;
    }
    if(t.getString("Action").equals("lanePointResync")) {
      var b=RoadData.get(player.serverLevel()).index.roads.get(t.getUUID("Id"));
      var state=watching(player);
      if(b!=null&&!Collections.disjoint(b.chunks,state.chunks)){state.sent.remove(b.record.id());road(player,b.record);}
      return;
    }
    if(t.getString("Action").equals("deletePreview")||t.getString("Action").equals("laneRampDeletePreview")||t.getString("Action").equals("elevatedAccessPreview")){try{perform(player,t);}catch(RuntimeException e){result(player,false,e.getMessage()==null?"预览读取失败":e.getMessage(),t);}return;}
    if(t.getString("Action").equals("tollPreview")||t.getString("Action").equals("interchangePreview")||t.getString("Action").equals("laneRampPreview")){
      try{perform(player,t);}catch(RuntimeException e){
        logCrossingFailure(t.getString("Action"),e);
        if(!(e instanceof IllegalArgumentException))org.apache.logging.log4j.LogManager.getLogger("SplineRoads/build").error("Road preview failed",e);
        CompoundTag reply=new CompoundTag();reply.putString("Kind",t.getString("Action").equals("tollPreview")?"tollCheck":t.getString("Action").equals("laneRampPreview")?"laneRampCheck":"corridorCheck");reply.putLong("Request",t.getLong("Request"));reply.putString("Dimension",player.level().dimension().location().toString());reply.putString("Error",e.getMessage()==null?"预览参数无效":e.getMessage());LaneRamps.conflictRoads(reply,player,reply.getString("Error"));open(player,reply);
      }return;
    }
    try {
      result(player, true, perform(player, t),t);
    } catch (RuntimeException e) {
      logCrossingFailure(t.getString("Action"),e);
      if(!(e instanceof IllegalArgumentException))org.apache.logging.log4j.LogManager.getLogger("SplineRoads/build").error("Road action failed: "+t.getString("Action"),e);
      result(player, false, (e instanceof IllegalArgumentException||e instanceof com.sora.splineroads.core.RoadPlanningBudget.Aborted)?(e.getMessage()==null?"道路参数无效":e.getMessage()):"建造异常已结束等待："+e.getClass().getSimpleName()+(e.getMessage()==null?"":"："+e.getMessage())+"；完整原因见 latest.log（SplineRoads/build）",t);
    }
  }

  private static void logCrossingFailure(String action,RuntimeException failure){
    var crossing=com.sora.splineroads.core.RoadCrossingFailure.find(failure);
    if(crossing!=null)System.getLogger("SplineRoads/cycle").log(System.Logger.Level.WARNING,
        "action="+action+" "+crossing.diagnostic(),crossing);
  }

  /** Same authoritative command path used by packets and server integration tests. */
  public static String perform(ServerPlayer player, CompoundTag t) {
    if(t.hasUUID("PlannerSession"))return ManualPlanner.edit(player,t,()->performTool(player,t));
    return performTool(player,t);
  }
  private static String performTool(ServerPlayer player, CompoundTag t) {
    if(t.getString("Action").equals("tollCancelPreview")){TollTool.cancelPreview(player,t);return "";}
    long now = player.serverLevel().getGameTime();
    boolean preview=t.getString("Action").equals("elevatedAccessPreview")||t.getString("Action").equals("deletePreview")||t.getString("Action").equals("laneRampDeletePreview")||t.getString("Action").equals("tollPreview")||t.getString("Action").equals("interchangePreview")||t.getString("Action").equals("laneRampPreview");
    var throttle=preview?LAST_PREVIEW:LAST_ACTION;
    Long last = throttle.get(player.getUUID());
    if (last != null && now - last < 5) throw new IllegalArgumentException("操作过快，请稍候重试");
    throttle.put(player.getUUID(), now);
    String action = t.getString("Action");
    ItemStack tool =
        t.hasUUID("PlannerSession")?ManualPlanner.tool(player,t):usable(player.getMainHandItem(), action)
            ? player.getMainHandItem()
            : player.getOffhandItem();
    if (!(tool.getItem() instanceof RoadTool
            || (action.startsWith("toll") && usable(tool,action))
            || ((action.equals("gantry")||action.equals("smartMode")) && tool.getItem() instanceof GantryTool)
            || (action.equals("smartText") && tool.getItem() instanceof SmartTextTool)
            || (action.equals("laneLines") && tool.getItem() instanceof LaneLineTool)
            || (action.equals("attachedPoint") && (tool.getItem() instanceof AttachedPointTool || tool.getItem() instanceof ElevatedPointTool))
            || (action.equals("lanePoint") && tool.getItem() instanceof LanePointTool)
            || (action.startsWith("laneRamp") && tool.getItem() instanceof LaneRampTool)
            || (action.equals("yJunction") && (tool.getItem() instanceof YJunctionTool || tool.getItem() instanceof JunctionTool))
            || (action.startsWith("junction") && usable(tool,action))
            || (action.startsWith("roundabout") && tool.getItem() instanceof RoundaboutTool)
            || (action.startsWith("interchange")
                && tool.getItem() instanceof InterchangeTool generator
                && generator.available())
            || ((action.equals("delete")||action.equals("deletePreview")) && tool.is(SplineRoads.REMOVER.get())))
        || !player.mayBuild()
        || player.isSpectator()) throw new IllegalArgumentException("请手持道路连接器并确保拥有建设权限");
    ServerLevel level = player.serverLevel();
    RoadData data = RoadData.get(level);
    switch (action) {
      case "deletePreview" -> {if(!tool.is(SplineRoads.CONNECTOR.get())&&!tool.is(SplineRoads.REMOVER.get()))throw new IllegalArgumentException("请手持道路连接器或删除器");RoadTool.openDeletion(player,t.getUUID("Id"),t);return "";}
      case "smartText" -> {if(!(tool.getItem() instanceof SmartTextTool))throw new IllegalArgumentException("请手持文字编辑器");data.smartSigns.edit(player,t);return "标牌内容已保存";}
      case "smartMode" -> {if(!(tool.getItem() instanceof GantryTool))throw new IllegalArgumentException("请手持龙门架编辑器");var key=SmartSignCodec.key(t);var b=data.index.roads.get(key.road());if(b==null)throw new IllegalArgumentException("龙门架已删除");RoadData.requireOwner(player,b.record.owner());if(player.distanceToSqr(b.mesh.first().center().x(),b.mesh.first().center().y(),b.mesh.first().center().z())>4096*4096)throw new IllegalArgumentException("离道路太远");data.smartSigns.mode(key,com.sora.splineroads.core.SmartSigns.Mode.valueOf(t.getString("Mode")),t.getLong("Revision"));return "标牌模式已更新；文字保持手动填写";}

      case "tollPreview" -> {
        TollTool.selection(player,tool,t);var inputs=TollRoads.preview(level,player,t);
        // Only a fully validated new preview takes ownership. A late ramp result
        // must not replace this player's newer prepared transaction.
        RoadPlanningJobs.cancel(player.getUUID());TollTool.rememberPreview(player,tool,t,inputs);
        var reply=new CompoundTag();reply.putString("Kind","tollCheck");reply.putString("Dimension",level.dimension().location().toString());reply.putLong("Request",t.getLong("Request"));reply.put("Inputs",inputs);open(player,reply);return "收费站预览已检查";
      }
      case "tollBuild" -> {TollTool.selection(player,tool,t);return TollRoads.build(level,player,t);}
      case "tollDelete" -> {TollRoads.remove(level,player,t.getUUID("Id"));return "收费站已删除，原端点与外接道路保留";}
      case "lanePoint" -> {if(!(tool.getItem() instanceof LanePointTool))throw new IllegalArgumentException("请手持车道点工具");return LanePointTool.edit(level,player,t);}
      case "laneRampRoad" -> {if(!(tool.getItem() instanceof RoadTool))throw new IllegalArgumentException("请手持道路连接器");return LaneRamps.editRoad(level,player,t);}
      case "laneRampPreview" -> {if(!(tool.getItem() instanceof LaneRampTool))throw new IllegalArgumentException("请手持匝道连接器");RoadPlanningJobs.begin(player,tool,t);return "匝道正在后台规划";}
      case "laneRamp" -> {if(!(tool.getItem() instanceof LaneRampTool))throw new IllegalArgumentException("请手持匝道连接器");return LaneRamps.build(player,tool,t);}
      case "laneRampDeletePreview", "laneRampDelete" -> {if(!(tool.getItem() instanceof LaneRampTool))throw new IllegalArgumentException("请手持匝道连接器");return LaneRampTool.deletion(player,tool,t,action.equals("laneRampDelete"));}
      case "attachedPoint" -> {if(!(tool.getItem() instanceof AttachedPointTool || tool.getItem() instanceof ElevatedPointTool))throw new IllegalArgumentException("请手持附属点创建器");return AttachedPointTool.edit(level,player,t);}
      case "laneLines" -> {if(!(tool.getItem() instanceof LaneLineTool))throw new IllegalArgumentException("请手持车道线编辑器");data.editLaneLine(level,player,t);return t.contains("HideArrows")?(t.getBoolean("HideArrows")?"所选路段的方向箭头已隐藏":"所选路段的方向箭头已显示"):"所选路段的单根车道线已更新";}
      case "yJunction" -> {return YJunctionTool.build(level,player,t,tool);}

      case "gantry" -> {
        if(!(tool.getItem() instanceof GantryTool))throw new IllegalArgumentException("请手持龙门架编辑器");
        data.editGantry(level,player,t);
        return "所选龙门架已更新";
      }
      case "interchangePreview" -> {
        requireGenerator(tool,t,data);
        if(!t.contains("Corridor"))throw new IllegalArgumentException("预览仅用于主辅路与双层同侧组合");
        if(!t.hasUUID("Id")&&(!Arrays.equals(t.getLongArray("Points"),tool.getOrCreateTag().getLongArray("Points"))
            ||!level.dimension().location().toString().equals(tool.getTag().getString("Dimension"))))throw new IllegalArgumentException("组合选点已失效，请重新选点");
        var inputs=Corridors.preview(level,player,t);
        // Explicit successful synchronous previews also supersede old ramp work.
        RoadPlanningJobs.cancel(player.getUUID());
        var reply=new CompoundTag();reply.putString("Kind","corridorCheck");reply.putLong("Request",t.getLong("Request"));reply.putString("Dimension",level.dimension().location().toString());reply.put("Inputs",inputs);open(player,reply);return "组合预览已检查";
      }
      case "roundabout" -> { return RoundaboutTool.build(level,player,t,tool); }
      case "junctionRoad" -> {if(!tool.is(SplineRoads.CONNECTOR.get()))throw new IllegalArgumentException("请手持道路连接器");return JunctionRoads.build(level,player,t);}
      case "elevatedAccessPreview" -> {if(!(tool.getItem() instanceof RoadTool))throw new IllegalArgumentException("请手持道路连接器");ElevatedAccessRoads.requireSelection(player,tool,t);open(player,ElevatedAccessRoads.preview(level,player,t));return "";}
      case "elevatedAccessBuild" -> {if(!(tool.getItem() instanceof RoadTool))throw new IllegalArgumentException("请手持道路连接器");ElevatedAccessRoads.requireSelection(player,tool,t);String result=ElevatedAccessRoads.build(level,player,t);for(String key:List.of("ElevatedSelection","SideMain","SidePoint","SideSelection","Start"))tool.getOrCreateTag().remove(key);return result;}
      case "junctionSideRoad" -> {if(!tool.is(SplineRoads.CONNECTOR.get()))throw new IllegalArgumentException("请手持道路连接器");return OneSidedJunctions.buildRoadSelected(level,player,t,tool);}
      case "junction" -> {
        if (!(tool.getItem() instanceof RoundaboutTool) && !(tool.getItem() instanceof JunctionTool)) throw new IllegalArgumentException("请手持普通路口编辑器");
        if(!t.hasUUID("Id")&&!(tool.getItem() instanceof JunctionTool))throw new IllegalArgumentException("新建独立路口请使用普通路口编辑器");
        if (!t.hasUUID("Id") && (!Arrays.equals(t.getLongArray("Points"),tool.getOrCreateTag().getLongArray("Points"))
            || !level.dimension().location().toString().equals(tool.getTag().getString("Dimension"))))
          throw new IllegalArgumentException("路口选点已失效，请重新选点");
        String message=Junctions.build(level,player,t);tool.getOrCreateTag().remove("Points");return message;
      }
      case "junctionDelete" -> {LaneDeletes.removeJunction(level,player,t);return "路口已整体删除，外部道路和端点保留";}

      case "interchange" -> {
        requireGenerator(tool, t, data);
        if (!t.hasUUID("Id")
            && (!Arrays.equals(
                    t.getLongArray("Points"), tool.getOrCreateTag().getLongArray("Points"))
                || !level
                    .dimension()
                    .location()
                    .toString()
                    .equals(tool.getTag().getString("Dimension"))))
          throw new IllegalArgumentException("立交端点选择失效，请重新选择");
        String message = Interchanges.build(level, player, t);
        tool.getOrCreateTag().remove("Points");
        return message;
      }
      case "interchangeDelete", "interchangeDeleteRamps" -> {
        requireGenerator(tool, t, data);
        boolean corridor=Interchanges.descriptor(data,t.getUUID("Id")).contains("Corridor");
        if (action.equals("interchangeDeleteRamps")) {
          Interchanges.removeRamps(level, player, t.getUUID("Id"));
          return corridor?"连接匝道已删除；主路、辅路或第二层道路保留，可单独编辑":"匝道及路口设施已删除；主路保留，可用道路连接器单独编辑";
        }
        Interchanges.remove(level, player, t.getUUID("Id"));
        return "整座立交已删除，原端点保留";
      }

      case "curveControl" -> {if(!(tool.getItem() instanceof RoadTool))throw new IllegalArgumentException("请手持道路连接器");return CurveControlNodes.add(level,player,tool,BlockPos.of(t.getLong("Pos")));}
      case "connect" -> {
        BlockPos a = BlockPos.of(t.getLong("A")), b = BlockPos.of(t.getLong("B"));
        UUID id = t.hasUUID("Id") ? t.getUUID("Id") : null;
        var settingsTag=t.getCompound("Settings");
        if(id!=null&&t.contains("BaseSettings")) {
          var current=data.editableRoad(id);
          if(current==null)throw new IllegalArgumentException("道路已删除，请重新选择");
          var latest=current.automatic()?com.sora.splineroads.core.RoadPlanner.mode(current.settings(),com.sora.splineroads.core.RoadGeometry.Mode.AUTO,current.settings().arcDegrees()):current.settings();
          settingsTag=RoadDraft.reconcileSettings(t.getCompound("BaseSettings"),settingsTag,RoadRecord.writeSettings(latest));
        }
        var settings = RoadRecord.readSettings(settingsTag);
        if (id == null) {
          if (!tool.getOrCreateTag().contains("Start")
              || tool.getTag().getLong("Start") != a.asLong()
              || !level
                  .dimension()
                  .location()
                  .toString()
                  .equals(tool.getTag().getString("Dimension")))
            throw new IllegalArgumentException("起点选择已失效，请重新选择两个端点");
        }
        settings=settings.options(settings.options().sidewalk(settings.options().sidewalk().smooth(true)));
        if (settings.style().ramp()) throw new IllegalArgumentException("道路连接器仅用于普通道路和高速道路");
        var controlNodes=id==null?CurveControlNodes.validate(level,player,tool.getOrCreateTag(),settings,a,b):java.util.List.<BlockPos>of();
        RoadRecord r = data.connect(level, player, a, b, settings, id, t.getBoolean("LevelEnds"),AutoJunctions.forced(t,"A"),AutoJunctions.forced(t,"B"),controlNodes);
        tool.getOrCreateTag().remove(CurveControlNodes.KEY);
        tool.getOrCreateTag().put("Settings", RoadRecord.writeSettings(com.sora.splineroads.core.RoadPreferences.fresh(settings)));
        tool.getOrCreateTag().putInt("PreferencesVersion", 3);
        if(ManualPlanner.ownsTool(player,tool)){
          tool.getOrCreateTag().remove("Start");tool.getOrCreateTag().remove("CurveThrough");
          return "道路已建成；请重新选择 A 和 B";
        }
        BlockPos next=AutoJunctions.center(data,r.a())!=null?r.a():r.b();
        tool.getOrCreateTag().putLong("Start",next.asLong());
        tool.getOrCreateTag().putString("Dimension", level.dimension().location().toString());
        if(AutoJunctions.center(data,next)!=null)return "道路和路口已更新；保留中心端点，可直接选择下一方向的外侧端点";
        return "道路已建成并自动清障；继续右键下一个端点即可续接";
      }
      case "node" -> {
        BlockPos p = BlockPos.of(t.getLong("Pos"));
        data.editNode(level, player, p, RoadRecord.readNode(t.getCompound("Node")));
        return "端点及相连道路已更新";
      }
      case "delete" -> {
        UUID id = t.getUUID("Id");
        LaneDeletes.remove(level, player, t);
        return "道路已删除";
      }
      default -> throw new IllegalArgumentException("未知操作");
    }
  }

  private static void requireGenerator(ItemStack stack, CompoundTag command, RoadData data) {
    if (!(stack.getItem() instanceof InterchangeTool tool) || !tool.available())
      throw new IllegalArgumentException("请手持对应的立交、主辅路或双层出入口生成器");
    tool.requireDescriptor(
        command.hasUUID("Id")
            ? Interchanges.descriptor(data,command.getUUID("Id"))
            : command);
    if(command.getString("Action").equals("interchange")||command.getString("Action").equals("interchangePreview"))tool.requireDescriptor(command);
  }

  private static boolean usable(ItemStack stack, String action) {
    if(action.startsWith("toll"))return stack.getItem() instanceof TollTool || stack.getItem() instanceof RoadTool || action.equals("tollDelete")&&stack.is(SplineRoads.REMOVER.get());
    if(action.equals("lanePoint"))return stack.getItem() instanceof LanePointTool;
    if(action.equals("laneRampRoad"))return stack.getItem() instanceof RoadTool;
    if(action.startsWith("laneRamp"))return stack.getItem() instanceof LaneRampTool;
    if(action.equals("attachedPoint"))return stack.getItem() instanceof AttachedPointTool||stack.getItem() instanceof ElevatedPointTool;
    if(action.equals("laneLines"))return stack.getItem() instanceof LaneLineTool;
    if(action.equals("yJunction"))return stack.getItem() instanceof YJunctionTool || stack.getItem() instanceof JunctionTool;
    if(action.equals("smartText"))return stack.getItem() instanceof SmartTextTool;
    if(action.equals("smartMode"))return stack.getItem() instanceof GantryTool;
    if(action.equals("gantry"))return stack.getItem() instanceof GantryTool;
    if(action.startsWith("roundabout"))return stack.getItem() instanceof RoundaboutTool;
    if(action.startsWith("elevatedAccess")||action.equals("junctionRoad")||action.equals("junctionSideRoad"))return stack.is(SplineRoads.CONNECTOR.get());
    if (action.startsWith("junction")) return stack.is(SplineRoads.CONNECTOR.get()) || stack.getItem() instanceof JunctionTool || stack.getItem() instanceof RoundaboutTool || action.equals("junctionDelete") && stack.is(SplineRoads.REMOVER.get());
    if (action.startsWith("interchange"))
      return stack.getItem() instanceof InterchangeTool generator && generator.available();
    return stack.getItem() instanceof RoadTool
        || ((action.equals("delete")||action.equals("deletePreview")) && stack.is(SplineRoads.REMOVER.get()));
  }

  private RoadNetwork() {}
}
