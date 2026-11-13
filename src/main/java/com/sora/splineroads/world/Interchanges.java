package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/**
 * Three or four anchors, compact editable specification, and a single validated world transaction.
 */
public final class Interchanges {
  public static CompoundTag descriptor(RoadData data,UUID id){
    var saved=data.interchanges.get(InterchangeJunctions.parent(data,id));
    if(saved==null)throw new IllegalArgumentException("道路组合已不存在");
    return saved.copy();
  }
  // Geometry is independent of terrain and permissions. Reuse an identical preview in single
  // player.
  private static final Map<CompoundTag, InterchangePlanner.Plan> PLANS =
      new LinkedHashMap<>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<CompoundTag, InterchangePlanner.Plan> e) {
          return size() > 3;
        }
      };

  public static CompoundTag write(InterchangePlanner.Options o) {
    CompoundTag t = new CompoundTag();
    t.putString("Preset", o.preset().name());
    t.putBoolean("Left", o.leftTraffic());
    t.putInt("Lanes", o.lanes());
    t.putDouble("Transition", o.transition());
    t.putDouble("Radius", o.radius());
    t.putDouble("Clearance", o.clearance());
    t.putInt("Upper", o.upper());
    t.putInt("JunctionAxis",o.junctionAxis());
    t.putInt("LoopMask",o.loopMask());
    t.putBoolean("AllowAbove",o.allowAbove());t.putBoolean("FlatConnector",o.flatConnector());t.putDouble("ConnectorHeight",o.connectorHeight());
    t.putDouble("RampWidth", o.rampWidth());
    t.putBoolean("AdjustEndpoints", o.adjustEndpoints());
    t.putBoolean("AllowShrink", o.allowShrink());
    t.putDouble("MaxLowering", o.maxLowering());
    var config=o.toll();var toll=new CompoundTag();toll.putBoolean("Enabled",config.enabled());toll.putBoolean("TwoWay",config.twoWay());toll.putBoolean("Reverse",config.reverse());toll.putString("Near",config.near().name());toll.putInt("ForwardETC",config.forwardEtc());toll.putInt("ReverseETC",config.reverseEtc());toll.putString("Name",config.name());t.put("Toll",toll);
    return t;
  }

  public static InterchangePlanner.Options read(CompoundTag t) {
    return new InterchangePlanner.Options(
        InterchangePlanner.Preset.valueOf(t.getString("Preset")),
        t.getBoolean("Left"),
        t.getInt("Lanes"),
        t.getDouble("Transition"),
        t.getDouble("Radius"),
        t.getDouble("Clearance"),
        t.getInt("Upper"),
        t.getDouble("RampWidth"),
        t.getBoolean("AdjustEndpoints"),
        t.getBoolean("AllowShrink"),
        t.contains("MaxLowering") ? t.getDouble("MaxLowering") : 8,
        t.contains("JunctionAxis") ? t.getInt("JunctionAxis") : 1,
        t.contains("LoopMask") ? t.getInt("LoopMask") : 5,
        t.getBoolean("AllowAbove"),t.getBoolean("FlatConnector"),t.getDouble("ConnectorHeight"),readToll(t));
  }

  private static DoubleTrumpetToll.Config readToll(CompoundTag options){
    if(!options.contains("Toll"))return DoubleTrumpetToll.Config.OFF;var t=options.getCompound("Toll");
    return new DoubleTrumpetToll.Config(t.getBoolean("Enabled"),t.getBoolean("TwoWay"),t.getBoolean("Reverse"),DoubleTrumpetToll.Near.valueOf(t.getString("Near")),t.getInt("ForwardETC"),t.getInt("ReverseETC"),t.getString("Name"));
  }

  static void clearRemovedDesign(CompoundTag t){
    t.put("Options",write(read(t.getCompound("Options"))));
    for(String key:List.of("DesignInputNodes","DesignInputAxes","DesignFittedNodes","DesignShape","DesignFixed","ParallelDesignScale","ParallelDesignOptions"))t.remove(key);
  }

  public static InterchangePlanner.Plan plan(CompoundTag t) {
    if(ParallelSpui.present(t))return ParallelSpui.plan(t);
    validateMainStyles(t);
    return InterchangeJunctions.applyEdits(InterchangeJunctions.elevationDefaults(ContinuousRoads.plan(t,Interchanges::planBase),t),t);
  }
  static InterchangePlanner.Plan confirmedPlan(CompoundTag t,CompoundTag command){
    validateMainStyles(t);var fixed=ConfirmedInterchangeFit.candidate(t,command);
    // Validate the exact submitted endpoints without repeating optional fitting.
    var plan=ContinuousRoads.confirmed(t,planBase(fixed));
    return InterchangeJunctions.applyEdits(InterchangeJunctions.elevationDefaults(plan,t),t);
  }
  private static void validateMainStyles(CompoundTag t) {
    for(String key:List.of("Main1","Main2","Main3"))if(t.contains(key)){
      var bridge=RoadRecord.readSettings(t.getCompound(key)).options().infrastructure().bridge();
      if(bridge==RoadInfrastructure.Bridge.ARCH||bridge==RoadInfrastructure.Bridge.CABLE||bridge==RoadInfrastructure.Bridge.SUSPENSION)
        throw new IllegalArgumentException("立交主路不支持大河拱桥、斜拉桥或悬索桥，请选择普通高架、梁桥、跨线桥或连续梁");
    }
  }
  private static InterchangePlanner.Plan planBase(CompoundTag t) {
    if(HostAxes.retiredInterchange(t))throw new IllegalArgumentException(HostAxes.RETIRED_MESSAGE);
    CompoundTag key = new CompoundTag();
    for (String field : List.of("Nodes", "Main1", "Main2", "Main3", "Options", "MultiBaseY", "Corridor", "ResolvedFit", "HostAxes"))
      if (t.contains(field)) key.put(field, t.get(field).copy());
    synchronized (PLANS) {
      var cached = PLANS.get(key);
      if (cached != null) return cached;
    }
    var tags = t.getList("Nodes", Tag.TAG_COMPOUND);
    if (tags.size() < 2 || tags.size() > 6) throw new IllegalArgumentException("道路组合端点数据不完整");
    Node[] nodes = new Node[tags.size()];
    for (int i = 0; i < nodes.length; i++) nodes[i] = RoadRecord.readNode(tags.getCompound(i));
    var result = t.contains("Corridor") ? CurvedRoadPlans.corridor(nodes,RoadRecord.readSettings(t.getCompound("Main1")),RoadRecord.readSettings(t.getCompound("Main2")),read(t.getCompound("Options")),Corridors.effective(t),HostAxes.read(t)) : nodes.length>=5 ? MultiInterchange.plan(nodes,new Settings[]{RoadRecord.readSettings(t.getCompound("Main1")),RoadRecord.readSettings(t.getCompound("Main2")),RoadRecord.readSettings(t.getCompound("Main3"))},read(t.getCompound("Options")),t.getDouble("MultiBaseY")) :
        CurvedRoadPlans.interchange(
            nodes,
            RoadRecord.readSettings(t.getCompound("Main1")),
            RoadRecord.readSettings(t.getCompound("Main2")),
            read(t.getCompound("Options")),HostAxes.read(t));
    synchronized (PLANS) {
      PLANS.put(key, result);
    }
    return result;
  }

  public static CompoundTag payload(
      ServerLevel level, ServerPlayer player, long[] points, UUID id) {
    try (var workChunks = RoadWorkChunks.open(level)) {
      if ((points.length < 2 || points.length > 6)
          || Arrays.stream(points).distinct().count() != points.length)
        throw new IllegalArgumentException("请选择对应数量的不同端点");
      RoadData data = RoadData.get(level);
      CompoundTag t = new CompoundTag();
      if (id != null) {
        var saved = data.interchanges.get(id);
        if (saved == null) throw new IllegalArgumentException("立交已不存在");
        if (!Arrays.equals(points, saved.getLongArray("Points")))
          throw new IllegalArgumentException("立交端点已变化，请重新打开立交");
        if (player != null) RoadData.requireOwner(player, saved.getUUID("Owner"));
        t = saved.copy();
      } else
        for (var entry : data.interchanges.entrySet())
          if (Arrays.equals(entry.getValue().getLongArray("Points"), points)) {
            return payload(level, player, points, entry.getKey());
          }
      t.putString("Kind", t.contains("Corridor") ? "corridor" : "interchange");
      t.putLongArray("Points", points);
      ListTag nodes = new ListTag();
      for (int i = 0; i < points.length; i++)
        nodes.add(
            RoadRecord.writeNode(
                id!=null&&t.getBoolean("VirtualRange")?RoadRecord.readNode(t.getList("PlanningNodes",Tag.TAG_COMPOUND).getCompound(i)):InterchangeEndpoints.read(level, player, t, i).node()));
      t.put("Nodes", nodes);
      if(points.length>=5&&!t.contains("MultiBaseY")){double base=Double.POSITIVE_INFINITY;for(int i=0;i<nodes.size();i++)base=Math.min(base,RoadRecord.readNode(nodes.getCompound(i)).position().y());t.putDouble("MultiBaseY",base);}
      if (!t.contains("Options")) {
        var d = InterchangePlanner.Options.DEFAULT;
        t.put(
            "Options",
            write(
                points.length == 3
                    ? new InterchangePlanner.Options(
                        InterchangePlanner.Preset.TRUMPET,
                        d.leftTraffic(),
                        d.lanes(),
                        d.transition(),
                        d.radius(),
                        d.clearance(),
                        d.upper())
                    : d));
      }
      if(points.length>=5&&!t.getCompound("Options").getString("Preset").startsWith("DIRECTIONAL_"))
        t.put("Options",write(new InterchangePlanner.Options(points.length==5?InterchangePlanner.Preset.DIRECTIONAL_FIVE:InterchangePlanner.Preset.DIRECTIONAL_SIX,false,1,96,20,5,2,0,true,true,0)));
      if(ParallelSpui.capture(data,t,player))return t;
      var hostAxes=new ArrayList<RoadAxis>();
      for (int axis = 0; axis < (points.length>=5?3:2); axis++) {
        String key = "Main" + (axis + 1);
        if (id!=null && t.contains(key)) continue;
        Settings settings =
            new Settings(Mode.STRAIGHT, Style.H4_RAIL, Style.H4_RAIL.defaultWidth(), 1, .4, 90);
        var chain =
            axis * 2 + 1 < points.length
                ? mainChain(data, BlockPos.of(points[axis * 2]), BlockPos.of(points[axis * 2 + 1]))
                : points.length==2 ? List.<RoadRecord>of() : points.length==3 ? thirdArmChain(data, t, points) : branchProfile(data,BlockPos.of(points[axis*2]));
        var profiles=chain;
        if (profiles.isEmpty() && axis == 1 && points.length == 3)
          profiles = branchProfile(data, BlockPos.of(points[2]));
        if (!profiles.isEmpty()) {
          var r = profiles.get(0);
          if (player != null) RoadData.requireOwner(player, r.owner());
          settings = r.settings();
        }
        if(!t.contains(key))t.put(key, RoadRecord.writeSettings(settings));
        if(points.length>=5&&!chain.isEmpty()){var path=HostAxes.chain(chain,BlockPos.of(points[axis*2]));if(path.curved()||!path.flat())throw new IllegalArgumentException("五、六向立交仍需要平直且各自等高的主路");}
        if(axis*2<points.length&&points.length<5){
          var defaults=HostAxes.read(t);
          hostAxes.add(chain.isEmpty()?defaults.get(axis):HostAxes.chain(chain,BlockPos.of(points[axis*2])));
        }
      }
      if(!hostAxes.isEmpty())HostAxes.write(t,hostAxes);
      var terrain=data.terrainGround();
      if(terrain!=null)for(int axis=0;axis<2&&axis*2+1<nodes.size();axis++){
        V a=RoadRecord.readNode(nodes.getCompound(axis*2)).position(),b=RoadRecord.readNode(nodes.getCompound(axis*2+1)).position();
        V center=a.add(b).mul(.5);double floor=terrain.top(center.x(),center.z(),center.y());
        var settings=RoadRecord.readSettings(t.getCompound("Main"+(axis+1)));
        var reference=RoadGeometry.build(new Node(a,RoadPlanner.yaw(b.sub(a)),0),new Node(b,RoadPlanner.yaw(b.sub(a)),0),settings);
        t.putBoolean("ServiceElevated"+axis,settings.structure()==Structure.BRIDGE
            ||Double.isFinite(floor)&&center.y()-floor>2
            ||RoadStructures.elevated(reference,RoadStructures.sample(reference,reference.length()/2),terrain));
      }
      if(points.length>=5&&HostAxes.read(t).stream().anyMatch(a->a.curved()||!a.flat()))throw new IllegalArgumentException("五、六向立交仍需要平直且各自等高的主路");
      if(id==null&&HostAxes.retiredInterchange(t))throw new IllegalArgumentException(HostAxes.RETIRED_MESSAGE);
      if(id==null)ContinuousRoads.capture(data,t);
      else if(t.contains("ContinuousSources")){
        // Keep point/paint edits made on generated mains when rebuilding the assembly.
        ContinuousReclaim.refresh(data,t);
      }
      if (id != null) {t.putUUID("Id", id);InterchangeJunctions.captureEdits(data,id,t);}
      return t;
    }
  }

  private static List<RoadRecord> thirdArmChain(RoadData data, CompoundTag inputs, long[] points) {
    if (points.length != 3) return List.of();
    var tags = inputs.getList("Nodes", Tag.TAG_COMPOUND);
    V a = RoadRecord.readNode(tags.getCompound(0)).position(),
        b = RoadRecord.readNode(tags.getCompound(1)).position(),
        c = RoadRecord.readNode(tags.getCompound(2)).position();
    V along = b.sub(a).horizontalUnit(), foot = a.add(along.mul(c.sub(a).dot(along)));
    foot = new V(foot.x(), c.y(), foot.z());
    // C describes a terminating approach, whose old tip need not be exactly at the mathematical
    // AB/CD intersection (or at AB's elevation). Follow its existing straight approach toward AB.
    V direction = foot.sub(c).horizontalUnit();
    double limit =
        foot.sub(c).horizontalLength()
            + RoadRecord.readSettings(inputs.getCompound("Main1")).width() / 2
            + 1;
    BlockPos start = BlockPos.of(points[2]), last = start;
    double farthest = 0;
    Set<BlockPos> visited = new HashSet<>();
    ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    queue.add(start);
    visited.add(start);
    while (!queue.isEmpty() && visited.size() < 512) {
      BlockPos at = queue.remove();
      for (UUID id : data.index.atNode(at)) {
        var r = data.index.roads.get(id).record;
        if (r.assembly() != null || r.settings().style().ramp()) continue;
        boolean forward = r.a().equals(at);
        BlockPos next = forward ? r.b() : r.a();
        V point = (forward ? r.end() : r.start()).position();
        double distance = point.sub(c).dot(direction);
        if (distance <= .01
            || distance > limit
            || Math.abs(point.sub(c).dot(direction.left())) > Math.max(24,limit*.6)
            || visited.contains(next)) continue;
        visited.add(next);
        queue.add(next);
        if (distance > farthest) {
          farthest = distance;
          last = next;
        }
      }
    }
    return last.equals(start) ? List.of() : mainChain(data, start, last);
  }

  private static List<RoadRecord> branchProfile(RoadData data, BlockPos at) {
    return data.index.atNode(at).stream()
        .map(data.index.roads::get)
        .filter(Objects::nonNull)
        .map(b -> b.record)
        .filter(r -> r.assembly() == null && !r.settings().style().ramp())
        .sorted(Comparator.comparing(RoadRecord::id))
        .limit(1)
        .toList();
  }

  static List<RoadRecord> mainChain(RoadData data, BlockPos a, BlockPos b) {
    Map<BlockPos, RoadRecord> via = new HashMap<>();
    Map<BlockPos, BlockPos> previous = new HashMap<>();
    ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    queue.add(a);
    previous.put(a, a);
    while (!queue.isEmpty() && previous.size() < 512) {
      BlockPos at = queue.remove();
      if (at.equals(b)) break;
      for (UUID id : data.index.atNode(at)) {
        var r = data.index.roads.get(id).record;
        if (r.junction()!=null||r.settings().style().ramp() || r.assembly() != null) continue;
        BlockPos next = r.a().equals(at) ? r.b() : r.a();
        if (previous.containsKey(next)) continue;
        previous.put(next, at);
        via.put(next, r);
        queue.add(next);
      }
    }
    if (!previous.containsKey(b)) return List.of();
    List<RoadRecord> chain = new ArrayList<>();
    for (BlockPos at = b; !at.equals(a); at = previous.get(at)) chain.add(via.get(at));
    Collections.reverse(chain);
    HostAxes.chain(chain,a); // Reject folded/angled chains, while retaining smooth curves and grades.
    Set<UUID> ids = new HashSet<>();
    chain.forEach(r -> ids.add(r.id()));
    for (var r : chain)
      for (BlockPos p : List.of(r.a(), r.b()))
        if (!p.equals(a) && !p.equals(b))
          for (UUID id : data.index.atNode(p))
            if (!ids.contains(id)) throw new IllegalArgumentException("选中的主路中间已有其他支路，请缩小选区或先处理该支路");
    return chain;
  }

  public static String build(ServerLevel level, ServerPlayer player, CompoundTag command) {
    try (var workChunks = RoadWorkChunks.open(level)) {
      RoadData data = RoadData.get(level);
      long[] points = command.getLongArray("Points");
      UUID existing = command.hasUUID("Id") ? command.getUUID("Id") : null;
      requireNoEndpointJunction(data,existing);
      CompoundTag t = payload(level, player, points, existing);
      t.put("Options", write(read(command.getCompound("Options"))));
      clearRemovedDesign(t);
      if(ParallelSpui.present(t)){
        ParallelSpui.capture(data,t,player);
        if(player!=null&&read(t.getCompound("Options")).adjustEndpoints()){
          var fitted=new ListTag();ParallelSpui.plan(t).anchors().forEach(node->fitted.add(RoadRecord.writeNode(node)));
          if(!command.getList("SourceNodes",Tag.TAG_COMPOUND).equals(t.getList("Nodes",Tag.TAG_COMPOUND))
              ||!command.getList("FittedNodes",Tag.TAG_COMPOUND).equals(fitted))
            throw new IllegalArgumentException("端点或方案已变化，请重新打开立交并查看端点调整预览");
        }
        return ParallelSpui.build(level,player,t);
      }
      if(command.contains("Corridor"))t.put("Corridor",command.getCompound("Corridor").copy());
      if (!t.contains("Corridor") && !HostAxes.presets(t)
          .contains(read(t.getCompound("Options")).preset()))
        throw new IllegalArgumentException("此布局不适用于当前向数，请重新选择立交预设");
      for (String key : List.of("Main1", "Main2", "Main3"))
        if (command.contains(key)) t.put(key, command.getCompound(key).copy());
      validateMainStyles(t);
      if(t.contains("Corridor"))HostAxes.normalizeCorridor(t);
      if(player!=null&&command.contains("SourceAxesHash")&&command.getInt("SourceAxesHash")!=HostAxes.signature(t))throw new IllegalArgumentException("主路曲线已改变，请重新打开生成器查看预览");
      var plan = t.contains("Corridor")?Corridors.resolve(level,player,t):
          player!=null&&ConfirmedInterchangeFit.eligible(t)&&read(t.getCompound("Options")).adjustEndpoints()
              ?confirmedPlan(t,command):plan(t);
      UUID group = t.hasUUID("Id") ? t.getUUID("Id") : UUID.randomUUID(),
          owner =
              t.hasUUID("Owner")
                  ? t.getUUID("Owner")
                  : player == null ? new UUID(0, 0) : player.getUUID();
      Set<UUID> removed = new HashSet<>();
      var retainedConnector=InterchangeConnectors.preserve(data,t,plan);
      if (!t.hasUUID("Id"))
        for (int axis = 0; axis * 2 + 1 < points.length; axis++)
          for (var r :
              mainChain(data, BlockPos.of(points[axis * 2]), BlockPos.of(points[axis * 2 + 1]))) {
            if (player != null) RoadData.requireOwner(player, r.owner());
            removed.add(r.id());
          }

      if (!t.hasUUID("Id") && points.length == 3)
        for (var r : thirdArmChain(data, t, points)) {
          if (player != null) RoadData.requireOwner(player, r.owner());
          removed.add(r.id());
        }
      for (var built : data.index.roads.values()) {
        var r = built.record;
        boolean main = false;
        for (int axis = 0; axis * 2 + 1 < points.length; axis++)
          main |=
              (r.a().asLong() == points[axis * 2] && r.b().asLong() == points[axis * 2 + 1])
                  || (r.b().asLong() == points[axis * 2] && r.a().asLong() == points[axis * 2 + 1]);
        if (InterchangeJunctions.belongs(data,group,r.assembly()) || main && r.assembly()==null) {
          if (player != null) RoadData.requireOwner(player, r.owner());
          removed.add(r.id());
        }
      }
      for(var leg:plan.legs()){UUID source=ContinuousRoads.sourceId(leg.name());if(source!=null){var old=data.index.roads.get(source);if(old!=null&&player!=null)RoadData.requireOwner(player,old.record.owner());removed.add(source);}}
      // Only unchanged recorded continuations accepted into this plan are replaced.
      var committedContinuousLimits=ContinuousRoads.committedLimits(t,plan.anchors());
      ListTag proposedNodes = new ListTag();
      plan.anchors().forEach(node -> proposedNodes.add(RoadRecord.writeNode(node)));
      boolean adjust = read(t.getCompound("Options")).adjustEndpoints();
      if (player != null
          && adjust
          && (!command
                  .getList("SourceNodes", Tag.TAG_COMPOUND)
                  .equals(t.getList("Nodes", Tag.TAG_COMPOUND))
              || !command.getList("FittedNodes", Tag.TAG_COMPOUND).equals(proposedNodes)))
        throw new IllegalArgumentException("端点或方案已变化，请重新打开立交并查看端点调整预览");
      List<RoadData.NodeMove> moves = new ArrayList<>();
      List<RoadIndex.Built> sharedChanges=new ArrayList<>();
      long[] sourcePoints = points.clone();
      points = points.clone();
      for (int i = 0; i < points.length; i++) {
        BlockPos from = BlockPos.of(sourcePoints[i]);
        var endpoint = InterchangeEndpoints.read(level, player, t, i);
        Node target = plan.anchors().get(i);
        boolean changed = !endpoint.node().equals(target);
        if (!changed && endpoint.marker() != null) continue;
        if(changed&&ContinuousRoads.virtual(t,i,target)){points[i]=BlockPos.containing(target.position().x(),target.position().y(),target.position().z()).asLong();continue;}
        BlockPos outer=ContinuousRoads.physicalEnd(t,i,target);if(!outer.equals(from)){from=outer;var marker=RoadData.requireNode(level,from,player);endpoint=new InterchangeEndpoints.Endpoint(marker.constructionNode(),marker,marker.saveWithoutMetadata());changed=!endpoint.node().equals(target);}
        if (changed && !adjust) throw new IllegalArgumentException("未允许调整端点");
        if(changed)SharedRoadEndpoints.stage(data,level,player,from,endpoint.node(),target,sharedChanges,removed);
        V p = target.position();
        BlockPos to = changed ? BlockPos.containing(p.x(), p.y(), p.z()) : from;
        moves.add(new RoadData.NodeMove(endpoint.marker() == null ? null : from, to, target, endpoint.snapshot()));
        points[i] = to.asLong();
      }
      if (Arrays.stream(points).distinct().count() != points.length)
        throw new IllegalArgumentException("调整后的端点方块重叠");
      HostAxes.fit(t,plan.anchors());
      t.put("Nodes", proposedNodes);
      t.put("PlanningNodes",proposedNodes.copy());
      t.putLongArray("Points", points);
      if(points.length>=5)workChunks.multiInterchange();
      List<RoadIndex.Built> records = new ArrayList<>(sharedChanges);
      ListTag roles = new ListTag();
      ListTag signTopology = new ListTag();
      int part = 0;
      if(read(t.getCompound("Options")).toll().enabled()){t.put("TollConnectorRoads",new ListTag());t.remove("TollConnectorShape");}
      t.putBoolean("IntegratedToll",read(t.getCompound("Options")).toll().enabled());
      var junctionSpecs=new ArrayList<JunctionSpec>();
      var junctionTags=new ArrayList<CompoundTag>();
      for (var leg : plan.legs())
        for (Mesh mesh : List.of(leg.mesh())) {
          Node a = RoadRibbon.start(mesh), b = RoadRibbon.end(mesh);
          BlockPos pa = anchor(points, t, a.position()), pb = anchor(points, t, b.position());
          UUID sourceId=ContinuousRoads.sourceId(leg.name());
          UUID roadId = sourceId!=null?ContinuousSections.id(leg.name()):
              UUID.nameUUIDFromBytes(
                  (group + ":" + part++).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          RoadRecord record =
              new RoadRecord(roadId, owner, pa, pb, a, b, mesh.settings(), false, 4)
                  .alignment(group, mesh);
          if(sourceId!=null){
            var source=ContinuousSections.source(t,leg.name());
            if(a.position().distance(source.start().position())<1e-6)pa=source.a();
            if(b.position().distance(source.end().position())<1e-6)pb=source.b();
            for(var move:moves){if(pa.equals(move.from()))pa=move.to();if(pb.equals(move.from()))pb=move.to();}
            // A sliced source position may straddle an integer by a few ulps.
            // The confirmed marker address takes precedence over that derived source address.
            pa=anchor(points,t,a.position(),pa);pb=anchor(points,t,b.position(),pb);
            record=new RoadRecord(roadId,source.owner(),pa,pb,a,b,mesh.settings(),source.automatic(),source.clearance(),List.of(),source.endCaps(),source.buildVersion(),ContinuousRoads.donor(leg.name())?null:group,mesh.samples(),source.furniturePhase());
          } else record=record.withAttachments(com.sora.splineroads.core.RoadAttachments.Data.EMPTY);
          if(leg.name().equals(DoubleTrumpetPlanner.CONNECTOR)){
            if(!retainedConnector.isEmpty()){
              for(var keep:retainedConnector){records.add(new RoadIndex.Built(keep));var signLeg=new CompoundTag();signLeg.putUUID("Road",keep.id());signLeg.putInt("From",-1);signLeg.putInt("To",-1);signLeg.putBoolean("Ramp",false);signTopology.add(signLeg);}continue;
            }
            InterchangeConnectors.capture(t,record);
          }
          if(leg.toll()!=null)record=InterchangeConnectors.group(record.toll(leg.toll()),group);
          if(DoubleTrumpetToll.connector(leg.name())&&!leg.name().equals(DoubleTrumpetPlanner.CONNECTOR))InterchangeConnectors.capturePart(t,record);
          if(leg.junction()!=null){
            var spec=leg.junction().spec();int slot=junctionSpecs.indexOf(spec);
            if(slot<0){slot=junctionSpecs.size();junctionSpecs.add(spec);junctionTags.add(InterchangeJunctions.descriptor(group,owner,slot,spec));}
            record=InterchangeJunctions.piece(group,owner,slot,leg.junction());roadId=record.id();
          }
          records.add(new RoadIndex.Built(record));
          var signLeg=new CompoundTag();signLeg.putUUID("Road",roadId);signLeg.putInt("From",leg.from());signLeg.putInt("To",leg.to());signLeg.putBoolean("Ramp",mesh.settings().style().ramp());signTopology.add(signLeg);
          if(t.contains("Corridor")){
            CompoundTag role=new CompoundTag();role.putUUID("Road",roadId);role.putString("Role",leg.name());roles.add(role);
          }
        }
      var partitionOwners=ContinuousSections.signOwners(plan,records);
      for(var entry:partitionOwners.entrySet())for(var state:data.smartSigns.road(entry.getKey()))entry.getValue().putIfAbsent(state.key().slot(),entry.getKey());
      var logicalSources=new HashSet<UUID>();for(var leg:plan.legs())if(leg.name().contains("@")&&data.streets.containsKey(ContinuousRoads.sourceId(leg.name())))logicalSources.add(ContinuousRoads.sourceId(leg.name()));
      ContinuousSections.updateSources(t,ContinuousService.sourcePlan(t,plan));
      // A partition preserves point identities, while its owning road ID may change.
      var pointOwners=new HashMap<UUID,UUID>();for(var r:records)for(var p:r.record.settings().options().lanePoints().points())pointOwners.put(p.id(),r.record.id());
      var remapped=new ArrayList<RoadIndex.Built>();for(var old:data.index.roads.values())if(!removed.contains(old.record.id())){
        var r=ContinuousSections.remap(old.record,pointOwners);if(r!=old.record){removed.add(r.id());remapped.add(new RoadIndex.Built(r));}}
      records.addAll(remapped);
      for(int i=0;i<records.size();i++){var r=ContinuousSections.remap(records.get(i).record,pointOwners);if(r!=records.get(i).record)records.set(i,new RoadIndex.Built(r));}
      // Junction fill triangles are standard SR junction pieces, not additional
      // road alignments. Keep the original alignment budget and bound them separately.
      long junctionPieces=records.stream().filter(r->r.record.junction()!=null).count();
      if (records.size()-junctionPieces > 128 || junctionSpecs.size()>2 || junctionPieces>512)
        throw new IllegalArgumentException("立交道路或路口分段过多，请缩小范围");
      // Recheck every external road, not just the two selected axes, before any writes.
      for (var proposed : records)
        for (var old : data.index.roads.values())
          if (!removed.contains(old.record.id()) && RoadIndex.overlapXZ(proposed.mesh, old.mesh, 1))
            checkExternal(proposed.mesh, old.mesh);
      List<RoadIndex.Built> existingRoads = new ArrayList<>();
      for (UUID oldId : removed)
        if (data.index.roads.containsKey(oldId)) existingRoads.add(data.index.roads.get(oldId));
      workChunks.roads(existingRoads);
      for (long point : t.getLongArray("PreservedNodes")) workChunks.node(BlockPos.of(point));
      Set<BlockPos> preserved = new HashSet<>();
      for (long point : points) preserved.add(BlockPos.of(point));
      for (long point : t.getLongArray("PreservedNodes"))
        if (level.getBlockEntity(BlockPos.of(point)) instanceof NodeEntity)
          preserved.add(BlockPos.of(point));
      for (UUID oldId : removed) {
        var old = data.index.roads.get(oldId);
        if(t.contains("Corridor")&&old!=null)for(BlockPos end:List.of(old.record.a(),old.record.b())){
          boolean external=data.index.atNode(end).stream().anyMatch(r->!removed.contains(r));
          boolean retained=records.stream().anyMatch(r->r.record.a().equals(end)||r.record.b().equals(end));
          if(external&&!retained)throw new IllegalArgumentException("辅路端点已连接其他道路，不能改变该接点位置；请先处理外接道路");
        }
        if (old != null)
          for (var p : List.of(old.record.a(), old.record.b()))
            if (level.getBlockEntity(p) instanceof NodeEntity) preserved.add(p);
      }
      if(t.contains("Corridor")||!ContinuousRoads.sources(t).isEmpty()){
        // Generated frontage and continuous-range endpoints are owned by this saved combination.
        // Retire obsolete ones in the SAME transaction as its new pavement,
        // including markers preserved by earlier versions after an update.
        for(BlockPos old:new ArrayList<>(preserved)){
          if(records.stream().anyMatch(r->r.record.a().equals(old)||r.record.b().equals(old)))continue;
          if(data.index.atNode(old).stream().anyMatch(id->!removed.contains(id)))continue;
          if(level.getBlockEntity(old) instanceof NodeEntity&&moves.stream().noneMatch(m->old.equals(m.from())))moves.add(RoadData.NodeMove.retire(old));
          preserved.remove(old);
        }
      }
      Set<BlockPos> destinations = new HashSet<>();
      for(var pos:InterchangeConnectors.obsoleteNodes(data,t,records,removed)){
        if(level.getBlockEntity(pos) instanceof NodeEntity){RoadData.requireNode(level,pos,player);moves.add(RoadData.NodeMove.retire(pos));}
        preserved.remove(pos);
      }
      moves.forEach(move -> {if(move.to()!=null)destinations.add(move.to());});
      if(t.contains("Corridor")||!ContinuousRoads.sources(t).isEmpty()||plan.legs().stream().anyMatch(l->l.name().contains("@"))){
        if(t.contains("Corridor"))t.put("CorridorRoles",roles);
        // Generated frontage ends are real selectable endpoints for local-road extensions.
        Set<BlockPos> added=new HashSet<>();
        for(var record:records)if(!record.mesh.settings().style().ramp())for(boolean first:new boolean[]{true,false}){
          BlockPos pos=first?record.record.a():record.record.b();
          if(destinations.contains(pos)){preserved.add(pos);continue;}
          if(level.getBlockEntity(pos) instanceof NodeEntity){RoadData.requireNode(level,pos,player);preserved.add(pos);continue;}
          if(!added.add(pos))continue;
          Node node=InterchangeEndpoints.generated(points,plan.anchors(),pos,first?record.record.start():record.record.end());
          CompoundTag snapshot=new CompoundTag();snapshot.putUUID("Owner",record.record.owner());
          snapshot.put("Node",RoadRecord.writeNode(node));snapshot.putBoolean("HeightExplicit",true);
          moves.add(new RoadData.NodeMove(null,pos,node,snapshot));destinations.add(pos);preserved.add(pos);
        }
      }
      for (var p : preserved) if (!destinations.contains(p)) RoadData.requireNode(level,p,player);
      ContinuousService.prepare(t,plan,records,junctionTags,signTopology,group,owner);
      var snapshotSources=ContinuousReclaim.snapshotCandidates(data,t,owner);
      data.replaceAssembly(level, player, records, removed, preserved, moves,editLimit(t));
      if(!committedContinuousLimits.isEmpty())t.put("ContinuousLimits",committedContinuousLimits);
      partitionOwners.forEach(data.smartSigns::splitKeys);
      for(var leg:plan.legs())if(logicalSources.contains(ContinuousRoads.sourceId(leg.name())))data.streets.put(ContinuousSections.id(leg.name()),data.index.roads.get(ContinuousSections.id(leg.name())).record);
      InterchangeJunctions.forget(data,group);
      for(var junction:junctionTags)data.junctions.put(junction.getUUID("Id"),junction);
      t.putBoolean("StandardServiceJunctions",!junctionTags.isEmpty());
      for (var move : moves) if (!destinations.contains(move.from())) preserved.remove(move.from());
      t.putLongArray(
          "PreservedNodes", preserved.stream().mapToLong(BlockPos::asLong).sorted().toArray());
      t.putUUID("Id", group);
      t.putUUID("Owner", owner);
      t.remove("Partial");
      ListTag occupied=new ListTag();
      for(var record:records)if(group.equals(record.record.assembly())&&ContinuousRoads.sources(t).stream().anyMatch(source->source.road().id().equals(record.record.id()))){var range=new CompoundTag();range.putUUID("Road",record.record.id());var source=ContinuousRoads.sources(t).stream().filter(entry->entry.road().id().equals(record.record.id())).findFirst().orElseThrow();int first=source.axis()*2;double start=com.sora.splineroads.core.RoadAttachments.station(record.mesh,plan.anchors().get(first).position()),end=com.sora.splineroads.core.RoadAttachments.station(record.mesh,plan.anchors().get(first+1).position());range.putDouble("From",Math.min(start,end));range.putDouble("To",Math.max(start,end));range.put("Start",RoadRecord.writeNode(new Node(com.sora.splineroads.core.RoadAttachments.at(record.mesh,Math.min(start,end)),0,0)));range.put("End",RoadRecord.writeNode(new Node(com.sora.splineroads.core.RoadAttachments.at(record.mesh,Math.max(start,end)),0,0)));occupied.add(range);}
      t.put("OccupiedRoads",occupied);
      for(Tag value:t.getList("ContinuousSources",Tag.TAG_COMPOUND)){
        var source=(CompoundTag)value;var original=RoadRecord.load(source.getCompound("Road"));var current=data.index.roads.get(original.id());
        if(current!=null){var r=current.record;
          if(!source.getBoolean("Detached")&&!source.getBoolean(ContinuousService.LOGICAL))source.put("Road",ContinuousRoads.updatedSource(original,r).header());
          else if(source.getBoolean("Detached")&&(source.getBoolean("Reclaimable")||snapshotSources.contains(original.id())||records.stream().anyMatch(built->built.record.id().equals(original.id())))){
            source.put("Road",r.header());source.putBoolean("DetachedExact",true);
          }
        }
        source.remove("Reclaimable");
      }
      ContinuousService.capture(data,t);
      t.put("SignTopology",signTopology);
      data.interchanges.put(group, t.copy());data.smartSigns.assemblyChanged(group);
      data.setDirty();
      return (t.contains("Corridor")?"道路组合已整体建成：":"立交已整体建成：")
          + plan.movements()
          + (t.contains("Corridor")?" 条同侧连接匝道，":" 条转向匝道，")
          + records.size()
          + " 个可分区加载的路段；对应生成器右键路面可整体编辑";
    }
  }

  private static BlockPos anchor(long[] points, CompoundTag t, V p) {
    return anchor(points,t,p,BlockPos.containing(p.x(),p.y(),p.z()));
  }

  static BlockPos anchor(long[] points, CompoundTag t, V p, BlockPos fallback) {
    var nodes = t.getList("Nodes", Tag.TAG_COMPOUND);
    for (int i = 0; i < points.length; i++)
      if (RoadRecord.readNode(nodes.getCompound(i)).position().distance(p) < 1e-5)
        return BlockPos.of(points[i]);
    return fallback;
  }

  static void checkExternal(Mesh a, Mesh b) { checkExternal(a,b,null); }
  static void checkExternal(Mesh a, Mesh b, Mesh previous) {
    RoadClearance.check(a,b,previous);
  }

  public static long[] points(RoadData data, UUID id) {
    var saved = data.interchanges.get(id);
    if (saved == null) throw new IllegalArgumentException("立交已不存在");
    return saved.getLongArray("Points").clone();
  }

  private static Set<BlockPos> selected(CompoundTag saved) {
    Set<BlockPos> nodes = new HashSet<>();
    if (saved != null)
      for (String key : List.of("Points", "PreservedNodes"))
        for (long point : saved.getLongArray(key)) nodes.add(BlockPos.of(point));
    return nodes;
  }

  /** A partial deletion keeps the group editable, but never resurrects removed roads implicitly. */
  public static void removePart(ServerLevel level, ServerPlayer player, UUID roadId) {
    var data = RoadData.get(level);
    var road = data.index.roads.get(roadId);
    if (road == null) throw new IllegalArgumentException("道路已经不存在");
    if (player != null) RoadData.requireOwner(player, road.record.owner());
    UUID group = InterchangeJunctions.parent(data,road.record.assembly());
    requireNoEndpointJunction(data,group);
    var saved = data.interchanges.get(group);
    if (saved != null && player != null) RoadData.requireOwner(player, saved.getUUID("Owner"));
    data.replaceAssembly(level, player, List.of(), Set.of(roadId), selected(saved),List.of(),editLimit(saved));
    if (saved != null) {
      if (data.index.roads.values().stream().noneMatch(r -> group.equals(r.record.assembly())))
        data.interchanges.remove(group);
      else saved.putBoolean("Partial", true);
      data.setDirty();
    }
  }

  /** Remove connecting facilities and detach the surviving axial roads, preserving their paths. */
  public static void removeRamps(ServerLevel level, ServerPlayer player, UUID group) {
    var data = RoadData.get(level);
    if(data.interchanges.containsKey(group)&&ParallelSpui.present(data.interchanges.get(group))){ParallelSpui.remove(level,player,data.interchanges.get(group).copy());return;}
    requireNoEndpointJunction(data,group);
    var saved = data.interchanges.get(group);
    if (saved == null) throw new IllegalArgumentException("立交不存在");
    if (player != null) RoadData.requireOwner(player, saved.getUUID("Owner"));
    Set<UUID> removed = new HashSet<>();
    List<RoadIndex.Built> mains = new ArrayList<>();
    Set<BlockPos> selected = selected(saved);
    List<RoadData.NodeMove> markers = new ArrayList<>();
    Set<BlockPos> added = new HashSet<>();
    Set<UUID> restoredServiceMain=saved.contains("Options")?InterchangeJunctions.restoreMain(data,saved,mains):Set.of();
    try (var chunks = RoadWorkChunks.open(level)) {
      for (var built : data.index.roads.values()) {
        var r = built.record;
        if (!InterchangeJunctions.belongs(data,group,r.assembly())) continue;
        if (player != null) RoadData.requireOwner(player, r.owner());
        removed.add(r.id());
        if (restoredServiceMain.contains(r.id())||r.junction()!=null||r.settings().style().ramp()
            || r.settings().style() == Style.UNMARKED
            || built.mesh.closed()) continue;
        // Axial main roads are the only two-way carriageways; pads and ring facilities are
        // excluded.
        boolean continuousMain=ContinuousRoads.sources(saved).stream().anyMatch(source->source.road().id().equals(r.id()));
        if (!continuousMain&&!saved.contains("Corridor") && !RoadProfile.catalog(r.settings().style()).twoWay()) continue;
        // The terminating C stem belongs to the three-way interchange. Removing its ramps
        // must remove this generated stub as well, retaining only the user's C marker.
        int arms=saved.getLongArray("Points").length;
        if (!continuousMain&&(arms==3||arms==5)
            && (r.a().asLong() == saved.getLongArray("Points")[arms-1]
                || r.b().asLong() == saved.getLongArray("Points")[arms-1])) continue;
        mains.add(new RoadIndex.Built(r.structures(List.of()).alignment(null, built.mesh)));
        for (boolean first : new boolean[] {true, false}) {
          BlockPos pos = first ? r.a() : r.b();
          selected.add(pos);
          chunks.node(pos);
          if (level.getBlockEntity(pos) instanceof NodeEntity) {
            RoadData.requireNode(level, pos, player);
          } else if (added.add(pos)) {
            // Three-way mainline stubs have an internal endpoint without a physical marker.
            Node node = first ? r.start() : r.end();
            CompoundTag snapshot = new CompoundTag();
            snapshot.putUUID("Owner", r.owner());
            snapshot.put("Node", RoadRecord.writeNode(node));
            snapshot.putBoolean("HeightExplicit", true);
            markers.add(new RoadData.NodeMove(null, pos, node, snapshot));
          }
        }
      }
      data.replaceAssembly(level, player, mains, removed, selected, markers,editLimit(saved));
      InterchangeJunctions.forget(data,group);
      data.interchanges.remove(group);
      data.setDirty();
    }
  }

  public static void remove(ServerLevel level, ServerPlayer player, UUID id) {
    var current=RoadData.get(level).interchanges.get(id);if(current!=null&&ParallelSpui.present(current)){ParallelSpui.remove(level,player,current.copy());return;}
    var data = RoadData.get(level);
    requireNoEndpointJunction(data,id);
    var saved = data.interchanges.get(id);
    if (saved == null) throw new IllegalArgumentException("立交不存在");
    if (player != null) RoadData.requireOwner(player, saved.getUUID("Owner"));
    Set<UUID> ids = new HashSet<>();
    for (var r : data.index.roads.values())
      if (InterchangeJunctions.belongs(data,id,r.record.assembly())) ids.add(r.record.id());
    data.replaceAssembly(
        level,
        player,
        List.of(),
        ids,
        Arrays.stream(saved.getLongArray("Points"))
            .mapToObj(BlockPos::of)
            .collect(java.util.stream.Collectors.toSet()),InterchangeConnectors.obsoleteNodes(data,saved.copy(),List.of(),ids).stream().filter(p->level.getBlockEntity(p) instanceof NodeEntity).map(RoadData.NodeMove::retire).toList(),editLimit(saved));
    InterchangeJunctions.forget(data,id);
    data.interchanges.remove(id);
    data.setDirty();
  }

  static void requireNoEndpointJunction(RoadData data,UUID group){
    if(group!=null&&data.streets.values().stream().anyMatch(r->group.equals(r.assembly())))
      throw new IllegalArgumentException("组合端点已形成路口；请先删除该路口的外接支路，再调整或删除组合");
  }
  private static int editLimit(CompoundTag t){return t!=null&&t.getLongArray("Points").length>=3?RoadLimits.MAX_MULTI_INTERCHANGE_EDIT_CELLS:RoadLimits.MAX_EDIT_CELLS;}
  private Interchanges() {}
}
