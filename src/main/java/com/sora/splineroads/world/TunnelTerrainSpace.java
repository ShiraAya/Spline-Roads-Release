package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadTunnelSpace;
import net.minecraft.core.BlockPos;
import java.util.List;

/** Shared construction/manual-infill policy for original terrain around tunnel shells.
 * Retaining a full cube must not fill a live corridor, including a different nearby road.
 * Sources are the FINAL planning snapshot during construction and the live index for infill.
 */
final class TunnelTerrainSpace {
  static boolean safe(BlockPos p,List<RoadIndex.Built> nearby){
    boolean shell=false;
    for(var road:nearby){
      shell|=road.shellAt(p);
      if(road.hasTunnel()){
        if(road.tunnelAt(p)||road.clearanceAt(p))return false;
      }else if(road.clearanceAt(p))return false;
    }
    return shell;
  }
  /** Manual fill may also occupy the outside of a support/skirt cell. Only its
   * unused volume is filled; every nearby live road and walkway remains protected. */
  static boolean exteriorInfill(BlockPos p,List<RoadIndex.Built> nearby){
    var cube=new RoadStructures.Part(new V(p.getX()+.5,p.getY(),p.getZ()),new V(p.getX()+.5,p.getY(),p.getZ()+1),1,1,false,RoadStructures.Material.CONCRETE);
    boolean supported=false;
    for(var road:nearby){
      if(road.hasTunnel()&&road.tunnelAt(p))return false;
      double air=Math.max(4.25,RoadInfrastructure.clearance(road.record.settings()));
      if(RoadClearance.structureInvades(cube,road.mesh,air)&&!RoadClearance.belowSurface(cube,road.mesh,1e-7))return false;
      var cell=new RoadRaster.Cell(p.getX(),p.getY(),p.getZ());
      for(var e:road.prepareCollision().structureColumn(p.getX(),p.getZ(),RoadsideBypass::walkway).entrySet())
        for(var box:e.getValue()){
          double top=e.getKey().y()+box.y1();
          if(p.getY()+1>top+1e-7&&p.getY()<top+2.1)return false;
        }
      if(road.prepareCollision().structureAt(cell,q->q.material()==RoadStructures.Material.TUNNEL||q.material()==RoadStructures.Material.CONCRETE||q.material()==RoadStructures.Material.DEFAULT))supported=true;
      // A sub-voxel deck edge in a lower support cell is equally fillable.
      if(road.column(p)!=null&&p.getY()+1<=road.column(p).minTop()+1e-7)supported=true;
    }
    return supported;
  }
  private TunnelTerrainSpace(){}
}
