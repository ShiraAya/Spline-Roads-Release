package com.sora.splineroads.world;
import java.util.*;
import com.sora.splineroads.core.*;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid="splineroads")
public final class Feedback212World {
 @SubscribeEvent public static void started(ServerStartedEvent e){
  if(!Boolean.getBoolean("road.feedbackFix1"))return;
  var server=e.getServer();var level=server.overworld();
  try{
   var data=RoadData.get(level);System.out.println("FIX1_WORLD roads="+data.index.roads.size());
   int checks=0;
   for(String id:List.of("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8","2618586e-ceb0-4a05-839d-cd6080c8d27c","50716f67-d317-49c8-9982-2006341a502a")){
    var road=data.index.roads.get(UUID.fromString(id));
    for(long chunk:road.chunks)level.getChunk((int)chunk,(int)(chunk>>32));
    var all=new ArrayList<>(data.index.roads.values());
    var retained=terrain(data,"terrainFill");var originals=terrain(data,"terrainOriginal");
    var result=StructurePlanner.plan(level,road,all,retained,originals,new RoadTerrain.Cache(retained,originals),new RoadPlanningIndex(all));
    System.out.println("FIX1_PLAN "+id+" old="+road.record.structures().size()+" new="+result.structures().size()+" piers="+result.structures().stream().filter(RoadStructures.Part::pier).count()+" walks="+result.structures().stream().filter(RoadSidewalks::smoothPart).count());
    if(id.startsWith("680c")){
      var split=data.splitJunctionStructures(level,List.of(road.record),road.record.id()).get(0);
      require(split.structures().stream().anyMatch(RoadSidewalks::smoothPart),"split ordinary half lost walkways");checks++;
    }
    if(id.startsWith("2618")){require(result.structures().stream().filter(RoadStructures.Part::pier).count()>=411,"overpass support fallback absent");checks++;}
    if(id.startsWith("5071")){
      var ground=RoadTerrain.read(level,retained,originals,new RoadTerrain.Cache(retained,originals));
      var finalRoad=new RoadIndex.Built(result);int scanned=0,clipped=0,maxBoxes=0,persisted=0;
      for(var p:result.structures())if(p.material()==RoadStructures.Material.TUNNEL&&p.a().distance(new RoadGeometry.V(1008,40,790))<24){
        var at=net.minecraft.core.BlockPos.containing(p.a().x(),p.a().y()+1,p.a().z());
        if(level.getBlockEntity(at) instanceof RoadFillEntity saved&&saved.saveWithoutMetadata().getBoolean("TerrainCutout"))persisted++;
        var mask=RoadInfillMask.cut(at,.83,List.of(finalRoad),List.of());scanned++;
        if(mask.clipped()){
          clipped++;maxBoxes=Math.max(maxBoxes,mask.boxes().size());
          var tag=new net.minecraft.nbt.CompoundTag();tag.put("Fill",net.minecraft.nbt.NbtUtils.writeBlockState(net.minecraft.world.level.block.Blocks.SAND.defaultBlockState()));
          tag.putDouble("TerrainCap",.83);tag.putBoolean("TerrainCutout",true);var list=new net.minecraft.nbt.ListTag();
          for(var b:mask.boxes()){var values=new net.minecraft.nbt.ListTag();for(double n:new double[]{b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1()})values.add(net.minecraft.nbt.DoubleTag.valueOf(n));list.add(values);}tag.put("TerrainEdge",list);
          var entity=new RoadFillEntity(at,com.sora.splineroads.SplineRoads.FILLED_COLLIDER.get().defaultBlockState());entity.load(tag);
          require(entity.edge().equals(mask.boxes()),"wall mask changed after NBT load");
          var reloaded=new RoadFillEntity(at,entity.getBlockState());reloaded.load(entity.saveWithoutMetadata());
          require(reloaded.edge().equals(mask.boxes()),"wall mask changed after NBT roundtrip");
          var shape=net.minecraft.world.phys.shapes.Shapes.empty();
          for(var b:mask.boxes())shape=net.minecraft.world.phys.shapes.Shapes.or(shape,net.minecraft.world.phys.shapes.Shapes.box(b.x0(),b.y0()*.83,b.z0(),b.x1(),b.y1()*.83,b.z1()));
          var cell=new RoadRaster.Cell(at.getX(),at.getY(),at.getZ());
          for(var b:finalRoad.prepareCollision().structureColumn(at.getX(),at.getZ(),at.getY(),at.getY()+1,q->q.material()==RoadStructures.Material.TUNNEL).getOrDefault(cell,List.of()))
            require(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape,net.minecraft.world.phys.shapes.Shapes.box(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1()),net.minecraft.world.phys.shapes.BooleanOp.AND),"infill intersects diagonal wall");
        }
      }
      require(scanned>0&&clipped>0,"wall test must clip actual report geometry");checks++;
      int buried=0;
      for(var part:result.structures())if(RoadSidewalks.smoothPart(part)&&!part.model().equals("sr:cycle_cover_walk")){
        var q=RoadQueries.horizontal(finalRoad.mesh,part.a());var sample=q.sample();
        if(RoadStreetscape.underground(finalRoad.mesh,sample.distance())&&Math.abs(q.lateral())>sample.halfWidth())buried++;
      }
      require(buried==0,"sidewalk remains in roofed tunnel: "+buried);checks++;
      if(Boolean.getBoolean("road.feedback212Reload"))require(persisted>0,"actual saved wall cutouts absent after server restart");
      System.out.println("FIX1_WALL scanned="+scanned+" clipped="+clipped+" buriedWalks="+buried+" maxBoxes="+maxBoxes+" persisted="+persisted);
    }
   }
   var fillTag=new net.minecraft.nbt.CompoundTag();fillTag.put("Fill",net.minecraft.nbt.NbtUtils.writeBlockState(net.minecraft.world.level.block.Blocks.SAND.defaultBlockState()));
   fillTag.putDouble("TerrainCap",.6);fillTag.putBoolean("TerrainCutout",true);
   var fillEntity=new RoadFillEntity(net.minecraft.core.BlockPos.ZERO,com.sora.splineroads.SplineRoads.FILLED_COLLIDER.get().defaultBlockState());fillEntity.load(fillTag);
   require(fillEntity.collision(level,net.minecraft.core.BlockPos.ZERO,net.minecraft.world.phys.shapes.CollisionContext.empty(),net.minecraft.world.phys.shapes.Shapes.empty()).isEmpty(),"empty cutout restored a full terrain cube");
   require(fillEntity.saveWithoutMetadata().getBoolean("TerrainCutout"),"cutout flag lost on reload");checks++;
   System.out.println("FIX1_WORLD_PASS checks="+checks);
   if(Boolean.getBoolean("road.feedback212Cuts")){
    var ids=new LinkedHashSet<UUID>();ids.add(UUID.fromString("50716f67-d317-49c8-9982-2006341a502a"));
    for(var point:List.of(new RoadGeometry.V(1314,56,596),new RoadGeometry.V(1213,64,946),new RoadGeometry.V(1528,72,669))){
      var road=data.index.roads.values().stream().filter(r->r.record.settings().structure()==RoadGeometry.Structure.AUTO&&r.record.junction()==null).min(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,point).horizontalDistance())).orElseThrow();
      ids.add(road.record.id());System.out.println("FIX1_CUT_SELECTED "+point+" "+road.record.id());
    }
    for(var id:ids){var old=data.index.roads.get(id);System.out.println("FIX1_CUT_SAVE_BEGIN "+id);
      for(long chunk:old.chunks)level.getChunk((int)chunk,(int)(chunk>>32));
      data.replaceAssembly(level,null,List.of(new RoadIndex.Built(old.record.structures(List.of()))),Set.of(id),Set.of(old.record.a(),old.record.b()));
      var road=data.index.roads.get(id);int interior=0;
      for(var region:RoadAutoTunnels.openRegions(road.record.structures()))for(double d=region.from()+.5;d<region.to();d+=1){
        var sample=RoadStructures.sample(road.mesh,d);
        for(int side:new int[]{-1,1}){var point=sample.at(side*(sample.halfWidth()-.1),-1);var at=net.minecraft.core.BlockPos.containing(point.x(),point.y(),point.z());
          require(road.tunnelAt(at),"open cut missing dry interior");
          var state=level.getBlockState(at);require((state.isAir()||TunnelAir.isAirSpace(state)||RoadBlocks.isCollider(state))&&(!state.hasProperty(RoadBlocks.Road.FILL)||state.getValue(RoadBlocks.Road.FILL)==RoadBlocks.Fill.NONE)&&!(level.getBlockEntity(at) instanceof RoadFillEntity fill&&!fill.fill().isAir()),"terrain remains inside open cut "+at+" "+state);interior++;
        }
      }
      require(interior>0,"open cut interior check must be positive");System.out.println("FIX1_CUT_SAVE_PASS "+id+" interior="+interior);
    }
   }
   if(Boolean.getBoolean("road.feedback212Edit")){
    int saved=0;var errors=new ArrayList<String>();
    for(String id:List.of("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8","2618586e-ceb0-4a05-839d-cd6080c8d27c","50716f67-d317-49c8-9982-2006341a502a"))try{
      var old=data.index.roads.get(UUID.fromString(id));System.out.println("FIX1_SAVE_BEGIN "+id);
      data.replaceAssembly(level,null,List.of(new RoadIndex.Built(old.record.structures(List.of()))),Set.of(old.record.id()),Set.of(old.record.a(),old.record.b()));
      require(!data.index.roads.get(old.record.id()).record.structures().isEmpty(),"save lost structures");saved++;System.out.println("FIX1_SAVE_OK "+id);
    }catch(Throwable f){f.printStackTrace();errors.add(id+": "+f);}
    try{
      var group=UUID.fromString("086ce4f0-d227-453e-bc4e-648ceae2683c");var before=data.junctions.get(group);
      var original=RoadRecord.load(before.getCompound("OriginalMain"));level.getChunkAt(net.minecraft.core.BlockPos.of(before.getLong("Branch")));
      var payload=OneSidedJunctions.payload(level,null,group);System.out.println("FIX1_SAVE_BEGIN oneSide");
      System.out.println("FIX1_SAVE_OK "+OneSidedJunctions.build(level,null,payload));saved++;
    }catch(Throwable f){f.printStackTrace();errors.add("oneSide: "+f);}
    try{
      var road=data.index.roads.get(UUID.fromString("2c0e4c31-4bcd-4877-9c24-96a38665b5e2"));var t=Interchanges.descriptor(data,road.record.assembly());
      System.out.println("FIX1_SAVE_BEGIN interchange");System.out.println("FIX1_SAVE_OK "+Interchanges.build(level,null,t));saved++;
    }catch(Throwable f){f.printStackTrace();errors.add("interchange: "+f);}
    require(errors.isEmpty(),"save failures "+errors);System.out.println("FIX1_SAVE_PASS actions="+saved);
   }
  }catch(Throwable failure){System.out.println("FIX1_WORLD_FAIL");failure.printStackTrace();}
  finally{server.halt(false);}
 }
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 @SuppressWarnings("unchecked") private static Map<Long,net.minecraft.world.level.block.state.BlockState> terrain(RoadData data,String name)throws Exception{
  var field=RoadData.class.getDeclaredField(name);field.setAccessible(true);return (Map<Long,net.minecraft.world.level.block.state.BlockState>)field.get(data);
 }
}
