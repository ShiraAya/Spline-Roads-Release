package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.*;

/** The retained material occupies only space outside structural wall and walk solids. */
final class RoadInfillMask {
  record Result(boolean clipped,List<RoadRaster.Box> boxes){}
  static Result cut(BlockPos pos,double cap,List<RoadIndex.Built> roads,List<RoadRaster.Box> initial){
    VoxelShape remaining=Shapes.empty();
    if(initial.isEmpty())remaining=Shapes.box(0,0,0,1,cap,1);
    else for(var b:initial)remaining=Shapes.or(remaining,Shapes.box(b.x0(),b.y0()*cap,b.z0(),b.x1(),b.y1()*cap,b.z1()));
    boolean clipped=false;
    var cell=new RoadRaster.Cell(pos.getX(),pos.getY(),pos.getZ());
    for(var road:roads)for(var entry:road.prepareCollision().structureColumn(pos.getX(),pos.getZ(),pos.getY(),pos.getY()+1,
        p->p.material()==RoadStructures.Material.TUNNEL||RoadSidewalks.smoothPart(p)).entrySet()){
      if(!entry.getKey().equals(cell))continue;
      for(var b:entry.getValue()){
        var solid=Shapes.box(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1());
        if(Shapes.joinIsNotEmpty(remaining,solid,BooleanOp.AND)){remaining=Shapes.joinUnoptimized(remaining,solid,BooleanOp.ONLY_FIRST);clipped=true;}
      }
    }
    if(!clipped)return new Result(false,initial);
    // Stored/rendered masks use normalized height; CAP is applied exactly once.
    return new Result(true,remaining.optimize().toAabbs().stream().map(b->new RoadRaster.Box(b.minX,b.minY/cap,b.minZ,b.maxX,b.maxY/cap,b.maxZ)).toList());
  }
  private RoadInfillMask(){}
}
