package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Real donor roads and authoritative readback for the native UI sequence. No direct interchange build. */
public final class Interchange204SequenceScene {
  public static final double X=840000.5,Z=840000.5;
  public record State(UUID group,CompoundTag saved,CompoundTag reopen,List<RoadRecord> members,Set<UUID> allIds) {}
  private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
  private static BlockPos marker(ServerPlayer player,Node value){
    var level=player.serverLevel();var p=BlockPos.containing(value.position().x(),value.position().y(),value.position().z());
    level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);
    var marker=(NodeEntity)level.getBlockEntity(p);marker.owner=player.getUUID();marker.heightExplicit=true;
    marker.headingLocked=true;marker.gradeLocked=true;marker.apply(value);return p;
  }
  public static CompoundTag create(ServerPlayer player){
    var level=player.serverLevel();player.setGameMode(GameType.CREATIVE);level.setDayTime(6000);level.setWeatherParameters(6000,6000,false,false);
    level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,level.getServer());
    level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false,level.getServer());
    var a=marker(player,new Node(new V(X,-40,Z-400),0,0));var b=marker(player,new Node(new V(X,-40,Z+400),0,0));
    var c=marker(player,new Node(new V(X-400,-59,Z),-90,0));var d=marker(player,new Node(new V(X+400,-59,Z),-90,0));
    var o=RoadProfile.Options.DEFAULT.extras(false,false,false).cycleFinish(RoadProfile.Options.CycleFinish.NONE).route(RoadProfile.Routing.DEFAULT.fit(false));
    o=o.infrastructure(o.infrastructure().gantry(RoadInfrastructure.Gantry.OFF));
    var highway=new Settings(Mode.STRAIGHT,Style.H4_RAIL,28,1,.4,90).options(o);
    var ordinary=new Settings(Mode.STRAIGHT,Style.O4_RAIL,Style.O4_RAIL.defaultWidth(),1,.4,90).options(o);
    var data=RoadData.get(level);require(data.interchanges.isEmpty(),"fixture world already has an interchange");
    // Build the low road first, so the high road's actual support search sees its traffic space.
    data.connect(level,player,c,d,ordinary,null);data.connect(level,player,a,b,highway,null);
    var points=new long[]{a.asLong(),b.asLong(),c.asLong(),d.asLong()};
    var item=new ItemStack(SplineRoads.INTERCHANGE.get());item.getOrCreateTag().putLongArray("Points",points);
    item.getOrCreateTag().putString("Dimension",level.dimension().location().toString());
    player.setItemInHand(InteractionHand.MAIN_HAND,item);player.containerMenu.broadcastChanges();
    var payload=Interchanges.payload(level,player,points,null);payload.putInt("GeneratorArms",4);
    payload.put("Options",Interchanges.write(new Options(Preset.CLOVERLEAF,false,1,96,20,5,1,5,true,false,0,1,5,true,false,0)));
    require(ContinuousRoads.sources(payload).size()==2,"native fixture did not capture two real donors");
    require(RoadRecord.readSettings(payload.getCompound("Main1")).style()==Style.H4_RAIL,"AB is not the highway");
    require(RoadRecord.readSettings(payload.getCompound("Main2")).style()==Style.O4_RAIL,"CD is not the ordinary road");
    return payload;
  }
  public static State readCompleted(ServerPlayer player,UUID priorGroup,Preset expected,ListTag fitted){
    var level=player.serverLevel();var data=RoadData.get(level);require(data.interchanges.size()==1,"expected exactly one built interchange");
    var group=data.interchanges.keySet().iterator().next();require(priorGroup==null||priorGroup.equals(group),"update replaced assembly identity");
    var saved=data.interchanges.get(group).copy();require(saved.getUUID("Owner").equals(player.getUUID()),"wrong saved owner");
    require(Interchanges.read(saved.getCompound("Options")).preset()==expected,"server did not save expected preset "+expected);
    require(saved.getList("Nodes",Tag.TAG_COMPOUND).equals(fitted),"server endpoints differ from the actual UI preview "+expected);
    require(ContinuousRoads.sources(saved).stream().map(ContinuousRoads.Source::axis)
        .collect(java.util.stream.Collectors.toSet()).equals(Set.of(0,1)),"lost an authored donor axis "+expected);
    require(!saved.getBoolean("Partial"),"saved assembly is partial "+expected);
    var nodes=saved.getList("Nodes",Tag.TAG_COMPOUND);var points=saved.getLongArray("Points");
    require(points.length==4&&nodes.size()==4,"saved endpoint shape changed "+expected);
    for(int i=0;i<points.length;i++){
      var pos=BlockPos.of(points[i]);level.getChunkAt(pos);require(level.getBlockEntity(pos) instanceof NodeEntity,"missing moved marker "+expected+" "+i);
      var marker=(NodeEntity)level.getBlockEntity(pos);require(marker.owner.equals(player.getUUID()),"wrong marker owner "+expected+" "+i);
      require(marker.constructionNode().equals(RoadRecord.readNode(nodes.getCompound(i))),"actual marker differs from saved fitted endpoint "+expected+" "+i);
    }
    var members=data.index.roads.values().stream().map(v->v.record).filter(r->InterchangeJunctions.belongs(data,group,r.assembly())).toList();
    require(!members.isEmpty()&&members.stream().anyMatch(r->r.settings().style().ramp()),"no actual built ramp roads "+expected);
    require(members.stream().allMatch(r->r.owner().equals(player.getUUID())),"foreign road in built group "+expected);
    var reopen=Interchanges.payload(level,player,points,group);reopen.putInt("GeneratorArms",4);
    require(Arrays.equals(reopen.getLongArray("Points"),points),"reopen used stale points "+expected);
    require(RoadWorkChunks.heldCount(level)==0,"transaction left held chunks "+expected);
    return new State(group,saved,reopen,List.copyOf(members),Set.copyOf(data.index.roads.keySet()));
  }
  private Interchange204SequenceScene(){}
  public static State review(ServerPlayer player){
    var data=RoadData.get(player.serverLevel());require(data.interchanges.size()==1,"review expects the one completed assembly");
    var group=data.interchanges.keySet().iterator().next();var saved=data.interchanges.get(group).copy();
    require(Interchanges.read(saved.getCompound("Options")).preset()==Preset.DOUBLE_TRUMPET,"review world is not final double trumpet");
    var members=data.index.roads.values().stream().map(v->v.record).filter(r->InterchangeJunctions.belongs(data,group,r.assembly())).toList();
    require(!members.isEmpty(),"review lost actual saved members");
    return new State(group,saved,saved,List.copyOf(members),Set.copyOf(data.index.roads.keySet()));
  }
}
