package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.*;

/** The retained material occupies only space outside structural wall and walk solids. */
final class RoadInfillMask {
  record Result(boolean clipped,List<RoadRaster.Box> boxes){}
  static Result cut(BlockPos pos,double cap,List<RoadIndex.Built> roads,List<RoadRaster.Box> initial){
    var cuts=new ArrayList<RoadRaster.Box>();
    var cell=new RoadRaster.Cell(pos.getX(),pos.getY(),pos.getZ());
    for(var road:roads)for(var entry:road.prepareCollision().structureColumn(pos.getX(),pos.getZ(),pos.getY(),pos.getY()+1,
        p->p.material()==RoadStructures.Material.TUNNEL||RoadSidewalks.smoothPart(p)).entrySet()){
      if(!entry.getKey().equals(cell))continue;
      cuts.addAll(entry.getValue());
    }
    return cutBoxes(cap,initial,cuts);
  }
  static Result cutBoxes(double cap,List<RoadRaster.Box> initial,List<RoadRaster.Box> cuts){
    if(cuts.isEmpty())return new Result(false,initial);
    var base=initial.isEmpty()?List.of(new RoadRaster.Box(0,0,0,1,cap,1)):
        initial.stream().map(b->new RoadRaster.Box(b.x0(),b.y0()*cap,b.z0(),b.x1(),b.y1()*cap,b.z1())).toList();
    var remaining=RoadCollisionShape.build(base);var solid=RoadCollisionShape.build(cuts);
    if(!Shapes.joinIsNotEmpty(remaining,solid,BooleanOp.AND))return new Result(false,initial);
    // Union once, then subtract once. Re-merging the voxel coordinate grids for
    // every tiny diagonal wall box made long-road save validation quadratic.
    remaining=Shapes.joinUnoptimized(remaining,solid,BooleanOp.ONLY_FIRST);
    // Stored/rendered masks use normalized height; CAP is applied exactly once.
    return new Result(true,remaining.toAabbs().stream().map(b->new RoadRaster.Box(b.minX,b.minY/cap,b.minZ,b.maxX,b.maxY/cap,b.maxZ)).toList());
  }
  private RoadInfillMask(){}
}
