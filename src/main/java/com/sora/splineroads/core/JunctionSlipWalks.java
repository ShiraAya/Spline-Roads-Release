package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** A corner has one outer trunk and one refuge connection, never two overlapping trunks. */
final class JunctionSlipWalks {
 static boolean affected(JunctionSpec s,List<Mesh> approaches,List<JunctionSlips.Slip> slips,Part p){V at=p.a().add(p.b()).mul(.5);
  for(var slip:slips){var a=RoadQueries.horizontal(approaches.get(slip.from()),at);var b=RoadQueries.horizontal(approaches.get(slip.to()),at);
   if(a.lateral()>s.arms().get(slip.from()).motorEdge(true,false)&&b.lateral()<s.arms().get(slip.to()).motorEdge(false,false)+s.arms().get(slip.to()).laneWidth()+1)return true;
  }return false;
 }
 static void add(JunctionSpec s,List<Mesh> approaches,JunctionSlipProfiles profiles,JunctionSlips.Slip slip,RoadSidewalks.Config walk,V inner,V outer,List<Part> parts,List<Part> island,boolean exact,boolean smoothEdges){
  double offset=RoadStreetscape.tactileOffset(walk.width());var route=new ArrayList<V>();
  var a=approaches.get(slip.from());var b=approaches.get(slip.to());
  double startWidth=exact?JunctionSlips.walkSpan(s.arms().get(slip.from())):walk.width(),endWidth=exact?JunctionSlips.walkSpan(s.arms().get(slip.to())):walk.width();
  if(!exact||startWidth>=2)for(var p:a.samples())if(p.distance()<slip.departure())route.add(profiles.map(slip.from(),p.at(p.halfWidth()+RoadStreetscape.tactileOffset(startWidth),0)));
  for(var p:slip.mesh().samples()){double width=exact?JunctionSlips.walkWidth(s,slip,p.distance(),smoothEdges):walk.width();if(!exact||width>=2)route.add(p.at(p.halfWidth()+RoadStreetscape.tactileOffset(width),0));}
  var tail=new ArrayList<V>();if(!exact||endWidth>=2)for(var p:b.samples())if(p.distance()<slip.arrival())tail.add(profiles.map(slip.to(),p.at(-p.halfWidth()-RoadStreetscape.tactileOffset(endWidth),0)));Collections.reverse(tail);route.addAll(tail);
  parts.addAll(TactilePaths.parts(route));var mid=RoadStructures.sample(slip.mesh(),exact?slip.turnEnd()*.5:slip.mesh().length()*.5);double midOffset=exact?RoadStreetscape.tactileOffset(JunctionSlips.walkWidth(s,slip,mid.distance(),smoothEdges)):offset;parts.addAll(TactilePaths.parts(List.of(outer,mid.at(mid.halfWidth()+midOffset,0))));
  var ends=new ArrayList<V>();ends.add(inner);
  for(int arm:new int[]{slip.from(),slip.to()}){var config=s.arms().get(arm);if(!config.crosswalk())continue;var mesh=approaches.get(arm);var p=RoadStructures.sample(mesh,mesh.length()-config.crossingSetback()-config.crossingWidth()/2);int side=arm==slip.from()?1:-1;
   double edge=side>0?config.motorEdge(true,false):config.motorEdge(false,false)+profiles.reservation(arm,p.distance());
   V end=p.at(edge+side*.45,0);ends.add(end);warning(island,end,p.left());
  }
  // Search a junction point on the actual refuge, so skewed roads cannot send a
  // blind branch across the vehicle lane or onto the other side of the corner.
  var paving=island.stream().filter(p->p.material().name().startsWith("WALK_")).map(Part::base).toList();var coverage=new Coverage(paving);V fork=null;double best=Double.POSITIVE_INFINITY;
  for(var poly:paving){V c=new V(0,0,0);for(var v:poly)c=c.add(v);c=c.mul(1.0/poly.size());double score=0;boolean safe=true;for(var e:ends){score+=e.distance(c);if(!covered(coverage,e,c)){safe=false;break;}}if(safe&&score<best){best=score;fork=c;}}
  if(fork!=null){for(var e:ends)island.addAll(TactilePaths.parts(List.of(fork,e)));return;}
  // Acute junctions can produce a concave refuge. Use a supported bend for
  // each crossing instead of forcing all three branches through one star point.
  for(int i=1;i<ends.size();i++){
   V start=ends.get(i),finish=ends.get(0);List<V> routeInIsland=null;double length=Double.POSITIVE_INFINITY;
   if(covered(coverage,start,finish))routeInIsland=List.of(start,finish);
   else for(var poly:paving){V c=new V(0,0,0);for(var v:poly)c=c.add(v);c=c.mul(1.0/poly.size());double d=start.distance(c)+c.distance(finish);if(d<length&&covered(coverage,start,c)&&covered(coverage,c,finish)){length=d;routeInIsland=List.of(start,c,finish);}}
   if(routeInIsland==null)throw new IllegalArgumentException("独立右转行人岛无法容纳连续盲道，请增大转角半径");
   island.addAll(TactilePaths.parts(routeInIsland));
  }
 }
 /** One local index per refuge. The containment test and candidate order stay exact. */
 private static final class Coverage {
  private final Map<Long,List<List<V>>> cells=new HashMap<>();
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  Coverage(List<List<V>> polygons){for(var p:polygons){double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=-minX,maxZ=-minX;for(V v:p){minX=Math.min(minX,v.x());maxX=Math.max(maxX,v.x());minZ=Math.min(minZ,v.z());maxZ=Math.max(maxZ,v.z());}
   for(int x=(int)Math.floor(minX/4);x<=(int)Math.floor(maxX/4);x++)for(int z=(int)Math.floor(minZ/4);z<=(int)Math.floor(maxZ/4);z++)cells.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(p);
  }}
  boolean contains(V point){for(var p:cells.getOrDefault(key((int)Math.floor(point.x()/4),(int)Math.floor(point.z()/4)),List.of()))if(JunctionPaint.inside(p,point))return true;return false;}
 }
 private static boolean covered(Coverage paving,V a,V b){int steps=Math.max(1,(int)Math.ceil(a.distance(b)*4));V normal=b.sub(a).horizontalUnit().left();for(int k=0;k<=steps;k++)for(double side:new double[]{-.31,0,.31}){V p=a.mul(1-k/(double)steps).add(b.mul(k/(double)steps)).add(normal.mul(side));if(!paving.contains(p))return false;}return true;}
 private static void warning(List<Part> out,V p,V axis){out.add(new Part(p.sub(axis.mul(.4)).add(new V(0,.2,0)),p.add(axis.mul(.4)).add(new V(0,.2,0)),.8,.02,false,Material.TACTILE));}
}
