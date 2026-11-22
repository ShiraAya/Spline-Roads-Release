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
  private final boolean retainedWalls;
  private final Map<Long,List<RoadIndex.Built>> chunks=new HashMap<>();
  private final Map<RoadIndex.Built,List<RoadAutoTunnels.RoofRegion>> profiles=new IdentityHashMap<>();
  private record Wall(double to,double first,double last){}
  private record Walls(NavigableMap<Double,Wall> left,NavigableMap<Double,Wall> right){
    boolean empty(){return left.isEmpty()&&right.isEmpty();}
  }
  private final Map<RoadIndex.Built,Walls> walls=new IdentityHashMap<>();
  private Walls walls(RoadIndex.Built road){return walls.computeIfAbsent(road,r->{
    var left=new TreeMap<Double,Wall>();var right=new TreeMap<Double,Wall>();
    for(var p:r.record.structures())if(p.material()==RoadStructures.Material.TUNNEL&&p.model().startsWith("sr:auto_cut:")&&p.width()>1){
      var a=RoadQueries.horizontal(r.mesh,p.a());var b=RoadQueries.horizontal(r.mesh,p.b());
      double from=a.sample().distance(),to=b.sample().distance();if(to<=from)continue;
      (a.lateral()>0?left:right).put(from,new Wall(to,p.a().y()+p.height()-RoadAutoTunnels.WALL_LIP,p.b().y()+p.height()-RoadAutoTunnels.WALL_LIP));
    }return new Walls(left,right);
  });}
  public RoadTerrainCover(LongFunction<List<RoadIndex.Built>> lookup){this(lookup,false);}
  public RoadTerrainCover(LongFunction<List<RoadIndex.Built>> lookup,boolean retainedWalls){this.lookup=lookup;this.retainedWalls=retainedWalls;}
  public double surface(double x,double z,double roadY,DoubleUnaryOperator original){
    double result=RoadTerrainSurface.connected(roadY,original);
    long chunk=ChunkPos.asLong((int)Math.floor(x)>>4,(int)Math.floor(z)>>4);
    for(var road:chunks.computeIfAbsent(chunk,c->lookup.apply(c).stream().filter(r->r.mesh.settings().structure()==Structure.TUNNEL
        ||!profiles.computeIfAbsent(r,v->RoadAutoTunnels.roofRegions(v.record.structures())).isEmpty()
        ||retainedWalls&&!walls(r).empty()).toList())){
      var q=RoadQueries.horizontal(road.mesh,new V(x,roadY,z));var at=q.sample();
      if(q.horizontalDistance()>at.halfWidth()+RoadAutoTunnels.LINING||roadY<at.center().y()-.25)continue;
      // Open cuts have no roof to probe above. Their saved retaining wall is an
      // excavation receipt, so previewing the cleared trench must not classify
      // its empty interior as a newly exposed ground road.
      var profile=retainedWalls?walls(road):new Walls(Collections.emptyNavigableMap(),Collections.emptyNavigableMap());
      for(var side:Math.abs(q.lateral())<=.1?List.of(profile.left(),profile.right()):List.of(q.lateral()>0?profile.left():profile.right())){
        var entry=side.floorEntry(at.distance()+1e-6);if(entry==null||at.distance()>entry.getValue().to()+1e-6)continue;
        var wall=entry.getValue();double t=Math.max(0,Math.min(1,(at.distance()-entry.getKey())/(wall.to()-entry.getKey())));
        double soil=wall.first()*(1-t)+wall.last()*t;
        if(soil<roadY+RoadAutoTunnels.MIN_CUT_DEPTH-1e-7)continue;
        result=Double.isFinite(result)?Math.max(result,soil):soil;
      }
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
