package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;
/** Cache immutable local masks during a transaction; exact footprints also reach the client. */
public final class RailbedTerrainEdge {
  public record Data(List<RoadRaster.Box> boxes,List<List<V>> polygons){}
  private static final Data EMPTY=new Data(List.of(),List.of());
  private record Key(BlockPos pos,List<Mesh> meshes){}
  private static final Map<Key,Data> CACHE=new LinkedHashMap<>(256,.75f,true){protected boolean removeEldestEntry(Map.Entry<Key,Data> e){return size()>4096;}};
  public static boolean present(BlockPos p,BlockState material,List<RoadIndex.Built> nearby){return !data(p,material,nearby).polygons().isEmpty();}
  public static List<RoadRaster.Box> boxes(BlockPos p,BlockState material,List<RoadIndex.Built> nearby){return data(p,material,nearby).boxes();}
  public static Data data(BlockPos p,BlockState material,List<RoadIndex.Built> nearby){
    if(!material.canOcclude()||material.hasBlockEntity()||!material.getFluidState().isEmpty())return EMPTY;
    boolean edge=false;var relevant=new ArrayList<Mesh>();
    for(var road:nearby){
      if(road.tunnelAt(p)||road.cycleBodyAt(p))return EMPTY;
      var c=road.column(p);if(c==null||p.getY()+1<=c.minTop()+1e-7||p.getY()>=c.maxTop()+Math.max(4.25,RoadInfrastructure.clearance(road.record.settings())))continue;
      relevant.add(road.mesh);edge|=road.record.railway();
    }
    if(!edge)return EMPTY;var key=new Key(p.immutable(),List.copyOf(relevant));
    synchronized(CACHE){var found=CACHE.get(key);if(found!=null)return found;}
    var polygons=TerrainEdgeGeometry.retain(new V(p.getX(),p.getY(),p.getZ()),relevant);
    var result=polygons.isEmpty()?EMPTY:new Data(collision(p,nearby),polygons);
    synchronized(CACHE){CACHE.put(key,result);}return result;
  }
  private static List<RoadRaster.Box> collision(BlockPos p,List<RoadIndex.Built> nearby){
    VoxelShape shape=Shapes.block();for(var road:nearby)for(var cells:road.prepareCollision().deckColumn(p.getX(),p.getZ()).values())for(var b:cells)shape=Shapes.joinUnoptimized(shape,Shapes.box(b.x0(),0,b.z0(),b.x1(),1,b.z1()),BooleanOp.ONLY_FIRST);
    return shape.optimize().toAabbs().stream().map(b->new RoadRaster.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ)).toList();
  }
  private RailbedTerrainEdge(){}
}
