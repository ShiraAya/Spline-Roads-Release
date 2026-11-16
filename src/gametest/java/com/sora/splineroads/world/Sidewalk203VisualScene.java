package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Isolated native contacts, with one deliberately unsupported outside wool block. */
public final class Sidewalk203VisualScene {
 public record Scene(UUID road,double ground,List<BlockPos> blocks,BlockPos outside){}
 public static Scene create(ServerPlayer player){
  var level=player.serverLevel();level.getChunkAt(BlockPos.ZERO);
  level.setDayTime(6000);level.setWeatherParameters(6000,6000,false,false);
  double ground=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,0,0);
  var a=Curve201GameTests.marker(level,new Node(new V(.5,ground,.5),0,0));
  var b=Curve201GameTests.marker(level,new Node(new V(.5,ground,40.5),0,0));
  ((NodeEntity)level.getBlockEntity(a)).owner=player.getUUID();((NodeEntity)level.getBlockEntity(b)).owner=player.getUUID();
  var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);
  s=s.options(s.options().extras(false,false,false).streetscape(s.options().streetscape().lamps(false)).outerRail(RoadProfile.OuterRail.OFF).sidewalk(new RoadSidewalks.Config(true,RoadSidewalks.Side.BOTH,5,"minecraft:bricks")));
  var road=RoadData.get(level).connect(level,player,a,b,s,null);var blocks=new ArrayList<BlockPos>();int n=0;
  for(var material:List.of(Blocks.BLUE_WOOL,Blocks.STONE,Blocks.GLASS)){
   var sample=RoadStructures.sample(road.mesh(),12+n++*4);var top=sample.at(sample.halfWidth()+2.5,-.2);
   var pos=new BlockPos((int)Math.floor(top.x()),(int)Math.ceil(top.y()),(int)Math.floor(top.z()));
   if(!level.getBlockState(pos).isAir())throw new AssertionError("fixture placement blocked "+pos);
   level.setBlock(pos,material.defaultBlockState(),3);blocks.add(pos);
  }
  var outside=blocks.get(1).offset(-6,0,0);level.setBlock(outside,Blocks.RED_WOOL.defaultBlockState(),3);
  com.sora.splineroads.net.RoadNetwork.road(player,road);return new Scene(road.id(),ground,List.copyOf(blocks),outside);
 }
 public static UUID bridge(ServerPlayer player){
  var level=player.serverLevel();level.getChunkAt(new BlockPos(45,0,0));double ground=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,45,0);
  var a=Curve201GameTests.marker(level,new Node(new V(45.5,ground+12,.5),0,0));var b=Curve201GameTests.marker(level,new Node(new V(45.5,ground+12,96.5),0,0));
  ((NodeEntity)level.getBlockEntity(a)).owner=player.getUUID();((NodeEntity)level.getBlockEntity(b)).owner=player.getUUID();
  var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).structure(Structure.BRIDGE),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);
  s=s.options(s.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.OFF).streetscape(s.options().streetscape().lamps(false)).infrastructure(s.options().infrastructure().bridge(RoadInfrastructure.Bridge.TEMPLATE).supportsEnabled(false)));
  var road=RoadData.get(level).connect(level,player,a,b,s,null);
  if(road.structures().stream().anyMatch(RoadStructures.Part::pier))throw new AssertionError("disabled support bridge generated pier");
  com.sora.splineroads.net.RoadNetwork.road(player,road);return road.id();
 }
 private Sidewalk203VisualScene(){}
}
