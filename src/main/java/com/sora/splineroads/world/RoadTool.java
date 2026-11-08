package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.net.RoadNetwork;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class RoadTool extends Item {
  public static Consumer<CompoundTag> openClient = t -> {};

  public static boolean showsNodes(ItemStack stack) {
    return stack.getItem() instanceof LanePointTool || stack.getItem() instanceof LaneRampTool || stack.getItem() instanceof ElevatedPointTool || stack.getItem() instanceof AttachedPointTool
        || stack.getItem() instanceof LaneLineTool
        || stack.getItem() instanceof RoadTool
        || stack.getItem() instanceof RoadSplitTool
        || stack.getItem() instanceof JunctionTool
        || stack.getItem() instanceof YJunctionTool
        || stack.getItem() instanceof RoundaboutTool
        || stack.getItem() instanceof TollTool
        || stack.getItem() instanceof InterchangeTool
        || stack.getItem() instanceof RoadRemover
        || stack.is(com.sora.splineroads.SplineRoads.NODE_ITEM.get());
  }


  /** Old planted medians may cover the marker; keep their endpoints editable before rebuilding. */
  public static BlockPos endpointAtHit(UseOnContext context) {
    return endpointAtHit(context.getLevel(), context.getClickedPos(), context.getClickLocation());
  }

  public static BlockPos endpointAtHit(
      Level level, BlockPos clicked, net.minecraft.world.phys.Vec3 hit) {
    if (!RoadBlocks.isCollider(level.getBlockState(clicked)))
      return clicked;
    BlockPos best = clicked;
    double nearest = .9;
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++)
        for (int dy = -2; dy <= 0; dy++) {
          BlockPos p = clicked.offset(dx, dy, dz);
          if (level.getBlockEntity(p) instanceof NodeEntity node) {
            V v = node.node().position();
            double distance = Math.hypot(v.x() - hit.x, v.z() - hit.z);
            if (distance < nearest && hit.y - v.y() >= -.15 && hit.y - v.y() <= 2) {
              nearest = distance;
              best = p;
            }
          }
        }
    return best;
  }

  public RoadTool() { super(new Properties().stacksTo(1)); }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
    BlockPos pos = endpointAtHit(context);
    var level = player.serverLevel();
    CompoundTag tool = context.getItemInHand().getOrCreateTag();
    try (var workChunks = RoadWorkChunks.open(level)) {
      if(!player.isShiftKeyDown()){var marker=AttachedPointTool.pick(player);if(marker!=null){OneSidedJunctions.selectedAttached(player,tool,marker.road(),marker.point());return InteractionResult.CONSUME;}}
      if(!player.isShiftKeyDown()&&!(level.getBlockEntity(pos) instanceof NodeEntity)){
        var picked=pick(player);var hit=context.getClickLocation();
        var attached=picked==null?null:AttachedPointTool.nearest(picked,new V(hit.x,hit.y,hit.z));
        if(attached!=null&&(SideHosts.attachable(picked.record)||attached.elevated()&&ElevatedPointTool.supported(picked.record))){OneSidedJunctions.selectedAttached(player,tool,picked,attached);return InteractionResult.CONSUME;}
      }
      if (level.getBlockEntity(pos) instanceof NodeEntity node) {
        RoadData.requireOwner(player, node.owner);
        if(player.isShiftKeyDown()&&AutoJunctions.center(RoadData.get(level),pos)!=null)
          throw new IllegalArgumentException("这是路口中心，请用普通路口编辑器编辑；道路连接器用于接入路段");
        if (player.isShiftKeyDown()) {
          CompoundTag t = new CompoundTag();
          t.putString("Kind", "node");

          t.putLong("Pos", pos.asLong());
          t.put("Node", RoadRecord.writeNode(node.node()));
          t.put("OriginalNode", RoadRecord.writeNode(node.node()));
          net.minecraft.nbt.ListTag attached = new net.minecraft.nbt.ListTag();
          RoadData nodeData = RoadData.get(level);
          for (var built : nodeData.index.roads.values()) {
            var r = built.record;
            if (!r.a().equals(pos) && !r.b().equals(pos)) continue;
            if (r.assembly() != null)
              throw new IllegalArgumentException("自动立交端点请先用立交连接器删除整座，再调整高差后重建");
            CompoundTag entry = new CompoundTag();
            entry.put("Road", r.header());
            entry.put("AutoA", RoadData.writeHint(nodeData.hint(level, r.a(), true, r.id())));
            entry.put("AutoB", RoadData.writeHint(nodeData.hint(level, r.b(), false, r.id())));
            attached.add(entry);
          }
          t.put("Attached", attached);
          RoadNetwork.open(player, t);
          return InteractionResult.CONSUME;
        }
        if(OneSidedJunctions.selectedEndpoint(player,tool,pos))return InteractionResult.CONSUME;
        String dimension = level.dimension().location().toString();
        if (!tool.contains("Start") || !dimension.equals(tool.getString("Dimension"))) {
          tool.remove(CurveControlNodes.KEY);
          tool.putLong("Start", pos.asLong());
          tool.putString("Dimension", dimension);
          player.displayClientMessage(
              Component.literal("已选起点 " + pos.toShortString() + "，请右键另一个端点；Shift＋右键端点可编辑朝向与坡度"),
              true);
        } else {
          BlockPos a = BlockPos.of(tool.getLong("Start"));
          if (a.equals(pos)) throw new IllegalArgumentException("请选另一个端点；Shift＋右键空气清除选择");
          NodeEntity first = RoadData.requireNode(level, a, player);
          if(TollRoads.reverseConnection(RoadData.get(level),a,pos)){
            BlockPos swap=a;a=pos;pos=swap;NodeEntity swapNode=first;first=node;node=swapNode;
            tool.putLong("Start",a.asLong());
          }
          CompoundTag t = new CompoundTag();
          t.putString("Kind", "road");

          t.putLong("A", a.asLong());
          t.putLong("B", pos.asLong());
          RoadData data = RoadData.get(level);
          t.put("StartNode", RoadRecord.writeNode(data.connectionNode(level,a,true,null,node.constructionNode().position())));
          t.put("EndNode", RoadRecord.writeNode(data.connectionNode(level,pos,false,null,first.constructionNode().position())));
          var saved =
              tool.contains("Settings")
                  ? RoadRecord.readSettings(tool.getCompound("Settings"))
                  : new Settings(
                      Mode.AUTO, Style.O2_YELLOW, 9, 1, .35, 90);
          if(tool.getInt("PreferencesVersion")<3)
            saved=saved.options(saved.options().infrastructure(saved.options().infrastructure().grade(.06).autoSpan(true)));
          saved=com.sora.splineroads.core.RoadPreferences.fresh(saved);
          double width =
              tool.getInt("PreferencesVersion") >= 2 ? saved.width() : saved.style().defaultWidth();
          Style choice = saved.style();
          if (choice.ramp()) {
            choice = Style.TWO_LANE;
            width = 9;
          }
          if (!choice.ramp() && !com.sora.splineroads.core.RoadProfile.modern(choice))
            width = data.inheritedWidth(a, pos, width, null);
          Settings settings =
              new Settings(
                      ManualPlanner.inSession()&&CurveControlNodes.controls(tool).isEmpty()?Mode.STRAIGHT:Mode.AUTO,
                      choice,
                      width,
                      saved.thickness(),
                      saved.tension(),
                      saved.arcDegrees())
                  .options(
                      saved
                          .options()
                          .lanePoints(com.sora.splineroads.core.LanePoints.Data.EMPTY)
                          .attachments(com.sora.splineroads.core.RoadAttachments.Data.EMPTY)
                          .ends(com.sora.splineroads.core.RoadTransitions.Ends.NONE)
                          .infrastructure(saved.options().infrastructure().clearEdits())
                          .lift(.5, 0)
                          .route(saved.options().routing().offset(0, 0).controls(CurveControlNodes.controls(tool)).through(tool.getBoolean("CurveThrough")))
                          .outsets(0, 0)
                          .sides(
                              com.sora.splineroads.core.RoadProfile.Side.AUTO,
                              com.sora.splineroads.core.RoadProfile.Side.AUTO))
                  .structure(saved.structure())
                  .rampTurn(RampTurn.LEGACY);
          t.put(
              "Settings",
              RoadRecord.writeSettings(
                  RoadData.joinWidths(
                      settings, data.endpointWidth(a, null), data.endpointWidth(pos, null))));
          t.putDouble("JoinWidthA", data.endpointWidth(a, null));
          t.putDouble("JoinWidthB", data.endpointWidth(pos, null));
          data.jointPayload(t, a, pos, null);
          if(t.contains("JoinSectionA")||t.contains("JoinSectionB")){
            var port=RoadRecord.readSettings(t.getCompound(t.contains("JoinSectionA")?"JoinSectionA":"JoinSectionB"));
            settings=continuation(settings,port);
            t.put("Settings",RoadRecord.writeSettings(settings));
          }

          t.put(
              "AutoA",
              RoadData.writeHint(
                  data.constructionHint(
                      level, a, true, null, node.constructionNode().position(), false)));
          t.put(
              "AutoB",
              RoadData.writeHint(
                  data.constructionHint(
                      level, pos, false, null, first.constructionNode().position(), false)));

          AutoJunctions.enrich(t,data,a,pos,null);
          RoadNetwork.open(player, t);
        }
        return InteractionResult.CONSUME;
      }
      var index = RoadData.get(level).index;
      var ids = index.at(pos.asLong());
      if (!ids.isEmpty()) {
        var hit = context.getClickLocation();
        var picked=pick(player);
        var road = picked!=null&&ids.contains(picked.record.id())?picked.record:
            ids.stream()
                .map(index.roads::get)
                .min(
                    java.util.Comparator.comparingDouble(
                        r ->
                            r.mesh.samples().stream()
                                .mapToDouble(
                                    s ->
                                        Math.abs(s.center().y() - hit.y)
                                            + Math.hypot(
                                                    s.center().x() - hit.x, s.center().z() - hit.z)
                                                * .01)
                                .min()
                                .orElse(1e9)))
                .orElseThrow()
                .record;
        requireTool(road);
        openRoad(player, road, false);
        return InteractionResult.CONSUME;
      }
      player.displayClientMessage(Component.literal("右键端点进行连接，右键已有路面可编辑或删除"), true);
    } catch (IllegalArgumentException e) {
      if(ManualPlanner.inSession())throw e;
      player.displayClientMessage(Component.literal(e.getMessage()), false);
    }
    return InteractionResult.CONSUME;
  }

  public static RoadIndex.Built pick(ServerPlayer player) {
    var eye = player.getEyePosition();
    var look = player.getLookAngle();
    return RoadData.get(player.serverLevel())
        .index
        .pick(new V(eye.x, eye.y, eye.z), new V(look.x, look.y, look.z), 256);
  }

  /** Refresh the authoritative dependency list before opening the shared deletion UI. */
  public static void openDeletion(ServerPlayer player, UUID id) {openDeletion(player,id,new CompoundTag());}

  public static void openDeletion(ServerPlayer player, UUID id,CompoundTag command) {
    var data=RoadData.get(player.serverLevel());
    RoadRecord road=data.streets.get(id);
    if(road==null){var built=data.index.roads.get(id);if(built!=null)road=built.record;}
    if(road==null)throw new IllegalArgumentException("道路已删除或尚未加载，请重新选择");
    RoadData.requireOwner(player,road.owner());
    openRoad(player,road,true,command);
  }

  public static void openRoad(ServerPlayer player, RoadRecord road, boolean deletion) {
    openRoad(player,road,deletion,new CompoundTag());
  }

  private static void openRoad(ServerPlayer player, RoadRecord road, boolean deletion,CompoundTag command) {
    if(!deletion&&ElevatedAccessRoads.saved(RoadData.get(player.serverLevel()),road.assembly())){ElevatedAccessRoads.open(player,road.assembly());return;}
    if(road.railway())throw new IllegalArgumentException("铁路路基请使用铁路路基连接器编辑或删除");
    java.util.function.Consumer<CompoundTag> reply=t->{com.sora.splineroads.net.RoadActionContext.echo(command,t);RoadNetwork.open(player,t);};
    try (var workChunks = RoadWorkChunks.open(player.serverLevel())) {
      var level = player.serverLevel();
      RoadData.requireOwner(player, road.owner());
      if(road.junction()!=null&&road.junction().spec().oneSide()!=null){var t=OneSidedJunctions.payload(level,player,road.assembly());if(!deletion)t=SideRoads.payload(t);t.putBoolean("DeleteOnly",deletion);reply.accept(t);return;}
      if(road.toll()!=null){var t=TollRoads.payload(level,player,new long[0],road.id());t.putBoolean("DeleteOnly",deletion);reply.accept(t);return;}
      if (road.junction() != null && deletion && road.junction().get().arm()>=0) {
        var saved=RoadData.get(level).junctions.get(road.assembly());
        if(saved!=null&&saved.getBoolean("Auto")){
          String key=saved.getList("ArmRoads",net.minecraft.nbt.Tag.TAG_STRING).getString(road.junction().get().arm());
          RoadRecord branch=RoadData.get(level).streets.get(java.util.UUID.fromString(key));if(branch==null&&RoadData.get(level).index.roads.containsKey(java.util.UUID.fromString(key)))branch=RoadData.get(level).index.roads.get(java.util.UUID.fromString(key)).record;
          if(branch!=null){var t=new CompoundTag();t.putString("Kind","road");t.putBoolean("DeleteOnly",true);t.putUUID("Id",branch.id());t.put("Road",branch.header());LaneDeletes.payload(RoadData.get(level),branch.id(),t);reply.accept(t);return;}
        }
      }
      if(road.junction()!=null&&!deletion){
        var logical=JunctionRoads.logical(RoadData.get(level),road);
        if(logical!=null)road=logical;
        else {reply.accept(JunctionRoads.payload(level,player,road));return;}
      }
      if (road.junction() != null) {
        CompoundTag t = Junctions.selectedPayload(level,player,road);
        t.putBoolean("DeleteOnly",true); reply.accept(t); return;
      }
      if(!deletion && LaneTopology.metadata(road).link()!=null){reply.accept(LaneRamps.tunnelChoices(LaneRamps.payload(road,true),LaneTopology.records(RoadData.get(level))));return;}
      if(!deletion && RoadData.get(level).streets.containsKey(road.id()))road=RoadData.get(level).editableRoad(road.id());
      if (deletion) {
        CompoundTag t = new CompoundTag();
        t.putString("Kind", "road");
        t.putBoolean("DeleteOnly", true);
        t.putUUID("Id", road.id());
        t.put("Road", road.header());
        t.putBoolean("ElevatedDelete",ElevatedAccessRoads.saved(RoadData.get(level),road.assembly()));
        LaneDeletes.payload(RoadData.get(level),road.id(),t);
        reply.accept( t);
        return;
      }
      if(road.assembly()!=null&&RoadData.get(level).interchanges.getOrDefault(road.assembly(),new CompoundTag()).getBoolean("YJunction"))throw new IllegalArgumentException("请用普通路口编辑器右键此 Y 字路口");
      if (road.assembly() != null) throw new IllegalArgumentException("此路段属于自动立交，请用立交连接器右键整体编辑");

      CompoundTag t = new CompoundTag();
      t.putBoolean("DeleteOnly", deletion);

      t.put("Road", road.header());
      t.putString("Kind", "road");
      t.putUUID("Id", road.id());
      t.putLong("A", road.a().asLong());
      t.putLong("B", road.b().asLong());
      t.put(
          "StartNode",
          RoadRecord.writeNode(
              new Node(
                  RoadData.requireNode(level, road.a(), player).constructionNode().position(),
                  road.start().yaw(),
                  road.start().grade())));
      t.put(
          "EndNode",
          RoadRecord.writeNode(
              new Node(
                  RoadData.requireNode(level, road.b(), player).constructionNode().position(),
                  road.end().yaw(),
                  road.end().grade())));
      t.put(
          "Settings",
          RoadRecord.writeSettings(
              road.automatic()
                  ? RoadPlanner.mode(road.settings(), Mode.AUTO, road.settings().arcDegrees())
                  : road.settings()));
      RoadData data = RoadData.get(level);
      t.putDouble("JoinWidthA", data.endpointWidth(road.a(), road.id()));
      t.putDouble("JoinWidthB", data.endpointWidth(road.b(), road.id()));
      data.jointPayload(t, road.a(), road.b(), road.id());
      t.put(
          "AutoA",
          RoadData.writeHint(
              data.constructionHint(
                  level, road.a(), true, road.id(), road.end().position(), false)));
      t.put(
          "AutoB",
          RoadData.writeHint(
              data.constructionHint(
                  level, road.b(), false, road.id(), road.start().position(), false)));

      if(!road.settings().style().ramp())AutoJunctions.enrich(t,data,road.a(),road.b(),road.id());
      reply.accept( t);
    }
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    ItemStack stack = player.getItemInHand(hand);
    if(!level.isClientSide&&!player.isShiftKeyDown()){var server=(ServerPlayer)player;var marker=AttachedPointTool.pick(server);if(marker!=null){try{OneSidedJunctions.selectedAttached(server,stack.getOrCreateTag(),marker.road(),marker.point());}catch(IllegalArgumentException e){player.displayClientMessage(Component.literal(e.getMessage()),false);}return InteractionResultHolder.success(stack);}}
    if (player.isShiftKeyDown()) {
      if (!level.isClientSide) {
        stack.getOrCreateTag().remove("ElevatedSelection");stack.getOrCreateTag().remove("SideSelection");stack.getOrCreateTag().remove("SideMain");stack.getOrCreateTag().remove("SidePoint");stack.getOrCreateTag().remove("Start");stack.getOrCreateTag().remove(CurveControlNodes.KEY);
        CompoundTag request = kind("reset");

        RoadNetwork.open((ServerPlayer) player, request);
        player.displayClientMessage(Component.literal("已清除道路选择与预览"), true);
      }
    } else if (level.isClientSide) {
      CompoundTag request = kind("resume");

      openClient.accept(request);
    } else if (!stack.getOrCreateTag().contains("Start")) {
      var road = pick((ServerPlayer) player);
      if (road != null) {
        try{requireTool(road.record);openRoad((ServerPlayer) player, road.record, false);}
        catch(IllegalArgumentException e){player.displayClientMessage(Component.literal(e.getMessage()),false);}
      }
    }
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
  }

  private static void requireTool(RoadRecord road) {
    if (road.settings().style().ramp() &&
        !(road.settings().style().connectorRamp()&&LaneTopology.metadata(road).link()!=null))
      throw new IllegalArgumentException("此旧路段可用道路删除器移除");
  }

  static Settings continuation(Settings requested,Settings port){
    var options=port.options().lanePoints(com.sora.splineroads.core.LanePoints.Data.EMPTY)
        .attachments(com.sora.splineroads.core.RoadAttachments.Data.EMPTY).laneLines(List.of())
        .streetscape(port.options().streetscape().raisedSpans(List.of()).tunnels(List.of()).crossings(List.of()))
        .infrastructure(port.options().infrastructure().clearEdits()).lift(.5,0).outsets(0,0)
        .route(requested.options().routing()).sides(com.sora.splineroads.core.RoadProfile.Side.AUTO,com.sora.splineroads.core.RoadProfile.Side.AUTO);
    return new Settings(requested.mode(),port.style(),port.width(),port.thickness(),port.tension(),port.arcDegrees())
        .structure(port.structure()).options(options);
  }

  private static CompoundTag kind(String kind) {
    CompoundTag t = new CompoundTag();
    t.putString("Kind", kind);
    return t;
  }

  @Override
  public void appendHoverText(ItemStack s, Level l, List<Component> lines, TooltipFlag flag) {
    lines.add(
        Component.translatable(
            "tooltip.splineroads.connector"));
  }
}
