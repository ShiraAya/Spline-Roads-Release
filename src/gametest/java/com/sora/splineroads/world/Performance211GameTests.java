package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.*;

/** World-level fault injection for the primitive transaction maps. */
@GameTestHolder("splineroads_performance211") @PrefixGameTestTemplate(false)
public final class Performance211GameTests {
  private static final class Injected extends RuntimeException {}
  @SuppressWarnings("unchecked") private static Map<Long,BlockState> ledger(RoadData data,String name)throws Exception {
    var f=RoadData.class.getDeclaredField(name);f.setAccessible(true);return Map.copyOf((Map<Long,BlockState>)f.get(data));
  }
  private record Snapshot(Map<UUID,RoadIndex.Built> roads,Map<UUID,RoadRecord> streets,
      Map<Long,BlockState> blocks,Map<Long,CompoundTag> entities,Map<Long,BlockState> original,Map<Long,BlockState> fill,Map<Long,BlockState> walks){}
  private static Snapshot snapshot(ServerLevel level,RoadData data,int y)throws Exception {
    var states=new HashMap<Long,BlockState>();var entities=new HashMap<Long,CompoundTag>();
    for(var p:BlockPos.betweenClosed(new BlockPos(299980,y-3,-10),new BlockPos(300020,y+9,100))){
      states.put(p.asLong(),level.getBlockState(p));var be=level.getBlockEntity(p);if(be!=null)entities.put(p.asLong(),be.saveWithFullMetadata());
    }
    return new Snapshot(Map.copyOf(data.index.roads),Map.copyOf(data.streets),states,entities,ledger(data,"terrainOriginal"),ledger(data,"terrainFill"),ledger(data,"sidewalkPlaced"));
  }
  private static void exact(GameTestHelper h,Snapshot before,Snapshot after,String stage){
    if(!before.equals(after)){
      System.out.println("PERF211 ROLLBACK DIFF "+stage+" roads="+before.roads().equals(after.roads())+" streets="+before.streets().equals(after.streets())+" blocks="+before.blocks().equals(after.blocks())+" entities="+before.entities().equals(after.entities())+" original="+before.original().equals(after.original())+" fill="+before.fill().equals(after.fill())+" walks="+before.walks().equals(after.walks()));
      before.blocks().entrySet().stream().filter(e->!Objects.equals(e.getValue(),after.blocks().get(e.getKey()))).limit(12).forEach(e->System.out.println("PERF211 BLOCK DIFF "+BlockPos.of(e.getKey())+" before="+e.getValue()+" after="+after.blocks().get(e.getKey())));
      before.entities().entrySet().stream().filter(e->!Objects.equals(e.getValue(),after.entities().get(e.getKey()))).limit(6).forEach(e->System.out.println("PERF211 ENTITY DIFF "+BlockPos.of(e.getKey())+" before="+e.getValue()+" after="+after.entities().get(e.getKey())));
    }
    h.assertTrue(before.equals(after),"rollback differed at "+stage);
    h.assertTrue(RoadWorkChunks.heldCount(h.getLevel())==0,"chunk tickets leaked at "+stage);
  }
  @GameTest(template="empty",templateNamespace="splineroads_performance211",timeoutTicks=16000)
  public static void transactionMapsKeepExactRollback(GameTestHelper h)throws Exception {
    var level=h.getLevel();var data=RoadData.get(level);level.getChunkAt(new BlockPos(300000,0,0));
    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,300000,0);
    boolean natural=Boolean.getBoolean("road.rollbackNaturalSnow");
    // Keep the rollback fixture deterministic across random server world seeds.
    // The entire changed platform is isolated in this test's distant region.
    if(!natural)for(var pos:BlockPos.betweenClosed(new BlockPos(299980,y-3,-10),new BlockPos(300020,y+9,100)))
      level.setBlock(pos,pos.getY()<y?net.minecraft.world.level.block.Blocks.STONE.defaultBlockState():net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2|16);
    var a=Curve201GameTests.marker(level,new Node(new V(300000.5,y+.125,.5),0,0));
    var b=Curve201GameTests.marker(level,new Node(new V(300000.5,y+.125,80.5),0,0));
    var settings=new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.4,90).structure(Structure.GROUND);
    settings=settings.options(settings.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.OFF));
    var changed=new Settings(Mode.STRAIGHT,Style.O2_YELLOW,11,1,.4,90).structure(Structure.GROUND).options(settings.options());
    var road=data.connect(level,null,a,b,settings,null);
    // Exercise support-dependent snow explicitly instead of relying on the
    // random terrain's biome to reveal neighbour updates outside edit.touched.
    int snow=0;
    if(!natural)for(int x:new int[]{299995,300005})for(int z=8;z<=72;z+=4){
      var pos=new BlockPos(x,y,z);
      var layer=net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState();
      if(layer.canSurvive(level,pos)){level.setBlock(pos,layer,2);snow++;}
    }
    var before=snapshot(level,data,y);
    if(natural)snow=(int)before.blocks().values().stream().filter(s->s.is(net.minecraft.world.level.block.Blocks.SNOW)).count();
    h.assertTrue(snow>0,"snow rollback fixture has no supported layers");
    System.out.println("PERF211 SNOW FIXTURE natural="+natural+" snow_layers="+snow+" road_y="+y);
    int cases=0;
    for(String stage:List.of("remove","index","ledger","write","metadata")){
      int[] visits={0};RoadData.editFault=at->{if(at.equals(stage)&&++visits[0]>=(stage.equals("write")?25:1))throw new Injected();};
      boolean failed=false;
      try{data.connect(level,null,a,b,changed,road.id());}catch(Injected expected){failed=true;}finally{RoadData.editFault=at->{};}
      h.assertTrue(failed,"fault stage not reached "+stage);exact(h,before,snapshot(level,data,y),stage);cases++;
    }
    for(String stage:List.of("index","write","world","metadata")){
      var plan=RoadSplitter.plan(level,null,road,36.5,RoadSlicing.Connection.CONTINUOUS);
      int[] visits={0};RoadData.splitFault=at->{if(at.equals(stage)&&++visits[0]>=1)throw new Injected();};boolean failed=false;
      try{RoadSplitter.commit(level,null,plan);}catch(Injected expected){failed=true;}finally{RoadData.splitFault=at->{};}
      h.assertTrue(failed,"split fault stage not reached "+stage);exact(h,before,snapshot(level,data,y),"split-"+stage);cases++;
    }
    data.connect(level,null,a,b,changed,road.id());
    var edited=data.index.roads.get(road.id()).record;
    var plan=RoadSplitter.plan(level,null,edited,36.5,RoadSlicing.Connection.CONTINUOUS);RoadSplitter.commit(level,null,plan);
    h.assertTrue(data.index.roads.containsKey(plan.pair().second().id()),"successful split missing second road");
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"successful transaction leaked tickets");
    System.out.println("PERF211 ROLLBACK PASS fault_cases="+cases+" exact_blocks_per_case="+before.blocks().size()+" explicit_snow_layers="+snow+" plus successful edit and split");h.succeed();
  }
}
