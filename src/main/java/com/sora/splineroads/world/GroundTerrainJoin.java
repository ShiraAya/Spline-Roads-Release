package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Keep the original terrain in a partially cut edge voxel, capped BELOW every
 * local road surface. Neither the deck nor its paint/width is extended. */
final class GroundTerrainJoin {
 // Generated edge fill below a quarter block is a loose paper-thin sheet.
 static final double MIN_CAP=.25;
 // Footway edge caps may be thin; the fill codec supports positive caps down to .01.
 static boolean usable(double cap){return cap>=.01&&cap<1;}
 static double cap(BlockPos p,BlockState original,List<RoadIndex.Built> nearby){
  if(TunnelAir.isAirSpace(original)||original.hasBlockEntity()||!original.getFluidState().isEmpty()||!original.canOcclude())return 1;
  if(RailbedTerrainEdge.present(p,original,nearby))return 1;
  boolean edge=false,footway=false;double cap=1;
  V center=new V(p.getX()+.5,p.getY(),p.getZ()+.5);
  for(var road:nearby){
   if(road.tunnelAt(p)||road.cycleBodyAt(p))return 1;
   var c=road.column(p);if(c!=null&&p.getY()>=c.maxTop()&&road.clearanceAt(p))return 1;
   // A graded continuous footway can occupy only part of the terrain cube.
   // Its top is independent of the asphalt column; keep original soil below
   // the lowest local footway top instead of discarding the whole cube.
   var q=RoadQueries.horizontal(road.mesh,center);var settings=road.record.settings();
   int side=q.lateral()<0?-1:1;
   if(settings.structure()!=Structure.BRIDGE&&settings.structure()!=Structure.TUNNEL
       &&!RoadStreetscape.raised(road.mesh,q.sample(),side)&&!RoadStreetscape.cut(road.mesh,q.sample(),side)){
    var cell=new RoadRaster.Cell(p.getX(),p.getY(),p.getZ());
    double top=road.prepareCollision().minimumStructureTop(cell,part->part.material().name().startsWith("WALK_"))-p.getY();
    if(top<1-1e-7){cap=Math.min(cap,top-.002);edge=true;footway=true;}
   }
   // Integer-height asphalt edge infill also uses the chunk mesh, not hundreds
   // of per-frame block-entity draws. The standard 2 mm cap avoids coplanar faces.
   if(c==null||p.getY()>c.maxTop()||p.getY()+1<c.minTop()-1e-7)continue;
   cap=Math.min(cap,c.minTop()-p.getY()-.002);
   if(settings.structure()==Structure.BRIDGE||settings.structure()==Structure.TUNNEL||RoadStreetscape.raised(road.mesh,q.sample(),side)||RoadStreetscape.cut(road.mesh,q.sample(),side))continue;
   var walk=settings.options().sidewalk();var ref=road.record.junction();if(ref!=null&&ref.get().arm()>=0)walk=ref.spec().arms().get(ref.get().arm()).external().options().sidewalk();
   if(walk.enabled()&&(walk.side()==RoadSidewalks.Side.BOTH||walk.side()==RoadSidewalks.Side.LEFT&&q.lateral()<0||walk.side()==RoadSidewalks.Side.RIGHT&&q.lateral()>0))continue;
   boolean end=!road.mesh.closed()&&(q.sample().distance()<1||q.sample().distance()>road.mesh.length()-1);
   if(!end&&Math.abs(q.lateral())<q.sample().halfWidth()-.72)continue;
   for(int x=0;x<=1;x++)for(int z=0;z<=1;z++){
    V point=new V(p.getX()+x,c.minTop(),p.getZ()+z);var corner=RoadQueries.horizontal(road.mesh,point);
    double along=point.sub(corner.sample().center()).dot(corner.tangent().horizontalUnit());
    if(corner.horizontalDistance()>corner.sample().halfWidth()+1e-7
        ||!road.mesh.closed()&&(corner.sample().distance()<1e-6&&along< -1e-7
          ||corner.sample().distance()>road.mesh.length()-1e-6&&along>1e-7))edge=true;
   }
  }
  // Flush grass, stone and other built-in infill materials already render
  // through the ordinary collider. Only unsupported materials need a fill entity.
  if(edge&&cap>=.998-1e-7&&RoadBlocks.Fill.of(original)!=RoadBlocks.Fill.NONE)return 1;
  return edge&&cap<.999?(cap>=(footway?.01:MIN_CAP)?cap:0):1;
 }
 private GroundTerrainJoin(){}
}
