package com.sora.splineroads.world;

import java.util.*;
import java.util.concurrent.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Real server transactions, independently counted; no GameTest-count claims. */
@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix3Safety {
 static int ticks,stage,checks,writes;static boolean done;static CompletableFuture<RoadRecord> future;static RoadRecord built;
 static final BlockPos A=new BlockPos(0,90,0),B=new BlockPos(0,90,40),P=new BlockPos(4,90,20);
 static final RoadGeometry.Settings SETTINGS=RoadGeometry.Settings.defaults();
 static CompoundTag originalData;static Map<Long,net.minecraft.world.level.block.state.BlockState> originalBlocks;static Map<Long,CompoundTag> originalEntities;
 static void require(boolean condition,String text){if(!condition)throw new AssertionError(text);}
 static void pass(String text){checks++;System.out.println("FIX3_SAFETY_PASS "+text);}
 static void rejects(Runnable action,String text){boolean denied=false;try{action.run();}catch(IllegalArgumentException expected){denied=true;}require(denied,text);pass(text);}
 static RoadBuildWorld snapshot(ServerLevel level){var w=new RoadBuildWorld(level,new UUID(0,0));for(int x=-2;x<=2;x++)for(int z=-2;z<=4;z++){var chunk=level.getChunk(x,z);w.columns.put(ChunkPos.asLong(x,z),CompletableFuture.completedFuture(RoadBuildWorld.capture(chunk)));}return w;}
 static void snapshotTests(ServerLevel level){
  var data=RoadData.get(level);var w=snapshot(level);var copy=data.buildCopy(level).get();
  try(var scope=w.enter(copy)){w.write(P,Blocks.GOLD_BLOCK.defaultBlockState());require(level.getBlockState(P).isAir(),"private overlay leaked live write");require(w.getBlockState(P).is(Blocks.GOLD_BLOCK),"overlay did not retain target");w.changes();}
  w.validate(null);pass("snapshot overlay isolation");
  final var terrainWorld=w;level.setBlock(P,Blocks.DIAMOND_BLOCK.defaultBlockState(),18);rejects(()->terrainWorld.validate(null),"reject terrain changed after capture");level.setBlock(P,Blocks.AIR.defaultBlockState(),18);
  w=snapshot(level);final var nodeWorld=w;try(var scope=w.enter(copy)){w.getBlockEntity(A);w.changes();}
  var node=(NodeEntity)level.getBlockEntity(A);node.offsetX=.125;node.setChanged();rejects(()->nodeWorld.validate(null),"reject changed endpoint entity");node.offsetX=0;node.setChanged();
  w=snapshot(level);final var heightWorld=w;w.height(P.getX(),P.getZ());w.changes();level.setBlock(P.above(20),Blocks.STONE.defaultBlockState(),18);rejects(()->heightWorld.validate(null),"reject changed sampled height");level.setBlock(P.above(20),Blocks.AIR.defaultBlockState(),18);
  var other=(NodeEntity)level.getBlockEntity(B);node.junctionSource=other.junctionSource=UUID.randomUUID();w=snapshot(level);node.junctionSource=other.junctionSource=null;
  try(var scope=w.enter(copy)){rejects(()->JunctionPointTool.requireDifferent(level,A,B),"junction source guard reads isolated endpoints");}
  var requests=new RoadEditRequests();var owner=new Object();var request=requests.submit(owner,"connect","test");require(requests.pending(request.id(),request.generation(),request.action(),request.target())==request,"pending ack consumed request");require(requests.take(request.id(),request.generation(),request.action(),request.target())==request,"final result lost after ack");pass("pending receipt preserves final result");
 }
 static CompletableFuture<RoadRecord> submit(ServerLevel level,UUID replace){return RoadBuildJobs.connect(level,null,A,B,SETTINGS,replace,false,false,false,List.of());}
 static void remember(ServerLevel level){originalData=RoadData.get(level).save(new CompoundTag());originalBlocks=new HashMap<>();originalEntities=new HashMap<>();for(int x=-12;x<=12;x++)for(int z=-12;z<=52;z++)for(int y=86;y<=96;y++){var p=new BlockPos(x,y,z);originalBlocks.put(p.asLong(),level.getBlockState(p));var entity=level.getBlockEntity(p);if(entity!=null)originalEntities.put(p.asLong(),entity.saveWithFullMetadata());}}
 static void unchanged(ServerLevel level){require(originalData.equals(RoadData.get(level).save(new CompoundTag())),"failed build changed saved road metadata");for(var e:originalBlocks.entrySet()){var p=BlockPos.of(e.getKey());require(level.getBlockState(p).equals(e.getValue()),"failed build left block "+p);var entity=level.getBlockEntity(p);require(Objects.equals(originalEntities.get(e.getKey()),entity==null?null:entity.saveWithFullMetadata()),"failed build left entity "+p);}}
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
  if(!Boolean.getBoolean("road.feedbackFix3Safety")||done||event.phase!=TickEvent.Phase.END)return;var server=event.getServer();var level=server.overworld();ticks++;
  try{
   if(ticks>4000)throw new AssertionError("safety timeout stage="+stage);
   if(stage==0){
    for(var p:List.of(A,B)){level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),18);var node=(NodeEntity)level.getBlockEntity(p);node.owner=new UUID(0,0);node.heightExplicit=true;node.yaw=0;node.setChanged();}
    snapshotTests(level);remember(level);future=submit(level,null);rejects(()->submit(level,null),"reject duplicate build");future.cancel(false);stage=1;return;
   }
   if(stage==1){if(RoadBuildJobs.busy(level))return;unchanged(level);pass("cancel releases job without writes");future=submit(level,null);stage=2;return;}
   if(stage==2){if(!future.isDone())return;built=future.join();require(RoadData.get(level).index.roads.containsKey(built.id()),"successful async road absent");pass("async road reaches world and data");remember(level);writes=0;RoadBuildJobs.commitFault=s->{if(s.equals("write")&&++writes==8)throw new IllegalStateException("injected commit failure");};
    // A wider deck ensures real changed cells, even after a successful save.
    var settings=new RoadGeometry.Settings(SETTINGS.mode(),SETTINGS.style(),13,SETTINGS.thickness(),SETTINGS.tension(),SETTINGS.arcDegrees());
    future=RoadBuildJobs.connect(level,null,A,B,settings,built.id(),false,false,false,List.of());stage=3;return;
   }
   if(stage==3){if(!future.isDone())return;RoadBuildJobs.commitFault=s->{};require(future.isCompletedExceptionally(),"fault did not fail commit");require(writes==8,"fault missed actual writes");unchanged(level);pass("mid-commit rollback restores blocks entities and road data");System.out.println("FIX3_SAFETY_COMPLETE checks="+checks);done=true;server.halt(false);}
  }catch(Throwable failure){RoadBuildJobs.commitFault=s->{};done=true;System.out.println("FIX3_SAFETY_FAIL stage="+stage);failure.printStackTrace();server.halt(false);}
 }
}
