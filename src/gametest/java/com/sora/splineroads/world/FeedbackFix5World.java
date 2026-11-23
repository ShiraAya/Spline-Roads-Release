package com.sora.splineroads.world;

import java.util.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Runs only in the explicitly selected, isolated feedback world. */
@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix5World {
 static int ticks;static boolean done;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
  if(!Boolean.getBoolean("road.feedbackFix5")||done||event.phase!=TickEvent.Phase.END||++ticks<60)return;
  done=true;var server=event.getServer();var level=server.overworld();
  try{
   var data=RoadData.get(level);var id=UUID.fromString("4c66e878-5cbc-4d48-83f0-1bce403a31ca");
   var saved=data.interchanges.get(id).copy();var points=saved.getLongArray("Points");
   var payload=Interchanges.payload(level,null,points,id);var plan=Interchanges.plan(payload);
   var oldIds=new HashSet<>(data.index.roads.keySet());long start=System.nanoTime();
   System.out.println("FIX5_WORLD_BEGIN turbine roads="+oldIds.size());
   System.out.println("FIX5_WORLD_RESULT turbine "+Interchanges.build(level,null,Interchanges.command(payload,plan,"interchange")));
   check(Arrays.equals(points,data.interchanges.get(id).getLongArray("Points")),"feedback endpoints changed");
   int updated=0;for(var built:data.index.roads.values())if(id.equals(built.record.assembly())){updated++;RoadGrades.validate(built.mesh);}
   check(updated>=18,"turbine assembly missing roads");
   System.out.println("FIX5_WORLD_TURBINE_PASS roads="+updated+" elapsedMs="+(System.nanoTime()-start)/1e6);
   var fixture=List.of(new BlockPos(3500,75,4000),new BlockPos(4500,75,4000),new BlockPos(4000,56,3500),new BlockPos(4000,56,4500));
   for(var pos:fixture){level.setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),18);var node=(NodeEntity)level.getBlockEntity(pos);node.owner=new UUID(0,0);node.heightExplicit=true;node.offsetY=.125;node.setChanged();}
   var t=Interchanges.payload(level,null,fixture.stream().mapToLong(BlockPos::asLong).toArray(),null);
   t.put("Options",Interchanges.write(new InterchangePlanner.Options(InterchangePlanner.Preset.STACK,false,1,64,32,5,1)));
   start=System.nanoTime();System.out.println("FIX5_WORLD_BEGIN stack");
   System.out.println("FIX5_WORLD_RESULT stack "+Interchanges.build(level,null,Interchanges.command(t,Interchanges.plan(t),"interchange")));
   var stack=data.interchanges.values().stream().filter(v->v.getCompound("Options").getString("Preset").equals("STACK")).findFirst().orElseThrow();
   var stackId=stack.getUUID("Id");int count=0;for(var b:data.index.roads.values())if(stackId.equals(b.record.assembly())){count++;RoadGrades.validate(b.mesh);}
   check(count>=18,"stack assembly missing roads");
   System.out.println("FIX5_WORLD_STACK_PASS id="+stackId+" roads="+count+" elapsedMs="+(System.nanoTime()-start)/1e6);
   System.out.println("FIX5_WORLD_PASS totalRoads="+data.index.roads.size());
  }catch(Throwable failure){System.out.println("FIX5_WORLD_FAIL");failure.printStackTrace();}
  server.halt(false);
 }
}
