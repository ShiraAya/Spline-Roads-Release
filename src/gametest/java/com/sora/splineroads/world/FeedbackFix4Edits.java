package com.sora.splineroads.world;
import java.util.*;
import java.util.concurrent.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadDraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Isolated feedback world: real editor transactions and server heartbeats. */
@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix4Edits {
 static int ticks,stage=Integer.getInteger("road.feedbackFix4Resume",0),jobTick;static boolean done;static long last,maxGap,begin;static CompletableFuture<String> future;
 static final List<net.minecraft.core.BlockPos> MANUAL=List.of(new net.minecraft.core.BlockPos(5000,56,5000),new net.minecraft.core.BlockPos(5000,56,4936),new net.minecraft.core.BlockPos(5064,56,5000),new net.minecraft.core.BlockPos(4936,56,5000));
 static UUID id(String text){return UUID.fromString(text);}
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 static final UUID BUMP=id("161cbbbe-5b24-41c4-8f95-b3e2c8187856"),CUT=id("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688");
 static final List<UUID> RAILS=List.of(id("2e9ac01f-fc51-35b9-9dbd-2c0c890aea6d"),id("8630159b-b3c0-3db6-ba5b-ab6866989a1e"));
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e){
  if(!Boolean.getBoolean("road.feedbackFix4Edit")||done||e.phase!=TickEvent.Phase.END)return;
  var server=e.getServer();var level=server.overworld();ticks++;long now=System.nanoTime();
  if(future!=null&&last!=0)maxGap=Math.max(maxGap,now-last);last=now;
  try{
   if(ticks==1)RoadData.get(level);if(ticks<60)return;
   if(future!=null){
    if(!future.isDone()){if(ticks%200==0)System.out.println("FIX4_HEARTBEAT stage="+stage+" ticks="+(ticks-jobTick)+" maxGapMs="+maxGap/1e6);if(now-begin>900_000_000_000L)throw new AssertionError("edit timeout");return;}
    System.out.println("FIX4_EDIT_PASS stage="+stage+" result="+future.join()+" elapsedMs="+(now-begin)/1e6+" ticks="+(ticks-jobTick)+" maxGapMs="+maxGap/1e6);
    check(maxGap<3_000_000_000L,"foreground stall stage "+stage);future=null;stage++;
   }
   var data=RoadData.get(level);
   if(stage==0){
    int denied=0;for(var road:List.of("2618586e-ceb0-4a05-839d-cd6080c8d27c","2c0e4c31-4bcd-4877-9c24-96a38665b5e2","14603d78-81b7-414f-91c1-728f6fd6a917","2e9ac01f-fc51-35b9-9dbd-2c0c890aea6d")){
     try{Interchanges.requireRoadEditor(data,id(road));}catch(IllegalArgumentException expected){denied++;}
    }check(denied==4,"interchange ownership bypass");Interchanges.requireRoadEditor(data,CUT);System.out.println("FIX4_POLICY_PASS rejected="+denied+" independentAllowed=1");stage++;
   }
   if(stage>5){
    var bump=data.index.roads.get(BUMP);check(bump.mesh.max().y()<=Math.max(bump.mesh.first().center().y(),bump.mesh.last().center().y())+1e-5,"ramp crest returned");
    for(var rail:RAILS){var b=data.index.roads.get(rail);long raised=b.record.structures().stream().filter(p->p.material()==RoadStructures.Material.CONCRETE&&!p.pier()&&p.height()<1&&p.a().y()>56.12).count();check(raised==0,"ground rail concrete base "+rail+" count="+raised);}
    var cut=data.index.roads.get(CUT);var spot=new V(1230,56,954);
    long walls=cut.record.structures().stream().filter(p->p.material()==RoadStructures.Material.TUNNEL&&p.a().sub(spot).horizontalLength()<5).count();check(walls>0,"shallow sidewalk wall missing");
    long rails=cut.record.structures().stream().filter(p->RoadRailSlabClipping.guardrail(p)&&p.a().sub(spot).horizontalLength()<3).count();check(rails==0,"rail remains under sidewalk");
    var host=data.index.roads.get(id("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8"));long paved=host.record.structures().stream().filter(LaneClosureLandscape::paved).count();check(paved>0,"gore asphalt missing");
    System.out.println("FIX4_WORLD_PASS walls="+walls+" railsUnderWalk="+rails+" asphalt="+paved+" maxRamp="+bump.mesh.max().y());done=true;server.halt(false);return;
   }
   if(stage==4)for(var pos:MANUAL)if(!(level.getBlockEntity(pos) instanceof NodeEntity)){level.setBlock(pos,com.sora.splineroads.SplineRoads.NODE.get().defaultBlockState(),18);var node=(NodeEntity)level.getBlockEntity(pos);node.owner=new UUID(0,0);node.heightExplicit=true;node.offsetY=.125;node.setChanged();}
   jobTick=ticks;begin=System.nanoTime();last=begin;maxGap=0;final int operation=stage;System.out.println("FIX4_EDIT_BEGIN stage="+stage);
   future=RoadBuildJobs.submit(level,null,copy->{
    check(!level.getServer().isSameThread(),"editor ran on server thread");
    if(operation<=2){var r=copy.index.roads.get(operation==1?BUMP:CUT).record;var cmd=new CompoundTag();cmd.putUUID("Id",r.id());cmd.putInt("Signature",r.header().hashCode());cmd.put("Settings",RoadDraft.editableSettings(RoadRecord.writeSettings(r.settings())));return LaneRamps.editRoad(level,null,cmd);}
    if(operation==3){var built=new ArrayList<RoadIndex.Built>();var nodes=new HashSet<net.minecraft.core.BlockPos>();for(var rail:RAILS){var r=copy.index.roads.get(rail).record;built.add(new RoadIndex.Built(r.structures(List.of())));nodes.add(r.a());nodes.add(r.b());}copy.replaceAssembly(level,null,built,new HashSet<>(RAILS),nodes);return "ground rails regenerated";}
    if(operation==4){
     if(copy.index.atNode(MANUAL.get(0)).isEmpty())Junctions.build(level,null,Junctions.payload(level,null,MANUAL.stream().mapToLong(net.minecraft.core.BlockPos::asLong).toArray(),null));
     var r=copy.index.roads.values().stream().map(b->b.record).filter(b->b.junction()!=null&&b.junction().get().arm()>=0&&copy.junctions.containsKey(b.assembly())).filter(b->{var j=copy.junctions.get(b.assembly());return !j.getBoolean("Auto")&&!j.getBoolean("OneSided")&&!j.hasUUID("Interchange");}).findFirst().orElseThrow(()->new AssertionError("manual junction fixture missing"));
     var cmd=JunctionRoads.payload(level,null,r);cmd.put("Settings",RoadDraft.editableSettings(cmd.getCompound("Settings")));return JunctionRoads.build(level,null,cmd);
    }
    var group=copy.junctions.entrySet().stream().filter(v->v.getValue().getBoolean("OneSided")&&!v.getValue().getBoolean("Paired")&&!v.getValue().getBoolean("MixedMarker")).map(Map.Entry::getKey).findFirst().orElseThrow(()->new AssertionError("side junction fixture missing"));
    var cmd=SideRoads.action(SideRoads.payload(OneSidedJunctions.payload(level,null,group)));cmd.put("Settings",RoadDraft.editableSettings(cmd.getCompound("Settings")));return OneSidedJunctions.buildRoadSelected(level,null,cmd,new ItemStack(Items.STICK));
   });
  }catch(Throwable failure){done=true;System.out.println("FIX4_WORLD_FAIL stage="+stage);failure.printStackTrace();server.halt(false);}
 }
}
