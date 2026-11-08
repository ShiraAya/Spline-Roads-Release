package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.world.level.ChunkPos;

/** Existing tunnel excavation is air, but natural soil above its saved ceiling
 * still counts as cover. An ordinary bridge has no such ceiling reservation. */
public final class RoadTerrainCover {
  private final LongFunction<List<RoadIndex.Built>> lookup;
  private final Map<Long,List<RoadIndex.Built>> chunks=new HashMap<>();
  private final Map<RoadIndex.Built,List<RoadAutoTunnels.RoofRegion>> profiles=new IdentityHashMap<>();
  public RoadTerrainCover(LongFunction<List<RoadIndex.Built>> lookup){this.lookup=lookup;}
  public double surface(double x,double z,double roadY,DoubleUnaryOperator original){
    double result=RoadTerrainSurface.connected(roadY,original);
    long chunk=ChunkPos.asLong((int)Math.floor(x)>>4,(int)Math.floor(z)>>4);
    for(var road:chunks.computeIfAbsent(chunk,c->lookup.apply(c).stream().filter(r->r.mesh.settings().structure()==Structure.TUNNEL
        ||!profiles.computeIfAbsent(r,v->RoadAutoTunnels.roofRegions(v.record.structures())).isEmpty()).toList())){
      var q=RoadQueries.horizontal(road.mesh,new V(x,roadY,z));var at=q.sample();
      if(q.horizontalDistance()>at.halfWidth()+RoadAutoTunnels.LINING||roadY<at.center().y()-.25)continue;
      double ceiling=Double.NaN;
      if(road.mesh.settings().structure()==Structure.TUNNEL)
        ceiling=RoadInfrastructure.ceiling(road.mesh.settings().options().infrastructure(),at.halfWidth(),0);
      else for(var r:profiles.computeIfAbsent(road,v->RoadAutoTunnels.roofRegions(v.record.structures())))
        if(at.distance()>=r.from()-1e-7&&at.distance()<=r.to()+1e-7){ceiling=r.first()+(r.last()-r.first())*(at.distance()-r.from())/(r.to()-r.from());break;}
      if(!Double.isFinite(ceiling)||roadY>at.center().y()+ceiling)continue;
      // The ceiling cell itself contains road collision and may retain only a
      // partial visible fill. Require real soil in the next untouched cell.
      double probe=Math.ceil(at.center().y()+ceiling+RoadAutoTunnels.ROOF_THICKNESS)+.0001;
      double solid=original.applyAsDouble(probe);
      if(!Double.isFinite(solid)||solid<=probe)continue;
      double top=RoadTerrainSurface.connected(probe,original);
      if(Double.isFinite(top))result=Double.isFinite(result)?Math.max(result,top):top;
    }
    return result;
  }
}
