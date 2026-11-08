package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Voxel paving outside the unchanged road ribbon; never a wider drivable mesh. */
public final class RoadAprons {
 public record Cell(int x,int z,double top){}
 public record JunctionCell(Cell cell,int arm){}
 /** Continue the selected approach apron around the exposed junction boundary. */
 public static List<JunctionCell> junctionCells(JunctionPlanner.Ref ref,java.util.function.Predicate<Sample> grounded){
  var edge=ref.boundary();var out=new LinkedHashMap<Long,JunctionCell>();double area=0;
  for(int i=0;i<edge.size();i++){var a=edge.get(i);var b=edge.get((i+1)%edge.size());area+=a.x()*b.z()-b.x()*a.z();}
  double sign=area>=0?-1:1;
  for(int i=0;i<edge.size();i++){
   var a=edge.get(i);var b=edge.get((i+1)%edge.size());var mid=a.add(b).mul(.5);if(a.distance(b)<1e-7)continue;
   int chosen=-1;double best=Double.POSITIVE_INFINITY;boolean mouth=false;
   for(int j=0;j<ref.spec().arms().size();j++){var mesh=ref.approach(j);if(RoadQueries.contains(mesh,mid,.02,.1)){mouth=true;break;}
    var at=mesh.last();double d=Math.min(mid.distance(at.at(at.halfWidth(),0)),mid.distance(at.at(-at.halfWidth(),0)));if(d<best){best=d;chosen=j;}}
   if(mouth||chosen<0)continue;var external=ref.spec().arms().get(chosen).external();
   if(!eligible(new Mesh(List.of(),external,a,b,0,false)))continue;
   var normal=b.sub(a).horizontalUnit().left().mul(sign);var inside=normal.mul(-1);
   if(!grounded.test(new Sample(a,inside,0,1))||!grounded.test(new Sample(b,inside,a.distance(b),1)))continue;
   final int arm=chosen;
   RoadSidewalks.raster(List.of(a.sub(normal.mul(.72)),a.add(normal),b.add(normal),b.sub(normal.mul(.72))),a,b,c->{
    var p=new V(c.x()+.5,a.y(),c.z()+.5);if(p.sub(mid).dot(normal)>1+1e-7)return;
    long key=((long)c.x()<<32)^(c.z()&0xffffffffL);out.putIfAbsent(key,new JunctionCell(new Cell(c.x(),c.z(),mid.y()),arm));
   });
  }return List.copyOf(out.values());
 }
 public static boolean eligible(Mesh mesh){return mesh.settings().options().streetscape().apron()!=RoadStreetscape.Apron.NONE&&mesh.settings().structure()!=Structure.BRIDGE&&mesh.settings().structure()!=Structure.TUNNEL&&!mesh.settings().options().sidewalk().enabled();}
 public static List<Cell> cells(Mesh mesh){return cells(mesh,s->true);}
 public static List<Cell> cells(Mesh mesh,java.util.function.Predicate<Sample> grounded){
  return cells(mesh,(sample,side)->grounded.test(sample));
 }
 public static List<Cell> cells(Mesh mesh,java.util.function.BiPredicate<Sample,Integer> grounded){
  if(!eligible(mesh))return List.of();var result=new LinkedHashMap<Long,Cell>();var samples=mesh.samples();
  for(int i=1;i<samples.size();i++){
   var a=samples.get(i-1);var b=samples.get(i);if(RoadStreetscape.underground(mesh,(a.distance()+b.distance())/2))continue;
   for(int side:new int[]{-1,1}){
    if(RoadStreetscape.raised(mesh,a,side)||RoadStreetscape.raised(mesh,b,side)||RoadStreetscape.cut(mesh,a,side)||RoadStreetscape.cut(mesh,b,side))continue;
    if(!grounded.test(a,side)||!grounded.test(b,side))continue;
    var band=List.of(a.at(side*Math.max(0,a.halfWidth()-.72),0),a.at(side*(a.halfWidth()+1),0),b.at(side*(b.halfWidth()+1),0),b.at(side*Math.max(0,b.halfWidth()-.72),0));
    RoadSidewalks.raster(band,a.center(),b.center(),cell->{
     V p=new V(cell.x()+.5,0,cell.z()+.5);var first=mesh.first();var last=mesh.last();
     if(!mesh.closed()&&(a.distance()<1.5&&p.sub(first.center()).dot(first.left().left().mul(-1))<0||b.distance()>mesh.length()-1.5&&p.sub(last.center()).dot(last.left().left().mul(-1))>0))return;
     V d=b.center().sub(a.center());double t=Math.max(0,Math.min(1,p.sub(a.center()).dot(new V(d.x(),0,d.z()))/Math.max(1e-9,d.x()*d.x()+d.z()*d.z())));
     // Select voxel centres within one block, rather than every cell merely
     // touched by the outer polygon (which produced a second outside row).
     V center=a.center().mul(1-t).add(b.center().mul(t));V left=a.left().mul(1-t).add(b.left().mul(t)).horizontalUnit();
     if(Math.abs(p.sub(center).dot(left))>a.halfWidth()*(1-t)+b.halfWidth()*t+1+1e-7)return;
     double top=a.center().y()+d.y()*t;long key=((long)cell.x()<<32)^(cell.z()&0xffffffffL);var old=result.get(key);
     if(old==null||top<old.top())result.put(key,new Cell(cell.x(),cell.z(),top));
    });
   }
  }return List.copyOf(result.values());
 }
 private RoadAprons(){}
}
