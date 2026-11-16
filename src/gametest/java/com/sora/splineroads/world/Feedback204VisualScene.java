package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Invoked only in the fresh world owned by Feedback204Visual. Never saves the source NBT. */
public final class Feedback204VisualScene {
  public static final UUID SAVED_ID=UUID.fromString("a358e737-b0df-416c-9d08-e86d4805bb1c");
  public record BridgeCase(String name,UUID id,RoadInfrastructure.Bridge kind,
      boolean supports,boolean auto,double x,double z,double ground,double deck,double length) {}

  private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  private static BlockPos marker(ServerPlayer player,Node value){
    var level=player.serverLevel();var p=BlockPos.containing(value.position().x(),value.position().y(),value.position().z());
    level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);
    var marker=(NodeEntity)level.getBlockEntity(p);marker.owner=player.getUUID();
    marker.heightExplicit=true;marker.headingLocked=true;marker.gradeLocked=true;marker.apply(value);return p;
  }
  public static CompoundTag snapshot(ServerPlayer player,Path source)throws Exception{
    var level=player.serverLevel();level.setDayTime(6000);level.setWeatherParameters(6000,6000,false,false);
    var root=NbtIo.readCompressed(source.toFile()).getCompound("data");
    var saved=root.getList("Interchanges",Tag.TAG_COMPOUND).getCompound(0);
    require(saved.hasUUID("Id")&&saved.getUUID("Id").equals(SAVED_ID),"wrong snapshot object 0");
    var nodes=saved.getList("Nodes",Tag.TAG_COMPOUND);require(nodes.size()==4,"snapshot does not have four nodes");
    var a=RoadRecord.readNode(nodes.getCompound(0));var b=RoadRecord.readNode(nodes.getCompound(1));
    var c=RoadRecord.readNode(nodes.getCompound(2));var d=RoadRecord.readNode(nodes.getCompound(3));
    require(Math.abs(a.position().distance(b.position())-600)<1e-5,"source AB is not 600 blocks");
    require(Math.abs(c.position().distance(d.position())-600)<1e-5,"source CD is not 600 blocks");
    require(Math.abs(Math.abs(a.position().y()-c.position().y())-7)<1e-5,"source height difference is not 7");
    require(saved.getCompound("Options").getBoolean("AdjustEndpoints"),"source adjustEndpoints is false");
    level.getDataStorage().set("splineroads",RoadData.load(root));
    var points=saved.getLongArray("Points");require(points.length==4,"wrong snapshot points");
    // Keep the authored marker block coordinates, which need not equal floor(node.position).
    for(int i=0;i<points.length;i++){
      var pos=BlockPos.of(points[i]);level.getChunkAt(pos);level.setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),2);
      var node=(NodeEntity)level.getBlockEntity(pos);node.owner=player.getUUID();node.heightExplicit=true;
      node.headingLocked=true;node.gradeLocked=true;node.apply(RoadRecord.readNode(nodes.getCompound(i)));
    }
    return Interchanges.payload(level,null,points,SAVED_ID);
  }

  public static List<BridgeCase> bridges(ServerPlayer player){
    var out=new ArrayList<BridgeCase>();
    out.add(bridge(player,0,"template-auto",RoadInfrastructure.Bridge.TEMPLATE,true,true));
    out.add(bridge(player,1,"template-manual",RoadInfrastructure.Bridge.TEMPLATE,true,false));
    out.add(bridge(player,2,"template-off",RoadInfrastructure.Bridge.TEMPLATE,false,true));
    out.add(bridge(player,3,"cable-on-control",RoadInfrastructure.Bridge.CABLE,true,true));
    out.add(bridge(player,4,"cable-off",RoadInfrastructure.Bridge.CABLE,false,true));
    out.add(bridge(player,5,"suspension-on-control",RoadInfrastructure.Bridge.SUSPENSION,true,true));
    out.add(bridge(player,6,"suspension-off",RoadInfrastructure.Bridge.SUSPENSION,false,true));
    return List.copyOf(out);
  }
  private static BridgeCase bridge(ServerPlayer player,int index,String name,RoadInfrastructure.Bridge kind,boolean supports,boolean auto){
    var level=player.serverLevel();double x=2000.5+index*80,z=2000.5,length=120;
    level.getChunkAt(BlockPos.containing(x,0,z));
    double ground=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)x,(int)z),deck=ground+18;
    var a=marker(player,new Node(new V(x,deck,z),0,0));var b=marker(player,new Node(new V(x,deck,z+length),0,0));
    // A plain yellow median, no rails, lamps, sidewalks or edge fitting: upper geometry is bridge-only.
    var settings=new Settings(Mode.STRAIGHT,Style.O2_YELLOW,22,1,.35,90).structure(Structure.BRIDGE);
    var o=settings.options();settings=settings.options(o.extras(false,false,false)
        .outerRail(RoadProfile.OuterRail.OFF).sidewalk(o.sidewalk().enabled(false))
        .streetscape(o.streetscape().lamps(false).walkLamps(false))
        .route(o.routing().fit(false))
        .infrastructure(o.infrastructure().bridge(kind).span(24).autoSpan(auto).supportsEnabled(supports)
            .gantry(RoadInfrastructure.Gantry.OFF).medianConversion(RoadInfrastructure.MedianConversion.OFF)));
    var road=RoadData.get(level).connect(level,player,a,b,settings,null);
    require(road.settings().options().infrastructure().bridge()==kind,"server altered bridge kind "+name);
    require(road.settings().options().infrastructure().supportsEnabled()==supports,"server altered support state "+name);
    RoadNetwork.road(player,road);
    return new BridgeCase(name,road.id(),kind,supports,auto,x,z,ground,deck,length);
  }
  private Feedback204VisualScene(){}
}
