package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.JunctionSpec.*;
import com.sora.splineroads.core.JunctionPlanner.*;
import com.sora.splineroads.core.RoadSurface.Face;
import java.util.*;
import static com.sora.splineroads.core.JunctionSpec.RIGHT;

/** Near-side right turn lanes. Each incoming arm owns its road and refuge island. */
public final class JunctionSlips48 {
 public record Slip(int from,int to,Mesh mesh,Mesh motor,List<V> island,double departure,double arrival,double cycleWidth) {}
 public static boolean enabled(JunctionSpec s){return s.arms().stream().anyMatch(Arm::rightSlip);}
 private static V outward(JunctionSpec s,int arm){return s.arms().get(arm).endpoint().position().sub(s.center()).horizontalUnit();}
 public static int target(JunctionSpec s,int from){
  int result=-1;double best=2;
  for(int j=0;j<s.arms().size();j++)if(j!=from&&s.arms().get(j).outgoing()>0&&JunctionPlanner.turn(s,from,j)==RIGHT){double score=Math.abs(outward(s,from).dot(outward(s,j)));if(score<best){best=score;result=j;}}
  return result;
 }
 public static String unavailable(JunctionSpec s,int from){
  int n=s.arms().size();if(s.kind()!=Kind.INTERSECTION||n!=3&&n!=4||s.parallelApproaches())return "仅适用于十字或丁字路口";
  if(s.leftTraffic())return "靠左通行的右转须穿越对向车道，不能设置外侧独立右转";
  if(s.arms().get(from).incoming()==0)return "此方向没有驶入车道";
  int opposite=0;for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(outward(s,i).dot(outward(s,j))<-.94)opposite++;
  if(opposite<(n==3?1:2))return "需要具有相对主路的十字或丁字布局";
  int to=target(s,from);if(to<0)return "此方向没有右转出口";
  double angle=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,outward(s,from).dot(outward(s,to))))));
  if(angle<60-1e-6||angle>120+1e-6)return "右转相邻道路夹角需为 60–120°";
  for(int i:new int[]{from,to}){var a=s.arms().get(i);if(a.external().structure()==Structure.TUNNEL)return "隧道路口不能设置外侧独立右转";if(Math.abs(a.endpoint().position().y()-s.center().y())>.1||Math.abs(a.endpoint().grade())>.001)return "独立右转接入口需要与路口齐平";}
  return "";
 }
 public static String islandLabel(Arm a){return a.external().options().sidewalk().enabled()?RoadSidewalks.Finish.of(a.external().options().sidewalk().material()).label:a.cycleWidth()>.01?"石砖":a.median()>0&&a.medianKind()==RoadProfile.Median.GREEN?"绿化带":"导流线";}
 private static double run(JunctionSpec s,int from,int to){var a=s.arms().get(from);var b=s.arms().get(to);return Math.max(36,s.cornerRadius()*2+Math.max(a.cycleWidth()+a.curbWidth(),b.cycleWidth()+b.curbWidth())+Math.max(a.laneWidth(),b.laneWidth())+18);}
 public static double approachLength(JunctionSpec s,int arm){double length=0;for(int i=0;i<s.arms().size();i++)if(s.arms().get(i).rightSlip()){int to=target(s,i);if(to>=0&&(i==arm||to==arm))length=Math.max(length,run(s,i,to)+14);}return length;}
 private static V curve(V a,V b,V c,V d,double t){double u=1-t;return a.mul(u*u*u).add(b.mul(3*u*u*t)).add(c.mul(3*u*t*t)).add(d.mul(t*t*t));}
 public static List<Slip> layout(JunctionSpec s,List<Mesh> approaches){
  var result=new ArrayList<Slip>();
  for(int i=0;i<s.arms().size();i++)if(s.arms().get(i).rightSlip()){
   String reason=unavailable(s,i);if(!reason.isEmpty())throw new IllegalArgumentException("接入口 "+(i+1)+"："+reason);
   int j=target(s,i);var a=s.arms().get(i);var b=s.arms().get(j);var ma=approaches.get(i);var mb=approaches.get(j);double run=run(s,i,j);
   if(Math.min(ma.length(),mb.length())<run+12)throw new IllegalArgumentException("独立右转接路过短，请将相邻端点各延长到至少 "+(int)Math.ceil(JunctionPlanner.radius(s)+run+12)+" 格外");
   double da=ma.length()-run,db=mb.length()-run;var sa=RoadStructures.sample(ma,da);var sb=RoadStructures.sample(mb,db);
   V p=sa.at(a.laneCenter(true,a.incoming()-1,false),0),q=sb.at(b.laneCenter(false,b.outgoing()-1,false),0);
   V f=sa.left().left().mul(-1),g=sb.left().left();double handle=p.sub(q).horizontalLength()*.35;
   V c1=p.add(f.mul(handle)),c2=q.sub(g.mul(handle));int steps=Math.max(32,(int)Math.ceil(p.distance(q)*2));var samples=new ArrayList<Sample>();
   double width=Math.max(4,Math.min(6,Math.max(a.laneWidth(),b.laneWidth())+1));
   for(int k=0;k<=steps;k++){double t=k/(double)steps;V at=curve(p,c1,c2,q,t),d=k==0?f:k==steps?g:curve(p,c1,c2,q,Math.min(1,t+.0001)).sub(curve(p,c1,c2,q,Math.max(0,t-.0001))).horizontalUnit();double edge=Math.min(1,Math.min(t,1-t)*6);double w=(a.laneWidth()*(1-t)+b.laneWidth()*t)*(1-edge)+width*edge;samples.add(new Sample(at,d.left(),0,w/2));}
   var motor=RoadRibbon.mesh(samples,JunctionPlanner.settings(width,s.thickness()));RoadRibbon.checkSelfIntersections(motor,4);
   if(RoadRibbon.minRadius(motor)<Math.max(6,width*1.5))throw new IllegalArgumentException("独立右转半径不足，请增大路口转角半径或延长接路");
   double cycle=a.cycleWidth()>.01&&b.cycleWidth()>.01?Math.min(a.cycleWidth(),b.cycleWidth())+.5:0;
   var mesh=cycle==0?motor:RoadRibbon.mesh(samples.stream().map(v->new Sample(v.at(cycle/2,0),v.left(),0,v.halfWidth()+cycle/2)).toList(),JunctionPlanner.settings(width+cycle,s.thickness()));
   var island=new ArrayList<V>();for(int k=0;k<samples.size();k+=4){var at=samples.get(k);island.add(at.at(-at.halfWidth()-.2,0));}var last=samples.get(samples.size()-1);island.add(last.at(-last.halfWidth()-.2,0));island.add(s.center());
   result.add(new Slip(i,j,mesh,motor,List.copyOf(island),da,db,cycle));
  }return List.copyOf(result);
 }
 /** Clip replaceable street furniture at the new lane; actual road/deck supports stay. */
 public static List<Part> clear(List<Part> parts,List<Mesh> slips){
  var walkClip=new SidewalkJoins(slips,List.of());var out=new ArrayList<Part>();
  for(var p:walkClip.clip(parts)){
   if(RoadSidewalks.smoothPart(p)){out.add(p);continue;}
   boolean blocked=false;for(var m:slips){
    if(Math.max(p.a().y(),p.b().y())+p.height()<m.min().y()+m.settings().thickness()-.02)continue;
    if(RoadClearance.structureInvades(p,m,4)){blocked=true;break;}
   }
   if(!blocked)out.add(p);
  }return List.copyOf(out);
 }
 public static List<Mesh> meshes(Plan plan){return plan.pieces().stream().filter(p->p.arm()<=-2).map(Piece::mesh).toList();}
 /** Raised refuges own their footprint. The asphalt depth bias otherwise covers
  * a 0.2-block footway when the camera is far away, despite correct solid faces. */
 public static RoadSurface.Geometry exposedSurface(RoadSurface.Geometry surface,List<Part> parts){
  var paving=parts.stream().filter(p->p.material().name().startsWith("WALK_")).map(Part::base).toList();
  if(paving.isEmpty())return surface;
  var mask=new RoadSurface.PaintMask(paving);var pavement=new ArrayList<Face>();
  for(var face:surface.pavement()){
   if(face.texture()!=RoadSurface.Texture.PLAIN||Math.abs(RoadLighting.normal(face).y())<.5){pavement.add(face);continue;}
   for(var polygon:mask.clip(face.points()))pavement.add(new Face(polygon,face.color(),face.emissive(),face.texture()));
  }
  return new RoadSurface.Geometry(List.copyOf(pavement),clipPaint(surface.markings(),mask));
 }
 public static Plan apply(JunctionSpec s,Plan base){
  if(!enabled(s))return base;
  var approaches=base.pieces().subList(0,s.arms().size()).stream().map(Piece::mesh).toList();var layout=layout(s,approaches);var lanes=layout.stream().map(Slip::mesh).toList();
  var laneMask=new RoadSurface.PaintMask(polygons(lanes));
  var islands=layout.stream().map(v->islandMesh(s,v)).toList();
  var pieces=new ArrayList<Piece>();for(var p:base.pieces()){
   var retained=clear(p.structures(),lanes);var paving=retained.stream().filter(v->v.material().name().startsWith("WALK_")).toList();
   var parts=new ArrayList<>(retained.stream().filter(v->!v.material().name().startsWith("WALK_")).toList());parts.addAll(new SidewalkJoins(islands,List.of()).clip(paving));
   pieces.add(new Piece(p.mesh(),clipPaint(p.paint(),laneMask),List.copyOf(parts),p.arm()));
  }
  var movements=new ArrayList<>(base.movements());var walks=new LinkedHashMap<>(base.sidewalks());walks.keySet().removeIf(c->lanes.stream().anyMatch(m->RoadQueries.contains(m,new V(c.x()+.5,c.y(),c.z()+.5),.1,.7)));
  var original=base.pieces().stream().map(Piece::mesh).toList();
  var oldRoadMask=new RoadSurface.PaintMask(polygons(original));
  for(var slip:layout){
   Arm arm=s.arms().get(slip.from());var road=slip.mesh();var paint=new ArrayList<Face>();var parts=new ArrayList<Part>();
   for(int side:new int[]{-1,1}){var path=road.samples().stream().map(v->v.at(side*(v.halfWidth()-.12),-.016)).toList();JunctionPaint.path(paint,path,.14,false,JunctionPaint.WHITE);}
   paint=new ArrayList<>(clipPaint(paint,oldRoadMask));
   var pavement=new ArrayList<Part>();for(var tri:JunctionPaint.triangulate(slip.island()))pavement.add(trianglePart(tri,Material.WALK_STONE_BRICKS,.2));
   pavement=new ArrayList<>(new SidewalkJoins(original,List.of()).clip(pavement));
   if(pavement.stream().mapToDouble(p->Math.abs(JunctionPaint.area(p.base()))).sum()<12)
    throw new IllegalArgumentException("独立右转岛区空间不足，请增大转角半径或调整相邻道路夹角");
   // One radial fan supplies the triangle's asphalt/structural base, with the road
   // surface union owning overlap with existing approaches and the central pad.
   var islandMesh=islandMesh(s,slip);
   boolean pedestrian=arm.external().options().sidewalk().enabled()||arm.cycleWidth()>.01;
   boolean green=!pedestrian&&arm.median()>0&&arm.medianKind()==RoadProfile.Median.GREEN;
   var islandParts=new ArrayList<Part>();var islandPaint=new ArrayList<Face>();
   for(var p:pavement){
    if(pedestrian)islandParts.add(material(p,arm.external().options().sidewalk().enabled()?RoadSidewalks.Finish.of(arm.external().options().sidewalk().material()).material():Material.WALK_STONE_BRICKS,.2));
    else if(green){islandParts.add(material(p,Material.SOIL,.25));var soil=material(p,Material.GREEN,.35);islandParts.add(new Part(soil.a().add(new V(0,.25,0)),soil.b().add(new V(0,.25,0)),soil.width(),soil.height(),false,soil.material(),soil.frameA(),soil.frameB()));}
    else hatch(islandPaint,p.base());
   }
   var config=arm.external().options().sidewalk();
   if(slip.cycleWidth()>0){
    double separation=RoadStreetscape.separatorWidth(arm.external().options());
    for(int k=1;k<road.samples().size();k++){var a=road.samples().get(k-1);var b=road.samples().get(k);double ia=a.halfWidth()-slip.cycleWidth(),ib=b.halfWidth()-slip.cycleWidth();
     JunctionPaint.line(paint,a.at(ia,-.016),b.at(ib,-.016),.13,JunctionPaint.WHITE);
     if(!arm.external().options().cycleAsphalt())paint.add(new Face(List.of(a.at(ia+separation+.55,-.009),a.at(a.halfWidth()-.08,-.009),b.at(b.halfWidth()-.08,-.009),b.at(ib+separation+.55,-.009)),0x536F61));
     double t=a.distance()/road.length();
     if(separation>0&&t>.2&&t<.8&&Math.abs(a.distance()-road.length()*.5)>3){
      var aa=a.at(ia+.5+separation/2,0);var bb=b.at(ib+.5+separation/2,0);
      var planter=new Part(aa,bb,separation,.25,false,Material.SOIL).frames(a.left().mul(separation/2),b.left().mul(separation/2));
      parts.add(planter);parts.add(new Part(aa.add(new V(0,.25,0)),bb.add(new V(0,.25,0)),separation-.2,.4,false,Material.GREEN).frames(a.left().mul((separation-.2)/2),b.left().mul((separation-.2)/2)));
     }
    }
   }
   if(pedestrian){
    var walk=config.enabled()?config:new RoadSidewalks.Config(true,RoadSidewalks.Side.RIGHT,3,"minecraft:stone_bricks");
    walk=walk.side(RoadSidewalks.Side.RIGHT);
    parts.addAll(new SidewalkJoins(original,pieces.stream().flatMap(p->p.structures().stream()).toList()).clip(RoadSidewalks.parts(road,walk)));
    // The crossing links the refuge to the outer footway. Both ends receive a
    // warning pad; no tactile strip is painted across the vehicle lane itself.
    double station=road.length()*.5;var at=RoadStructures.sample(road,station);V forward=at.left().left().mul(-1);
    for(double x=-at.halfWidth()+.35;x<at.halfWidth()-.2;x+=1.4)JunctionPaint.line(paint,at.at(x,-.018).sub(forward.mul(1.8)),at.at(x,-.018).add(forward.mul(1.8)),.7,JunctionPaint.WHITE);
    V inner=at.at(-at.halfWidth()-.6,0),outer=at.at(at.halfWidth()+.6,0);
    warning(islandParts,inner,forward);warning(parts,outer,forward);
    if(walk.tactile()){parts.addAll(TactilePaths.parts(List.of(outer,at.at(at.halfWidth()+RoadStreetscape.tactileOffset(walk.width()),0))));
     Part nearest=pieces.stream().flatMap(p->p.structures().stream()).filter(p->p.material()==Material.TACTILE&&p.height()<.018).min(Comparator.comparingDouble(p->p.a().sub(inner).horizontalLength())).orElse(null);
     if(nearest!=null&&nearest.a().distance(inner)<40){V d=nearest.b().sub(nearest.a());double t=Math.max(0,Math.min(1,inner.sub(nearest.a()).dot(d)/Math.max(1e-9,d.dot(d))));V join=nearest.a().add(d.mul(t)).sub(new V(0,.2,0));islandParts.addAll(TactilePaths.parts(List.of(join,inner)));}
    }
   }
   // Yield before entering the exit road; the bypass does not inherit the central stop line.
   var yield=RoadStructures.sample(slip.motor(),slip.motor().length()*.72);V forward=yield.left().left().mul(-1);
   var triangle=List.of(yield.at(-Math.min(1.2,yield.halfWidth()-.3),-.018).sub(forward),yield.at(Math.min(1.2,yield.halfWidth()-.3),-.018).sub(forward),yield.center().add(forward.mul(1.8)).add(new V(0,-.018,0)));
   for(int k=0;k<3;k++)JunctionPaint.line(paint,triangle.get(k),triangle.get((k+1)%3),.18,JunctionPaint.WHITE);
   // custom() projects all markings onto the deck. Split the colored cycle
   // surface around white markings instead of relying on tiny Y differences.
   var whiteMask=new RoadSurface.PaintMask(paint.stream().filter(f->f.color()==JunctionPaint.WHITE).map(Face::points).toList());
   var disjoint=new ArrayList<Face>();for(var face:paint){if(face.color()==JunctionPaint.WHITE)disjoint.add(face);else disjoint.addAll(clipPaint(List.of(face),whiteMask));}paint=disjoint;
   pieces.add(new Piece(islandMesh,List.copyOf(islandPaint),List.copyOf(islandParts),-1));pieces.add(new Piece(road,List.copyOf(paint),List.copyOf(parts),-2-slip.from()));
   movements.removeIf(m->m.from()==slip.from()&&m.turn()==RIGHT);movements.add(new Movement(slip.from(),arm.incoming()-1,slip.to(),s.arms().get(slip.to()).outgoing()-1,RIGHT,slip.motor().samples().stream().map(Sample::center).toList()));
  }
  return new Plan(List.copyOf(pieces),List.copyOf(movements),base.boundary(),Collections.unmodifiableMap(walks),base.signals(),base.heads());
 }
 private static List<List<V>> polygons(List<Mesh> meshes){var out=new ArrayList<List<V>>();for(var mesh:meshes)for(int i=1;i<mesh.samples().size();i++){var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);out.add(List.of(a.at(-a.halfWidth(),0),a.at(a.halfWidth(),0),b.at(b.halfWidth(),0),b.at(-b.halfWidth(),0)));}return out;}
 private static Mesh islandMesh(JunctionSpec s,Slip slip){var fan=new ArrayList<Sample>();V apex=s.center();for(int k=0;k<slip.island().size()-1;k++){V edge=slip.island().get(k),delta=edge.sub(apex);fan.add(new Sample(edge.add(apex).mul(.5),delta.horizontalUnit(),0,delta.horizontalLength()/2));}return fanMesh(fan,s.thickness());}
 private static List<Face> clipPaint(List<Face> faces,RoadSurface.PaintMask mask){var out=new ArrayList<Face>();for(var f:faces)for(var p:mask.clip(f.points()))out.add(new Face(p,f.color()));return List.copyOf(out);}
 private static Part trianglePart(List<V> p,Material m,double height){V a=p.get(0),b=p.get(1),c=p.get(2);return new Part(a.add(b).mul(.5),c,Math.max(.001,Math.min(8,a.distance(b))),height,false,m,a.sub(b).mul(.5),new V(0,0,0));}
 private static Mesh fanMesh(List<Sample> raw,double thickness){
  // This is a triangulated pad fan, not a vehicle centerline: its inner edge
  // intentionally collapses onto the apex, as in the central junction triangles.
  var samples=new ArrayList<Sample>();var bounds=new ArrayList<V>();double d=0;V previous=null;
  for(var p:raw){if(previous!=null){double step=p.center().distance(previous);if(step<1e-7)continue;d+=step;}samples.add(new Sample(p.center(),p.left(),d,p.halfWidth()));bounds.add(p.at(p.halfWidth(),0));bounds.add(p.at(-p.halfWidth(),0));previous=p.center();}
  double y=samples.get(0).center().y();var min=new V(bounds.stream().mapToDouble(V::x).min().orElseThrow(),y-thickness,bounds.stream().mapToDouble(V::z).min().orElseThrow());var max=new V(bounds.stream().mapToDouble(V::x).max().orElseThrow(),y,bounds.stream().mapToDouble(V::z).max().orElseThrow());
  return new Mesh(List.copyOf(samples),JunctionPlanner.settings(4,thickness),min,max,d,false);
 }
 private static Part material(Part p,Material m,double height){return new Part(p.a(),p.b(),p.width(),height,p.pier(),m,p.frameA(),p.frameB());}
 private static void warning(List<Part> out,V p,V forward){out.add(new Part(p.sub(forward.mul(.4)).add(new V(0,.2,0)),p.add(forward.mul(.4)).add(new V(0,.2,0)),.8,.02,false,Material.TACTILE));for(double x:new double[]{-.24,0,.24})for(double z:new double[]{-.24,0,.24}){V q=p.add(forward.mul(x)).add(forward.left().mul(z)).add(new V(0,.22,0));out.add(new Part(q,q,.08,.025,true,Material.TACTILE));}}
 private static void hatch(List<Face> out,List<V> polygon){double low=polygon.stream().mapToDouble(p->p.x()+p.z()).min().orElse(0),high=polygon.stream().mapToDouble(p->p.x()+p.z()).max().orElse(0);for(double v=Math.ceil(low/3)*3;v<high;v+=3){var hits=new ArrayList<V>();for(int i=0;i<polygon.size();i++){V a=polygon.get(i),b=polygon.get((i+1)%polygon.size());double d=b.x()+b.z()-a.x()-a.z();if(Math.abs(d)<1e-9)continue;double t=(v-a.x()-a.z())/d;if(t>=0&&t<1)hits.add(a.add(b.sub(a).mul(t)).add(new V(0,-.018,0)));}if(hits.size()==2)JunctionPaint.line(out,hits.get(0),hits.get(1),.35,JunctionPaint.WHITE);}}
 private JunctionSlips48(){}
}
