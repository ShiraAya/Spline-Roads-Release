package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
public final class YJunctionTool extends Item {
  public YJunctionTool(){super(new Properties().stacksTo(1));}
  @Override public InteractionResult useOn(UseOnContext c){
    if(c.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(c.getPlayer() instanceof ServerPlayer p)try(var work=RoadWorkChunks.open(p.serverLevel())){
      var d=RoadData.get(p.serverLevel());BlockPos pos=RoadTool.endpointAtHit(c);var tag=c.getItemInHand().getOrCreateTag();
      if(p.isShiftKeyDown()){tag.remove("Points");p.displayClientMessage(Component.literal("Y 字选点已清空"),true);return InteractionResult.CONSUME;}
      if(p.serverLevel().getBlockEntity(pos) instanceof NodeEntity){
        RoadData.requireNode(p.serverLevel(),pos,p);String dim=p.level().dimension().location().toString();long[] old=dim.equals(tag.getString("Dimension"))?tag.getLongArray("Points"):new long[0];if(old.length>=3)old=new long[0];for(long k:old)if(k==pos.asLong())throw new IllegalArgumentException("请选择不同的端点");
        long[] next=Arrays.copyOf(old,old.length+1);next[old.length]=pos.asLong();tag.putLongArray("Points",next);tag.putString("Dimension",dim);
        if(next.length==3)RoadNetwork.open(p,payload(p.serverLevel(),p,next,null));else p.displayClientMessage(Component.literal("已选 "+(char)('A'+old.length)+"；接着选择 "+(char)('A'+next.length)+"（A 双向，B 出向，C 入向）"),true);
      }else{var r=RoadTool.pick(p);var saved=r==null?null:d.interchanges.get(r.record.assembly());if(saved==null||!saved.getBoolean("YJunction"))throw new IllegalArgumentException("依次选 A/B/C 端点，或右键已有 Y 字路口");RoadNetwork.open(p,payload(p.serverLevel(),p,saved.getLongArray("Points"),saved.getUUID("Id")));}
    }catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}return InteractionResult.CONSUME;
  }
  @Override public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand hand){if(p.isShiftKeyDown()){p.getItemInHand(hand).getOrCreateTag().remove("Points");if(!l.isClientSide)p.displayClientMessage(Component.literal("Y 字选点已清空"),true);}return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand),l.isClientSide);}
  static CompoundTag payload(ServerLevel l,ServerPlayer p,long[] points,UUID id){
    if(points.length!=3||Arrays.stream(points).distinct().count()!=3)throw new IllegalArgumentException("需要 A/B/C 三个不同端点");
    var d=RoadData.get(l);var t=id==null?new CompoundTag():Interchanges.descriptor(d,id);if(id!=null&&p!=null)RoadData.requireOwner(p,t.getUUID("Owner"));
    t.putString("Kind","yJunction");t.putBoolean("YJunction",true);t.putLongArray("Points",points);if(!t.contains("Tension"))t.putDouble("Tension",.4);
    var nodes=new ListTag();int signature=1;
    for(int i=0;i<3;i++){
      var pos=BlockPos.of(points[i]);var entity=RoadData.requireNode(l,pos,p);var node=entity.constructionNode();
      var hosts=d.index.atNode(pos).stream().map(d.index.roads::get).filter(r->id==null||!id.equals(r.record.assembly())).toList();
      if(hosts.size()>1)throw new IllegalArgumentException("所选端点已接多条道路，请选道路尽头");
      Settings settings=t.contains("Profile"+i)?RoadRecord.readSettings(t.getCompound("Profile"+i)):new Settings(Mode.CURVE,i==0?Style.O4_YELLOW:Style.O2_ONE,(i==0?Style.O4_YELLOW:Style.O2_ONE).defaultWidth(),1,.4,90);
      if(!hosts.isEmpty()){
        var host=hosts.get(0);if(p!=null)RoadData.requireOwner(p,host.record.owner());settings=RoadData.endpointSection(host,pos);
        if(host.record.assembly()==null)settings=settings.options(settings.options().sidewalk(host.record.settings().options().sidewalk()));
        var actual=host.record.caps(0).mesh();var end=host.record.a().equals(pos)?actual.first():actual.last();V outward=end.left().left().mul(host.record.a().equals(pos)?1:-1);
        V direction=i==1?outward.mul(-1):outward; // A points toward junction, B away, C toward junction.
        if(i==0)direction=outward;
        double grade=(host.record.a().equals(pos)?host.record.start().grade():host.record.end().grade())*(host.record.a().equals(pos)?-1:1)*(i==1?-1:1);
        V endpoint=end.center();
        node=new Node(endpoint,YJunctionPlanner.yaw(direction),grade);
        if(direction.left().dot(end.left())<0)settings=RoadEndpointSections.orient(settings,true);
        if(i>0&&((i==1&&!host.record.a().equals(pos))||(i==2&&!host.record.b().equals(pos))))throw new IllegalArgumentException((i==1?"B":"C")+" 单行道方向不符：B 应从端点驶出，C 应驶入端点");
        signature=31*signature+GantryTool.signature(host.record);
      }else {
        if(i>0&&!t.contains("Profile"+i)){var main=RoadRecord.readSettings(t.getCompound("Profile0"));var cp=RoadProfile.catalog(main);var count=new RoadLanes.Counts(i==1?RoadLanes.counts(main).forward():RoadLanes.counts(main).reverse(),0);var style=RoadLanes.carrier(cp.type(),count,Median.NONE);var o=main.options().lanes(count).ends(RoadTransitions.Ends.NONE).laneLines(List.of());settings=new Settings(Mode.CURVE,style,RoadProfile.width(style,o,RoadProfile.layout(main,main.width()).laneWidth()),main.thickness(),.4,90).structure(main.structure()).options(o);}
        if(!entity.headingLocked){V av=RoadData.requireNode(l,BlockPos.of(points[0]),p).constructionNode().position();V bv=RoadData.requireNode(l,BlockPos.of(points[1]),p).constructionNode().position();V cv=RoadData.requireNode(l,BlockPos.of(points[2]),p).constructionNode().position();V direction=i==0?bv.add(cv).mul(.5).sub(av):i==1?bv.sub(av):av.sub(cv);node=new Node(node.position(),YJunctionPlanner.yaw(direction),0);}
      }
      t.putBoolean("LockedProfile"+i,!hosts.isEmpty());nodes.add(RoadRecord.writeNode(node));t.put("Profile"+i,RoadRecord.writeSettings(settings));signature=31*signature+node.hashCode();
    }
    t.put("Nodes",nodes);t.putInt("Signature",signature);return t;
  }
  public static String build(ServerLevel l,ServerPlayer p,CompoundTag command,ItemStack tool){
    if(!(tool.getItem() instanceof YJunctionTool)&&!(command.hasUUID("Id")&&tool.getItem() instanceof JunctionTool))throw new IllegalArgumentException("请手持 Y 字路口编辑器");
    var d=RoadData.get(l);UUID id=command.hasUUID("Id")?command.getUUID("Id"):null;long[] points=command.getLongArray("Points");
    if(id==null&&(!Arrays.equals(points,tool.getOrCreateTag().getLongArray("Points"))||!l.dimension().location().toString().equals(tool.getTag().getString("Dimension"))))throw new IllegalArgumentException("选点已失效，请重新选择 A/B/C");
    if(id!=null){var saved=Interchanges.descriptor(d,id);if(!saved.getBoolean("YJunction")||!Arrays.equals(points,saved.getLongArray("Points")))throw new IllegalArgumentException("Y 字路口已改变");}
    if(command.getBoolean("Delete")){if(id==null)throw new IllegalArgumentException("尚未建造");Interchanges.remove(l,p,id);return "Y 字路口已删除";}
    try(var work=RoadWorkChunks.open(l)){
      var t=payload(l,p,points,id);if(t.getInt("Signature")!=command.getInt("Signature"))throw new IllegalArgumentException("端点或相接道路已改变，请重新选择");
      for(int i=0;i<3;i++)if(!t.getBoolean("LockedProfile"+i)&&command.contains("Profile"+i)){var requested=RoadRecord.readSettings(command.getCompound("Profile"+i));requested.validate();t.put("Profile"+i,RoadRecord.writeSettings(requested));}
      double tension=command.getDouble("Tension");t.putDouble("Tension",tension);var n=t.getList("Nodes",Tag.TAG_COMPOUND);var plan=YJunctionPlanner.plan(RoadRecord.readNode(n.getCompound(0)),RoadRecord.readNode(n.getCompound(1)),RoadRecord.readNode(n.getCompound(2)),RoadRecord.readSettings(t.getCompound("Profile0")),RoadRecord.readSettings(t.getCompound("Profile1")),RoadRecord.readSettings(t.getCompound("Profile2")),tension);
      UUID group=id==null?UUID.randomUUID():id,owner=id==null?(p==null?new UUID(0,0):p.getUUID()):t.getUUID("Owner");t.putUUID("Id",group);t.putUUID("Owner",owner);
      BlockPos a=BlockPos.of(points[0]),b=BlockPos.of(points[1]),c=BlockPos.of(points[2]),throat=BlockPos.containing(plan.throat().position().x(),plan.throat().position().y(),plan.throat().position().z());
      var built=new ArrayList<RoadIndex.Built>();var meshes=List.of(plan.stem(),plan.outbound(),plan.inbound());var from=List.of(a,throat,c);var to=List.of(throat,b,throat);
      for(int i=0;i<3;i++){var m=meshes.get(i);var first=m.first();var last=m.last();var r=new RoadRecord(UUID.nameUUIDFromBytes((group+":"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),owner,from.get(i),to.get(i),new Node(first.center(),YJunctionPlanner.yaw(first.left().left().mul(-1)),i==2?RoadRecord.readNode(n.getCompound(2)).grade():plan.throat().grade()),new Node(last.center(),YJunctionPlanner.yaw(last.left().left().mul(-1)),i==1?RoadRecord.readNode(n.getCompound(1)).grade():i==2?-plan.throat().grade():plan.throat().grade()),m.settings(),true,4).alignment(group,m);built.add(new RoadIndex.Built(r));}
      Set<UUID> removed=new HashSet<>();if(id!=null)for(var r:d.index.roads.values())if(id.equals(r.record.assembly()))removed.add(r.record.id());
      if(l.getBlockEntity(throat) instanceof NodeEntity&&!Set.of(a,b,c).contains(throat))throw new IllegalArgumentException("Y 字分流处有其他端点，请调整选点");
      // These three hosts lose only the free-end cap at the port being joined.
      // Check against their final decks, including any untouched far-end cap.
      var ports=Set.of(a,b,c);
      for(var old:d.index.roads.values()){
        if(removed.contains(old.record.id()))continue;
        int mask=old.record.endCaps();
        if(ports.contains(old.record.a()))mask&=~1;
        if(ports.contains(old.record.b()))mask&=~2;
        var deck=mask==old.record.endCaps()?old.mesh:old.record.caps(mask).mesh();
        for(var proposed:built)if(RoadIndex.overlapXZ(proposed.mesh,deck,1))Interchanges.checkExternal(proposed.mesh,deck);
      }
      d.replaceAssembly(l,p,built,removed,Set.of(a,b,c));d.interchanges.put(group,t);d.setDirty();tool.getOrCreateTag().remove("Points");return "Y 字路口已更新：A→B、C→A";
    }
  }
}
