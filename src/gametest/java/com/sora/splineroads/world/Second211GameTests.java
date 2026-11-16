package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.core.BlockPos;import net.minecraft.gametest.framework.*;import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
@net.minecraftforge.gametest.GameTestHolder("splineroads_211_second")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class Second211GameTests {
 @GameTest(template="empty",templateNamespace="splineroads_211_second",timeoutTicks=16000)
 public static void reportedRemoteConnection(GameTestHelper h){
  var l=h.getLevel();var d=RoadData.get(l);var host=d.index.roads.get(UUID.fromString("b7017daa-18ee-417d-9fab-e96728670cf3")).record;
  var near=new BlockPos(251,56,584);var nodes=new ArrayList<BlockPos>();
  for(int x=230;x<=270;x++)for(int z=520;z<=600;z++){var p=new BlockPos(x,56,z);l.getChunkAt(p);if(l.getBlockEntity(p) instanceof NodeEntity)nodes.add(p);}
  System.out.println("SECOND211 REPORTED NODES "+nodes);
  var end=nodes.stream().min(Comparator.comparingDouble(p->p.distSqr(near))).orElseGet(()->Curve201GameTests.marker(l,new Node(new V(251.5,56,584.5),-90,0)));
  var s=RoadLanes.configure(new Settings(Mode.AUTO,Style.O6_RAIL,32,1,.4,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),5);
  s=s.options(s.options().extras(false,false,false).route(s.options().routing().fit(false)));
  long start=System.nanoTime();System.out.println("SECOND211 CONNECT "+host.b()+" -> "+end);
  try{var road=d.connect(l,null,host.b(),end,s,null);System.out.println("SECOND211 CONNECT PASS "+road.id()+" ms="+(System.nanoTime()-start)/1e6);h.succeed();}
  catch(RuntimeException failure){failure.printStackTrace();throw failure;}
 }
 @GameTest(template="empty",templateNamespace="splineroads_211_second",timeoutTicks=200)
 public static void wetFoundation(GameTestHelper h){
  var l=h.getLevel();var p=h.absolutePos(new BlockPos(3,3,3));
  for(int y=0;y<=8;y++)l.setBlock(p.above(y),Blocks.WATER.defaultBlockState(),2);
  l.setBlock(p,Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true),2);
  var g=RoadTerrain.read(l,Map.of(),Map.of(),new HashMap<>());double top=g.top(p.getX()+.5,p.getZ()+.5,p.getY()+7);
  h.assertTrue(Math.abs(top-(p.getY()+.5))<1e-7,"waterlogged foundation skipped: "+top);
  h.assertTrue(l.getBlockState(p).getValue(BlockStateProperties.WATERLOGGED),"foundation read changed waterlogged slab");
  var stone=p.east(2);l.setBlock(stone,Blocks.STONE.defaultBlockState(),2);for(int y=1;y<=8;y++)l.setBlock(stone.above(y),Blocks.WATER.defaultBlockState(),2);
  var wet=RoadTerrain.read(l,Map.of(),Map.of(),new HashMap<>());h.assertTrue(Math.abs(wet.top(stone.getX()+.5,stone.getZ()+.5,stone.getY()+7)-(stone.getY()+1))<1e-7,"ordinary underwater stone skipped");
  h.assertTrue(l.getBlockState(stone).is(Blocks.STONE),"foundation read destroyed stone");h.succeed();
 }
}
