package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Terrain walls follow the actual curved pad boundary, with one saved owner. */
public final class JunctionRetainingWalls {
 public static final String MODEL="sr:junction_retaining_wall";
 private record End(int arm,int side,V normal,double walk,double cap){}
 public static List<Part> plan(JunctionSpec spec,List<Mesh> approaches,List<V> boundary,Ground ground){
  if(spec.kind()!=JunctionSpec.Kind.INTERSECTION||boundary.size()<3||spec.arms().stream().noneMatch(a->a.external().structure()==Structure.AUTO||a.external().structure()==Structure.TUNNEL))return List.of();
  var shells=new ArrayList<List<Part>>();
  for(int i=0;i<approaches.size();i++){
   var raw=approaches.get(i);var settings=spec.arms().get(i).external();
   var proxy=new Mesh(raw.samples(),settings,raw.min(),raw.max(),raw.length(),raw.closed());
   shells.add(settings.structure()==Structure.TUNNEL?RoadInfrastructure.tunnelShell(proxy):RoadAutoTunnels.enclose(proxy,ground,List.of()));
  }
  int n=boundary.size(),start=-1;boolean[] open=new boolean[n];double sign=JunctionPaint.area(boundary)>=0?-1:1;
  for(int i=0;i<n;i++){var a=boundary.get(i);var b=boundary.get((i+1)%n);var mid=a.add(b).mul(.5);open[i]=a.distance(b)>1e-8&&approaches.stream().noneMatch(m->RoadQueries.contains(m,mid,.02,.1));if(!open[i])start=i;}
  if(start<0)return List.of();var out=new ArrayList<Part>();var run=new ArrayList<V>();
  for(int k=1;k<=n;k++){int i=(start+k)%n;if(open[i]){if(run.isEmpty())run.add(boundary.get(i));run.add(boundary.get((i+1)%n));}else if(!run.isEmpty()){corner(out,spec,approaches,shells,run,sign,ground);run.clear();}}
  if(!run.isEmpty())corner(out,spec,approaches,shells,run,sign,ground);return List.copyOf(out);
 }
 private static End end(JunctionSpec spec,List<Mesh> approaches,List<List<Part>> shells,V point){
  int arm=0,side=1;double best=Double.POSITIVE_INFINITY;
  for(int i=0;i<approaches.size();i++)for(int s:new int[]{-1,1}){var at=approaches.get(i).last();double d=at.at(s*at.halfWidth(),0).distance(point);if(d<best){best=d;arm=i;side=s;}}
  var mesh=approaches.get(arm);var mouth=mesh.last();double cap=point.y();
  for(var p:shells.get(arm))if(p.material()==Material.TUNNEL){
   var a=RoadQueries.horizontal(mesh,p.a());var b=RoadQueries.horizontal(mesh,p.b());
   if(Math.max(a.sample().distance(),b.sample().distance())<mesh.length()-.05||(a.lateral()+b.lateral())*.5*side<mouth.halfWidth()-.2)continue;
   for(var v:p.top())if(RoadQueries.horizontal(mesh,v).sample().distance()>mesh.length()-.05)cap=Math.max(cap,v.y());
  }
  var walk=spec.arms().get(arm).external().options().sidewalk();double width=walk.enabled()&&(walk.side()==RoadSidewalks.Side.BOTH||walk.side()==RoadSidewalks.Side.LEFT&&side<0||walk.side()==RoadSidewalks.Side.RIGHT&&side>0)?walk.width():0;
  return new End(arm,side,mouth.left().mul(side),width,cap);
 }
 private static void corner(List<Part> out,JunctionSpec spec,List<Mesh> approaches,List<List<Part>> shells,List<V> raw,double sign,Ground ground){
  var edge=RoadSidewalks.closeCorner(spec,approaches,raw);if(edge.size()<2)return;
  var first=end(spec,approaches,shells,edge.get(0));var last=end(spec,approaches,shells,edge.get(edge.size()-1));
  var modeA=spec.arms().get(first.arm()).external().structure();var modeB=spec.arms().get(last.arm()).external().structure();
  if(modeA!=Structure.AUTO&&modeA!=Structure.TUNNEL&&modeB!=Structure.AUTO&&modeB!=Structure.TUNNEL)return;
  int n=edge.size();double[] d=new double[n],tops=new double[n],walks=new double[n];var normals=new ArrayList<V>();
  for(int i=1;i<n;i++)d[i]=d[i-1]+edge.get(i).distance(edge.get(i-1));
  for(int i=0;i<n;i++){
   V p=edge.get(i),normal;
   if(i==0)normal=first.normal();else if(i==n-1)normal=last.normal();else{var a=p.sub(edge.get(i-1)).horizontalUnit().left().mul(sign);var b=edge.get(i+1).sub(p).horizontalUnit().left().mul(sign);normal=a.add(b).mul(1/Math.max(.5,1+a.dot(b)));}
   normals.add(normal);double t=Settings.smooth(d[i]/d[n-1]);walks[i]=first.walk()+(last.walk()-first.walk())*t;
   double terrain=Double.NEGATIVE_INFINITY;
   for(double offset:new double[]{0,.75,1.55}){var q=p.add(normal.mul(walks[i]+offset));double y=ground.surface(q.x(),q.z(),p.y());if(Double.isFinite(y))terrain=Math.max(terrain,y);}
   tops[i]=terrain>=p.y()+2-1e-7?Math.min(p.y()+RoadAutoTunnels.HEADROOM,terrain+RoadAutoTunnels.WALL_LIP):p.y();
  }
  tops[0]=first.cap();tops[n-1]=last.cap();
  for(int i=1;i<n;i++){
   V a=edge.get(i-1),b=edge.get(i);double ha=tops[i-1]-a.y(),hb=tops[i]-b.y();if(Math.max(ha,hb)<.1)continue;
   // A short taper meets surface ground; adjacent segments share exact cap vertices.
   ha=Math.max(.15,ha)+spec.thickness();hb=Math.max(.15,hb)+spec.thickness();
   var na=normals.get(i-1);var nb=normals.get(i);V down=new V(0,-spec.thickness(),0);
   out.add(new Part(a.add(na.mul(walks[i-1]+.8)).add(down),b.add(nb.mul(walks[i]+.8)).add(down),1.6,Math.max(ha,hb),false,Material.TUNNEL,na.mul(.8),nb.mul(.8),MODEL,new Heights(ha,ha,hb,hb)));
  }
 }
 private JunctionRetainingWalls(){}
}
