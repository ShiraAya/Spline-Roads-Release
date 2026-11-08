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

public final class TollTool extends Item {
  private record Preview(long request,UUID token) {}
  private static final Map<ServerPlayer,Preview> PREVIEWS=new WeakHashMap<>();
  public TollTool(){super(new Properties().stacksTo(1));}
  public static void rememberPreview(ServerPlayer player,ItemStack stack,CompoundTag command,CompoundTag inputs){
    var saved=new CompoundTag();saved.putLong("Request",command.getLong("Request"));saved.putUUID("Token",inputs.getUUID("Token"));
    stack.getOrCreateTag().put("TollPreview",saved);PREVIEWS.put(player,new Preview(saved.getLong("Request"),saved.getUUID("Token")));
  }
  public static void cancelPreview(ServerPlayer player,CompoundTag command){
    var saved=PREVIEWS.get(player);
    if(saved==null||!command.contains("Request")||saved.request()!=command.getLong("Request")
        ||command.hasUUID("Token")&&!saved.token().equals(command.getUUID("Token")))return;
    RoadData.forgetPreview(player.getUUID(),saved.token());PREVIEWS.remove(player);
  }
  public static void clearPreviews(){PREVIEWS.clear();}
  private static void clear(ServerPlayer player,ItemStack stack){
    var state=stack.getOrCreateTag();var previous=state.getCompound("TollPreview");
    if(previous.hasUUID("Token"))RoadData.forgetPreview(player.getUUID(),previous.getUUID("Token"));
    cancelPreview(player,previous);state.remove("Points");state.remove("InlineSelection");state.remove("TollPreview");
    var t=new CompoundTag();t.putString("Kind","tollReset");t.putLong("Request",previous.getLong("Request"));RoadNetwork.open(player,t);
  }
  static void inlineSelection(ItemStack stack,CompoundTag payload){var tag=stack.getOrCreateTag();tag.remove("Points");tag.putString("Dimension",payload.getString("Dimension"));tag.put("InlineSelection",payload.copy());}
  private static void edit(ServerPlayer p,RoadRecord r){RoadNetwork.open(p,TollRoads.payload(p.serverLevel(),p,new long[0],r.id()));}
  private static void clearClient(){var reset=new CompoundTag();reset.putString("Kind","tollReset");RoadTool.openClient.accept(reset);}
  @Override public InteractionResult useOn(UseOnContext context){
    if(context.getLevel().isClientSide){if(context.getPlayer()!=null&&context.getPlayer().isShiftKeyDown())clearClient();return InteractionResult.SUCCESS;}
    if(!(context.getPlayer() instanceof ServerPlayer p))return InteractionResult.PASS;
    if(p.isShiftKeyDown()){clear(p,context.getItemInHand());return InteractionResult.CONSUME;}
    try(var chunks=RoadWorkChunks.open(p.serverLevel())){
      var level=p.serverLevel();var at=RoadTool.endpointAtHit(context);var data=RoadData.get(level);
      var station=TollRoads.at(data,at);if(station!=null){edit(p,station);return InteractionResult.CONSUME;}
      var hit=RoadTool.pick(p);if(!(level.getBlockEntity(at) instanceof NodeEntity)){if(hit!=null&&hit.record.toll()!=null){edit(p,hit.record);return InteractionResult.CONSUME;}if(hit!=null){var location=context.getClickLocation();var t=InlineTollRoads.payload(level,p,hit.record.id(),RoadSplitGeometry.station(hit.mesh,new com.sora.splineroads.core.RoadGeometry.V(location.x,location.y,location.z)));inlineSelection(context.getItemInHand(),t);RoadNetwork.open(p,t);return InteractionResult.CONSUME;}throw new IllegalArgumentException("收费站生成器：右键道路断面，或依次选择端点 A、B");}
      RoadData.requireNode(level,at,p);var tag=context.getItemInHand().getOrCreateTag();String dimension=level.dimension().location().toString();
      long[] selected=dimension.equals(tag.getString("Dimension"))?tag.getLongArray("Points"):new long[0];if(selected.length>=2)selected=new long[0];
      if(selected.length==1&&selected[0]==at.asLong())throw new IllegalArgumentException("请选择另一个端点 B");
      long[] next=Arrays.copyOf(selected,selected.length+1);next[selected.length]=at.asLong();tag.putLongArray("Points",next);tag.putString("Dimension",dimension);tag.remove("InlineSelection");
      if(next.length==2)RoadNetwork.open(p,TollRoads.payload(level,p,next,null));else p.displayClientMessage(Component.literal("已选 A；请选择站区另一端 B"),true);
    }catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}return InteractionResult.CONSUME;
  }
  @Override public InteractionResultHolder<ItemStack> use(Level level,Player who,InteractionHand hand){
    var stack=who.getItemInHand(hand);if(level.isClientSide&&who.isShiftKeyDown())clearClient();if(!level.isClientSide&&who instanceof ServerPlayer p){
      if(p.isShiftKeyDown())clear(p,stack);else try{
        var hit=RoadTool.pick(p);if(hit!=null&&hit.record.toll()!=null)edit(p,hit.record);
        else {var t=stack.getOrCreateTag();if(t.getLongArray("Points").length==2&&level.dimension().location().toString().equals(t.getString("Dimension")))RoadNetwork.open(p,TollRoads.payload(p.serverLevel(),p,t.getLongArray("Points"),null));else {var reply=new CompoundTag();reply.putString("Kind","tollResume");RoadNetwork.open(p,reply);}}
      }catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}
    }return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
  }
  public static void selection(ServerPlayer p,ItemStack tool,CompoundTag t){
    if(t.hasUUID("InlineRoad")){var selection=tool.getOrCreateTag().getCompound("InlineSelection");if(!selection.hasUUID("InlineRoad")||!selection.getUUID("InlineRoad").equals(t.getUUID("InlineRoad"))||selection.getDouble("InlineStation")!=t.getDouble("InlineStation")||selection.getInt("InlineSignature")!=t.getInt("InlineSignature"))throw new IllegalArgumentException("收费站断面选择已失效，请重新点击道路");return;}
    if(t.hasUUID("Id"))return;var tag=tool.getOrCreateTag();
    if(!Arrays.equals(t.getLongArray("Points"),tag.getLongArray("Points"))||!p.level().dimension().location().toString().equals(tag.getString("Dimension")))throw new IllegalArgumentException("收费站选择已失效，请重新选 A/B");
  }
  @Override public void appendHoverText(ItemStack s,Level l,List<Component> lines,TooltipFlag f){lines.add(Component.literal("右键道路断面原位生成；也可选择端点 A、B 独立生成"));lines.add(Component.literal("Shift＋右键清除临时选择；右键收费站编辑"));}
}
