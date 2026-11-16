package com.sora.splineroads.world;

import java.util.*;
import java.lang.reflect.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

@net.minecraftforge.gametest.GameTestHolder("splineroads_211_feedback")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class Feedback211GameTests {
  @GameTest(template="empty",templateNamespace="splineroads_211_feedback",timeoutTicks=200)
  public static void canonicalAirWriteReceipts(GameTestHelper h)throws Exception{
    var level=h.getLevel();var pos=h.absolutePos(new BlockPos(1,4,1));level.getChunkAt(pos);
    var verify=RoadData.class.getDeclaredMethod("requireBlockState",ServerLevel.class,BlockPos.class,BlockState.class);verify.setAccessible(true);
    int checks=0;
    for(var actual:List.of(Blocks.AIR,Blocks.CAVE_AIR,Blocks.VOID_AIR))for(var expected:List.of(Blocks.AIR,Blocks.CAVE_AIR,Blocks.VOID_AIR)){
      level.setBlock(pos,actual.defaultBlockState(),2);verify.invoke(null,level,pos,expected.defaultBlockState());checks++;
      RoadData.restorePlannerBlocks(level,Map.of(pos.asLong(),expected.defaultBlockState()),Map.of());
      h.assertTrue(level.getBlockState(pos).isAir(),"air restore produced a solid");checks++;
    }
    for(var actual:List.of(Blocks.STONE,Blocks.WATER,Blocks.LAVA)){
      level.setBlock(pos,actual.defaultBlockState(),2);boolean failed=false;
      try{verify.invoke(null,level,pos,Blocks.AIR.defaultBlockState());}catch(InvocationTargetException e){failed=e.getCause() instanceof IllegalStateException;}
      h.assertTrue(failed,"solid/fluid mismatch accepted");checks++;
    }
    level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);boolean failed=false;
    try{verify.invoke(null,level,pos,Blocks.STONE.defaultBlockState());}catch(InvocationTargetException e){failed=e.getCause() instanceof IllegalStateException;}
    h.assertTrue(failed,"missing solid write accepted");checks++;
    System.out.println("SR211 AIR RECEIPTS PASS checks="+checks);h.succeed();
  }
  @GameTest(template="empty",templateNamespace="splineroads_211_feedback",timeoutTicks=200)
  public static void actualRefusedSolidWriteStillFails(GameTestHelper h)throws Exception{
    var write=RoadData.class.getDeclaredMethod("checkedSetBlock",ServerLevel.class,BlockPos.class,BlockState.class,int.class);write.setAccessible(true);
    var outside=new BlockPos(0,h.getLevel().getMinBuildHeight()-1,0);boolean rejected=false;
    try{write.invoke(null,h.getLevel(),outside,Blocks.STONE.defaultBlockState(),2);}catch(InvocationTargetException e){rejected=e.getCause() instanceof IllegalStateException;}
    h.assertTrue(rejected,"world-refused solid write lost its failure");h.succeed();
  }
}
