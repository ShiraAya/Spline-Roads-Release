package com.sora.splineroads.world;

import com.mojang.authlib.GameProfile;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

@GameTestHolder("splineroads_204_holes") @PrefixGameTestTemplate(false)
public final class Holes204GameTests {
  static final int Y=-40;
  enum Shape { SINGLE_PIT, SIDE_2X2, HALF, FULL, TRANSVERSE_2M, SIDE_CHAIN }
  record Result(UUID id,Shape shape,boolean railway){}
  static Settings street(){
    var s=new Settings(Mode.STRAIGHT,Style.O1_ONE,5,1,.35,90).structure(Structure.AUTO);
    return s.options(s.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.AUTO).route(s.options().routing().fit(false)).infrastructure(s.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF)));
  }
  static boolean emptyCap(int dx,int z,Shape shape){return switch(shape){
    case SINGLE_PIT -> dx==-2&&z==12;
    case SIDE_2X2 -> dx>=-3&&dx< -1&&z>=12&&z<14;
    case HALF -> dx>=1;
    case FULL -> true;
    case TRANSVERSE_2M -> z>=12&&z<14;
    case SIDE_CHAIN -> dx>=-3&&dx< -1&&Arrays.stream(new int[]{12,20,28,36,44,52}).anyMatch(start->z>=start&&z<start+2);
  };}
  static void terrain(GameTestHelper h,int x,Shape shape){
    var level=h.getLevel();var data=RoadData.get(level);
    for(var road:data.index.roads.values()){
      var min=road.mesh.min();var max=road.mesh.max();
      h.assertTrue(max.x()<x-24||min.x()>x+24||max.z()< -10||min.z()>74,"hole fixture collides with an existing road "+road.record.id());
    }
    // Separate known foundation and ground cap: opening the cap reveals a real
    // eight-block-deep void without depending on the world's superflat thickness.
    // This is prepared terrain, not a fake Ground implementation in the world test.
    for(int dx=-18;dx<=18;dx++)for(int z=-4;z<=68;z++){
      var floor=new BlockPos(x+dx,Y-9,z);level.getChunkAt(floor);
      level.setBlock(floor,Blocks.STONE.defaultBlockState(),2);
      for(int y=Y-8;y<=Y+2;y++){
        var p=new BlockPos(x+dx,y,z);
        var state=y==Y-1&&!emptyCap(dx,z,shape)?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();
        if(!level.getBlockState(p).equals(state))level.setBlock(p,state,2);
      }
    }
  }
  static BlockPos marker(ServerLevel level,ServerPlayer player,V point){
    var p=BlockPos.containing(point.x(),point.y(),point.z());level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);
    var node=(NodeEntity)level.getBlockEntity(p);node.owner=player.getUUID();node.heightExplicit=true;node.apply(new Node(point,0,0));return p;
  }
  static List<RailbedPath.Path> track(int x){
    var points=new ArrayList<RailbedPath.Point>();for(int d=0;d<=64;d++)points.add(new RailbedPath.Point(d,new V(x+.5,Y,d+.5)));
    var segment=new RailbedPath.Segment("holes204-"+x,new RailbedPath.Node(x,Y,0),new RailbedPath.Node(x,Y,64),false,64,List.of("default"),points);
    return List.of(new RailbedPath.Path(List.of(segment)));
  }
  static RoadRecord buildRailway(GameTestHelper h,ServerPlayer player,int x){
    UUID id=UUID.randomUUID();var paths=track(x);
    // Use the production railway facade and its real prepare/build transaction.
    // Only MTR's already-detached path snapshot is supplied directly; this test
    // does not claim to exercise the optional MTR adapter or change live tracks.
    var future=RailbedBuildQueue.submit(h.getLevel(),player,id,paths,6,Structure.AUTO,paths,RailbedOptions.DEFAULT);
    for(int ticks=0;!future.isDone()&&ticks<16;ticks++)RailbedRoads.tick();
    h.assertTrue(future.isDone(),"railway transaction did not drain from its production queue");var entry=future.join();
    var data=RoadData.get(h.getLevel());h.assertTrue(data.railbeds.entries.containsKey(id)&&data.index.roads.containsKey(id),"railway transaction did not populate both stores");
    h.assertTrue(entry.road.settings().options().railbed().active()&&!entry.road.legacyRailway(),"test bypassed the current railway transaction");
    h.assertTrue(entry.road.settings().options().railbed().paths().get(0).fingerprint().equals(paths.get(0).fingerprint()),"railway transaction moved imported alignment");
    return data.index.roads.get(id).record;
  }
  static void verify(GameTestHelper h,RoadRecord record,Shape shape){
    var mesh=record.mesh();var sides=record.settings().options().streetscape().sides();
    long deep=record.structures().stream().filter(p->p.pier()&&p.material()==RoadStructures.Material.CONCRETE&&Math.min(p.a().y(),p.b().y())<Y-2).count();
    if(shape==Shape.SINGLE_PIT||shape==Shape.SIDE_2X2||shape==Shape.SIDE_CHAIN){
      h.assertTrue(sides.negativeRaised().isEmpty()&&sides.positiveRaised().isEmpty(),"local pit became raised span: "+record.settings().style()+" "+shape+" "+sides);
      h.assertTrue(deep==0,"local pit generated bridge piers: "+record.settings().style()+" "+shape);
    }else{
      double at=shape==Shape.TRANSVERSE_2M?12:32;var sample=RoadStructures.sample(mesh,at);
      boolean negative=RoadStreetscape.raised(mesh,sample,-1),positive=RoadStreetscape.raised(mesh,sample,1);
      h.assertTrue(shape==Shape.HALF?negative!=positive:negative&&positive,"real opening lost correct half/full classification: "+record.settings().style()+" "+shape+" "+sides);
      h.assertTrue(deep>0,"real opening lost actual foundations: "+record.settings().style()+" "+shape);
    }
    System.out.println("SR204 HOLES WORLD CASE PASS "+record.settings().style()+" "+shape+" id="+record.id()+" width="+record.settings().width()+" sides="+sides+" deepPiers="+deep+" totalStructures="+record.structures().size());
  }
  static void run(GameTestHelper h,boolean railway){
    var level=h.getLevel();var player=FakePlayerFactory.get(level,new GameProfile(new UUID(20404,railway?2:1),railway?"SR204RailHole":"SR204RoadHole"));player.setGameMode(GameType.CREATIVE);
    int base=railway?870000:860000;var results=new ArrayList<Result>();var originals=new HashMap<UUID,RoadRecord>();
    for(var shape:Shape.values()){
      int x=base+shape.ordinal()*250;terrain(h,x,shape);RoadRecord record;
      if(railway)record=buildRailway(h,player,x);
      else{var a=marker(level,player,new V(x+.5,Y,.5));var b=marker(level,player,new V(x+.5,Y,64.5));record=RoadData.get(level).connect(level,player,a,b,street(),null);}
      h.assertTrue(record.owner().equals(player.getUUID()),"fixture bypassed owned-player construction");verify(h,record,shape);results.add(new Result(record.id(),shape,railway));originals.put(record.id(),record);
      h.assertTrue(RoadWorkChunks.heldCount(level)==0,"hole case leaked chunk tickets");
    }
    var data=RoadData.get(level);var loaded=RoadData.load(data.save(new CompoundTag()));level.getDataStorage().set("splineroads",loaded);
    for(var result:results){
      var record=loaded.index.roads.get(result.id()).record;var old=originals.get(result.id());verify(h,record,result.shape());
      h.assertTrue(record.structures().equals(old.structures()),"reload changed bridge structures");
      h.assertTrue(record.settings().options().streetscape().sides().equals(old.settings().options().streetscape().sides()),"reload changed half classifications");
      if(railway){h.assertTrue(loaded.railbeds.entries.containsKey(result.id()),"reload lost railway store entry");h.assertTrue(record.settings().options().railbed().paths().get(0).fingerprint().equals(old.settings().options().railbed().paths().get(0).fingerprint()),"reload changed MTR source snapshot");}
    }
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"hole suite leaked tickets");
    System.out.println("SR204 HOLES WORLD PASS "+(railway?"actual-RailbedRoads":"owned-road-connect")+" single/side2x2/half/full/transverse2m/side-chain real terrain/real structures/save-load");h.succeed();
  }
  @GameTest(template="empty",templateNamespace="splineroads_204_holes",timeoutTicks=24000)
  public static void narrowRoadHolesAndRealOpenings(GameTestHelper h){run(h,false);}
  @GameTest(template="empty",templateNamespace="splineroads_204_holes",timeoutTicks=24000)
  public static void railwayHolesAndRealOpenings(GameTestHelper h){run(h,true);}
}
