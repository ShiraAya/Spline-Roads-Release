package com.sora.splineroads.world;

import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class InterchangeTool extends Item {
  private final int arms;
  private final com.sora.splineroads.core.CorridorPlanner.Kind corridor;

  public InterchangeTool() {
    this(4);
  }

  public InterchangeTool(int arms) {
    this(arms,null);
  }
  public InterchangeTool(int arms,com.sora.splineroads.core.CorridorPlanner.Kind corridor) {
    super(new Properties().stacksTo(1));
    if (arms < 2 || arms > 6 || arms==2&&corridor==null) throw new IllegalArgumentException("invalid generator arms");
    this.arms = arms;
    this.corridor = corridor;
  }

  public int arms() {
    return arms;
  }

  public boolean available() {
    return arms <= 6;
  }

  private String selection() {
    if(corridor!=null)return arms==2?"A/B 主路两端（自动生成同向辅路）":"A/B 第一层两端＋C/D 第二层两端（平行双层路）";
    return arms == 3 ? "A/B 主路两端＋C 支路端点" : arms == 4 ? "A/B 主路两端＋C/D 另一条主路两端" : arms == 5 ? "A/B、C/D 两条贯通主路＋E 支路端点" : "A/B、C/D、E/F 三条贯通主路两端";
  }

  private CompoundTag tagged(CompoundTag payload) {
    payload.putInt("GeneratorArms", arms);
    if(corridor!=null){
      payload.putString("GeneratorMode",corridor.name());
      payload.putString("Kind",payload.getString("Kind").replace("interchange","corridor"));
      if(payload.contains("Nodes"))Corridors.initialize(payload,corridor);
    }
    return payload;
  }

  public void requireDescriptor(CompoundTag t){
    if(t==null)throw new IllegalArgumentException("道路组合已不存在");
    requireLayout(t.getLongArray("Points"));
    String mode=t.contains("Corridor")?t.getCompound("Corridor").getString("Mode"):"";
    if(!mode.equals(corridor==null?"":corridor.name()))
      throw new IllegalArgumentException("请使用与当前道路组合对应的生成器");
  }

  public void requireLayout(long[] points) {
    if (!available()) throw new IllegalArgumentException(arms + " 向立交生成器暂未开放建造");
    if (points.length != arms)
      throw new IllegalArgumentException(corridor!=null?"请使用与此组合对应的主辅路或双层出入口工具":"请使用 " + points.length + " 向立交生成器编辑此立交");
  }

  private boolean placeholder(Player player) {
    if (available()) return false;
    if (!player.level().isClientSide)
      player.displayClientMessage(Component.literal(arms + " 向立交生成器：预留物品，暂未开放建造"), true);
    return true;
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
    if (placeholder(player)) return InteractionResult.CONSUME;
    try (var workChunks = RoadWorkChunks.open(player.serverLevel())) {
      var level = player.serverLevel();
      var tool = context.getItemInHand().getOrCreateTag();
      var hitRoad=RoadTool.pick(player);var hit=context.getClickLocation();
      var attached=hitRoad==null?null:AttachedPointTool.nearest(hitRoad,new com.sora.splineroads.core.RoadGeometry.V(hit.x,hit.y,hit.z));
      if(attached!=null){
        if(hitRoad.record.assembly()!=null)throw new IllegalArgumentException("立交主路附属点仅用于标线分段，不能用来选择整路");
        RoadData.requireOwner(player,hitRoad.record.owner());String dimension=level.dimension().location().toString();
        long[] selected=dimension.equals(tool.getString("Dimension"))?tool.getLongArray("Points"):new long[0];if(selected.length>=arms)selected=new long[0];
        if(selected.length%2!=0||selected.length+2>arms)throw new IllegalArgumentException("附属点一次选择完整道路；请先完成或清除当前单端点选择");
        long a=hitRoad.record.a().asLong(),b=hitRoad.record.b().asLong();for(long old:selected)if(old==a||old==b)throw new IllegalArgumentException("该道路与已选端点重复");
        long[] next=Arrays.copyOf(selected,selected.length+2);next[selected.length]=a;next[selected.length+1]=b;tool.putLongArray("Points",next);tool.putString("Dimension",dimension);
        if(next.length==arms)RoadNetwork.open(player,tagged(Interchanges.payload(level,player,next,null)));else player.displayClientMessage(Component.literal("已通过附属点选中完整道路；继续选择另一条道路或支路端点"),true);
        return InteractionResult.CONSUME;
      }
      BlockPos p = RoadTool.endpointAtHit(context);
      if (level.getBlockEntity(p) instanceof NodeEntity) {
        RoadData.requireNode(level, p, player);
        String dimension = level.dimension().location().toString();
        long[] selected =
            dimension.equals(tool.getString("Dimension"))
                ? tool.getLongArray("Points")
                : new long[0];
        if (selected.length >= arms) selected = new long[0];
        for (long point : selected)
          if (point == p.asLong()) throw new IllegalArgumentException("请选择另一个端点");
        long[] next = Arrays.copyOf(selected, selected.length + 1);
        next[selected.length] = p.asLong();
        tool.putLongArray("Points", next);
        tool.putString("Dimension", dimension);
        if (next.length == arms)
          RoadNetwork.open(player, tagged(Interchanges.payload(level, player, next, null)));
        else
          player.displayClientMessage(
              Component.literal(
                  "已选 "
                      + (char) ('A' + selected.length)
                      + "；接着选择 "
                      + (char) ('A' + next.length)
                      + "（"
                      + selection()
                      + "）"),
              true);
      } else edit(player);
    } catch (IllegalArgumentException e) {
      player.displayClientMessage(Component.literal(e.getMessage()), false);
    }
    return InteractionResult.CONSUME;
  }

  private void edit(ServerPlayer player) {
    var hit = RoadTool.pick(player);
    if (hit == null || hit.record.assembly() == null)
      throw new IllegalArgumentException("选择 " + selection() + "；或右键已有立交");
    var data=RoadData.get(player.serverLevel());
    UUID group=InterchangeJunctions.parent(data,hit.record.assembly());
    var saved = data.interchanges.get(group);
    if (saved == null) throw new IllegalArgumentException("立交描述已缺失，请删除残留路段后重建");
    requireDescriptor(saved);
    RoadNetwork.open(
        player,
        tagged(
            Interchanges.payload(
                player.serverLevel(),
                player,
                saved.getLongArray("Points"),
                group)));
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player who, InteractionHand hand) {
    ItemStack stack = who.getItemInHand(hand);
    if (placeholder(who)) return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    if (who.isShiftKeyDown()) {
      if (!level.isClientSide) {
        stack.getOrCreateTag().remove("Points");
        CompoundTag t = new CompoundTag();
        t.putString("Kind", "interchangeReset");
        RoadNetwork.open((ServerPlayer) who, tagged(t));
      }
    } else if (level.isClientSide) {
      CompoundTag t = new CompoundTag();
      t.putString("Kind", "interchangeResume");
      RoadTool.openClient.accept(tagged(t));
    } else
      try {
        var player = (ServerPlayer) who;
        var hit = RoadTool.pick(player);
        if (hit != null && hit.record.assembly() != null) edit(player);
        else {
          long[] points = stack.getOrCreateTag().getLongArray("Points");
          if (points.length == arms
              && level
                  .dimension()
                  .location()
                  .toString()
                  .equals(stack.getTag().getString("Dimension")))
            RoadNetwork.open(
                player, tagged(Interchanges.payload(player.serverLevel(), player, points, null)));
        }
      } catch (IllegalArgumentException e) {
        who.displayClientMessage(Component.literal(e.getMessage()), false);
      }
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
  }

  @Override
  public void appendHoverText(ItemStack s, Level l, List<Component> lines, TooltipFlag f) {
    if (!available()) {
      lines.add(Component.literal("预留物品 · 暂未开放建造"));
      lines.add(Component.literal(arms == 5 ? "规划：A/B＋C/D＋E 端点" : "规划：A/B＋C/D＋E/F"));
      return;
    }
    lines.add(Component.literal("选择 " + selection() + " 后打开设置"));
    lines.add(Component.literal("Shift＋右键空气重新选点；右键已有立交可整体编辑"));
  }
}
