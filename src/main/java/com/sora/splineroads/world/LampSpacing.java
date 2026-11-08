package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Prune complete lamp assemblies at road/approach seams, preserving other layers and rows. */
final class LampSpacing {
  private record Lamp(UUID road,V foot,V left,double gap,boolean existing,boolean junction){}
  private static List<Lamp> lamps(RoadIndex.Built road,boolean existing){
    return road.record.structures().stream().map(RoadStreetscape::lampFoot).filter(Objects::nonNull).distinct()
        .map(p->new Lamp(road.record.id(),p,RoadQueries.horizontal(road.mesh,p).sample().left(),Math.min(12,road.mesh.settings().options().streetscape().lampSpacing()*.45),existing,road.record.junction()!=null)).toList();
  }
  static void reconcile(RoadData data,List<RoadIndex.Built> built,Set<UUID> removed){
    var kept=new HashMap<Long,List<Lamp>>();for(var r:data.index.roads.values())if(!removed.contains(r.record.id()))for(var lamp:lamps(r,true))add(kept,lamp);
    var candidates=new ArrayList<Lamp>();for(var r:built)candidates.addAll(lamps(r,false));
    candidates.sort(Comparator.comparing(Lamp::junction).thenComparing(Lamp::road).thenComparingDouble(l->l.foot().x()).thenComparingDouble(l->l.foot().z()).thenComparingDouble(l->l.foot().y()));
    var hidden=new HashMap<UUID,Set<V>>();
    for(var lamp:candidates){
      boolean close=false;int x=cell(lamp.foot().x()),z=cell(lamp.foot().z());
      for(int dx=-1;dx<=1&&!close;dx++)for(int dz=-1;dz<=1&&!close;dz++)for(var other:kept.getOrDefault(key(x+dx,z+dz),List.of()))if(close(lamp,other)){close=true;break;}
      if(close)hidden.computeIfAbsent(lamp.road(),k->new HashSet<>()).add(lamp.foot());else add(kept,lamp);
    }
    for(int i=0;i<built.size();i++){var road=built.get(i);var feet=hidden.get(road.record.id());if(feet==null)continue;
      built.set(i,road.structures(road.record.structures().stream().filter(p->!feet.contains(RoadStreetscape.lampFoot(p))).toList()));
    }
  }
  private static int cell(double n){return (int)Math.floor(n/12);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private static void add(Map<Long,List<Lamp>> cells,Lamp lamp){cells.computeIfAbsent(key(cell(lamp.foot().x()),cell(lamp.foot().z())),k->new ArrayList<>()).add(lamp);}
  private static boolean close(Lamp a,Lamp b){
    var delta=a.foot().sub(b.foot());double distance=delta.horizontalLength();
    if(Math.abs(delta.y())>2||distance>=Math.min(a.gap(),b.gap()))return false;
    return distance<1||Math.abs(a.left().dot(b.left()))>.94&&Math.abs(delta.dot(a.left()))<2&&Math.abs(delta.dot(b.left()))<2;
  }
  private LampSpacing(){}
}
