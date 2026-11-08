package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.JunctionSpec.*;
import com.sora.splineroads.core.JunctionPlanner.*;
import com.sora.splineroads.core.RoadSurface.Face;
import java.util.*;
import static com.sora.splineroads.core.JunctionSpec.RIGHT;

/** Near-side right turn lanes. Each incoming arm owns its road and refuge island. */
public final class JunctionSlips {
 public record Slip(int from,int to,Mesh mesh,Mesh motor,List<V> island,double departure,double arrival,double cycleWidth,List<Double> cycleWidths,double turnEnd) {
  public double cycle(int sample){return cycleWidths.get(sample);}
 }
 public static boolean enabled(JunctionSpec s){return s.arms().stream().anyMatch(Arm::rightSlip);}
 public static boolean receives(JunctionSpec s,int to){for(int i=0;i<s.arms().size();i++)if(s.arms().get(i).rightSlip()&&target(s,i)==to)return true;return false;}
 private static V outward(JunctionSpec s,int arm){return s.arms().get(arm).endpoint().position().sub(s.center()).horizontalUnit();}
 public static int target(JunctionSpec s,int from){
  int result=-1;double best=2;
  for(int j=0;j<s.arms().size();j++)if(j!=from&&s.arms().get(j).outgoing()>0&&JunctionPlanner.turn(s,from,j)==RIGHT){double score=Math.abs(outward(s,from).dot(outward(s,j)));if(score<best){best=score;result=j;}}
  return result;
 }
 public static String unavailable(JunctionSpec s,int from){
  if(s.oneSide()!=null)return "单边路口保持中央隔离带连续，不使用额外独立右转道";
  int n=s.arms().size();if(s.kind()!=Kind.INTERSECTION||n!=3&&n!=4||s.parallelApproaches())return "仅适用于十字或丁字路口";
  if(s.leftTraffic())return "靠左通行的右转须穿越对向车道，不能设置外侧独立右转";
  if(s.arms().get(from).incoming()==0)return "此方向没有驶入车道";
  int opposite=0;for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(outward(s,i).dot(outward(s,j))<-.94)opposite++;
  if(opposite<(n==3?1:2))return "需要具有相对主路的十字或丁字布局";
  int to=target(s,from);if(to<0)return "此方向没有右转出口";
  if(s.arms().get(to).outgoing()<2)return "右转转入方向至少需要 2 条车道";
  double angle=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,outward(s,from).dot(outward(s,to))))));
  if(angle<60-1e-6||angle>120+1e-6)return "右转相邻道路夹角需为 60–120°";
  for(int i:new int[]{from,to}){var a=s.arms().get(i);if(a.external().structure()==Structure.TUNNEL)return "隧道路口不能设置外侧独立右转";if(Math.abs(a.endpoint().position().y()-s.center().y())>.1||Math.abs(a.endpoint().grade())>.001)return "独立右转接入口需要与路口齐平";}
  return "";
 }
 public static String islandLabel(Arm a){return a.external().options().sidewalk().enabled()?RoadSidewalks.Finish.of(a.external().options().sidewalk().material()).label:a.cycleWidth()>.01?"石砖":"导流线";}
 static double run(JunctionSpec s,int from,int to){var a=s.arms().get(from);var b=s.arms().get(to);return Math.max(40,s.cornerRadius()*2+Math.max(a.cycleWidth()+a.curbWidth(),b.cycleWidth()+b.curbWidth())+Math.max(a.laneWidth(),b.laneWidth())+20);}
 public static double approachLength(JunctionSpec s,int arm){double length=0;for(int i=0;i<s.arms().size();i++)if(s.arms().get(i).rightSlip()){int to=target(s,i);if(to>=0&&(i==arm||to==arm))length=Math.max(length,run(s,i,to)+40);}return length;}
 private static V curve(V a,V b,V c,V d,double t){double u=1-t;return a.mul(u*u*u).add(b.mul(3*u*u*t)).add(c.mul(3*u*t*t)).add(d.mul(t*t*t));}
 private static double smooth(double t){return Settings.smooth(Math.max(0,Math.min(1,t)));}
 private static double cycleSpan(Arm a){return a.cycleWidth()>.01?a.cycleWidth()+a.curbWidth()+.5:0;}
 static double walkSpan(Arm a){var w=a.external().options().sidewalk();return w.enabled()?w.width():a.cycleWidth()>.01?3:0;}
 static double walkWidth(JunctionSpec s,Slip slip,double d){
  return walkWidth(s,slip,d,true);
 }
 static double walkWidth(JunctionSpec s,Slip slip,double d,boolean smoothEdges){
  double a=walkSpan(s.arms().get(slip.from())),b=walkSpan(s.arms().get(slip.to()));
  double from=cycleSpan(s.arms().get(slip.from())),to=cycleSpan(s.arms().get(slip.to()));
  if(smoothEdges&&from>0&&to==0&&b>=from-.5+2){
   // Keep a wide footway's outside edge tangent to the exit while the cycle lane
   // narrows inside it. Its freed width becomes paving instead of an S-shaped verge.
   double delta=from-.5;
   if(d<=slip.turnEnd())return a+(b-delta-a)*smooth(d/slip.turnEnd());
   return b-delta*(1-smooth((d-slip.turnEnd()-4)/Math.max(1,slip.mesh().length()-slip.turnEnd()-4)));
  }
  double t=a>0&&b==0?smooth((d-slip.turnEnd()-4)/Math.max(1,slip.mesh().length()-slip.turnEnd()-4)):a==0?smooth(d/24):smooth(d/slip.turnEnd());return a+(b-a)*t;
 }
 public static List<Slip> layout(JunctionSpec s,List<Mesh> approaches){return layout(s,approaches,true,true);}
 static List<Slip> layout(JunctionSpec s,List<Mesh> approaches,boolean exact){
  return layout(s,approaches,exact,false);
 }
 static List<Slip> layout(JunctionSpec s,List<Mesh> approaches,boolean exact,boolean smoothEdges){
  var result=new ArrayList<Slip>();
  for(int i=0;i<s.arms().size();i++)if(s.arms().get(i).rightSlip()){
   String reason=unavailable(s,i);if(!reason.isEmpty())throw new IllegalArgumentException("接入口 "+(i+1)+"："+reason);
   int j=target(s,i);var a=s.arms().get(i);var b=s.arms().get(j);var ma=approaches.get(i);var mb=approaches.get(j);double run=run(s,i,j);
   if(Math.min(ma.length(),mb.length())<run+32)throw new IllegalArgumentException("独立右转接路过短，请将相邻端点各延长到至少 "+(int)Math.ceil(JunctionPlanner.radius(s)+run+32)+" 格外");
   double da=ma.length()-run,db=mb.length()-run;var sa=RoadStructures.sample(ma,da);var sb=RoadStructures.sample(mb,db);
   V p=sa.at(a.motorEdge(true,false)+a.laneWidth()/2,0),q=sb.at(b.laneCenter(false,b.outgoing()-1,false),0);
   V f=sa.left().left().mul(-1).add(sa.left().mul(smoothEdges?0:a.laneWidth()/16)).horizontalUnit(),g=sb.left().left();double handle=p.sub(q).horizontalLength()*.35;
   V c1=p.add(f.mul(handle)),c2=q.sub(g.mul(handle));int steps=Math.max(32,(int)Math.ceil(p.distance(q)*2));var samples=new ArrayList<Sample>();
   double width=Math.max(4,Math.min(6,Math.max(a.laneWidth(),b.laneWidth())+1));
   for(int k=0;k<=steps;k++){double t=k/(double)steps;V at=curve(p,c1,c2,q,t),d=k==0?f:k==steps?g:curve(p,c1,c2,q,Math.min(1,t+.0001)).sub(curve(p,c1,c2,q,Math.max(0,t-.0001))).horizontalUnit();double edge=smoothEdges?16*t*t*(1-t)*(1-t):Math.min(1,Math.min(t,1-t)*6);double blend=smoothEdges?smooth(t):t;double w=(a.laneWidth()*(1-blend)+b.laneWidth()*blend)*(1-edge)+width*edge;double joinBlend=Settings.smooth(Math.min(1,t*p.distance(q)/12));V frame=smoothEdges?d.left():sa.left().mul(1-joinBlend).add(d.left().mul(joinBlend)).horizontalUnit();samples.add(new Sample(at,frame,0,w/2));}
   var motor=RoadRibbon.mesh(samples,JunctionPlanner.settings(width,s.thickness()));RoadRibbon.checkSelfIntersections(motor,4);
   if(RoadRibbon.minRadius(motor)<Math.max(6,width*1.5))throw new IllegalArgumentException("独立右转半径不足，请增大路口转角半径或延长接路");
   double fromCycle=cycleSpan(a),toCycle=cycleSpan(b),cycle=exact?Math.max(fromCycle,toCycle):Math.min(fromCycle,toCycle);
   int turnSamples=samples.size();double turnLength=motor.length(),tail=exact&&((fromCycle>0&&toCycle==0)||(walkSpan(a)>0&&walkSpan(b)==0))?Math.min(32,db-2):0;
   var island=new ArrayList<V>();for(int k=0;k<samples.size();k+=exact?1:4){var at=samples.get(k);island.add(at.at(-at.halfWidth()-(exact?0:.2),0));}var last=samples.get(samples.size()-1);if(!exact)island.add(last.at(-last.halfWidth()-.2,0));island.add(s.center());
   if(tail>0){int count=(int)Math.ceil(tail*2);for(int k=1;k<=count;k++)samples.add(new Sample(last.center().add(g.mul(tail*k/count)),last.left(),0,last.halfWidth()));motor=RoadRibbon.mesh(samples,JunctionPlanner.settings(width,s.thickness()));}
   var widths=new ArrayList<Double>();var wide=new ArrayList<Sample>();
   for(var at:motor.samples()){
    double cw=cycle,blend=0;
    if(exact){blend=fromCycle>0&&toCycle==0?smooth((at.distance()-turnLength-4)/Math.max(1,tail-4)):fromCycle==0?smooth(at.distance()/24):smooth(at.distance()/turnLength);cw=fromCycle+(toCycle-fromCycle)*blend;}
    double shoulder=smoothEdges?((fromCycle==0?.5:0)*(1-blend)+(toCycle==0?.5:0)*blend):0,outer=cw+shoulder;
    widths.add(cw);wide.add(new Sample(at.at(outer/2,0),at.left(),0,at.halfWidth()+outer/2));
   }
   var mesh=cycle==0&&!smoothEdges?motor:RoadRibbon.mesh(wide,JunctionPlanner.settings(width+cycle+(smoothEdges?.5:0),s.thickness()));
   result.add(new Slip(i,j,mesh,motor,List.copyOf(island),da,db-tail,cycle,List.copyOf(widths),mesh.samples().get(turnSamples-1).distance()));
  }return List.copyOf(result);
 }
 /** Clip replaceable street furniture at the new lane; actual road/deck supports stay. */
 public static List<Part> clear(List<Part> parts,List<Mesh> slips){
  var walkClip=new SidewalkJoins(slips,List.of());var out=new ArrayList<Part>();
  for(var p:walkClip.clip(parts)){
   if(RoadSidewalks.smoothPart(p)){out.add(p);continue;}
   if((p.material()==Material.GREEN||p.material()==Material.SOIL||p.material()==Material.CONCRETE)&&p.height()<=1.2&&p.width()<=3&&!p.pier()){
    // Planting and its curb must share an exact cut, even where a short deck
    // overlap touches only one end of a long base. Never discard the whole base.
    var proxy=new Part(p.a(),p.b(),p.width(),p.height(),false,Material.WALK_STONE_BRICKS,p.frameA(),p.frameB());
    for(var bit:new SidewalkJoins(slips,List.of()).clip(List.of(proxy)))out.add(new Part(bit.a(),bit.b(),bit.width(),p.height(),false,p.material(),bit.frameA(),bit.frameB(),p.model(),null));
    continue;
   }
   boolean blocked=false;for(var m:slips){
    if(Math.max(p.a().y(),p.b().y())+p.height()<m.min().y()+m.settings().thickness()-.02)continue;
    if(RoadClearance.structureInvades(p,m,4)){blocked=true;break;}
   }
   if(!blocked)out.add(p);
  }return List.copyOf(out);
 }
 /** Old cycle planters can leave thin concrete wedges between the new slip
  * and its reserved approach. The complete refuge footprint owns that space,
  * including the part clipped from its final paving by the approach deck. */
 public static List<Part> clearRefugePlanting(List<Part> parts,List<Mesh> refuges){
  var out=new ArrayList<Part>();var planting=new ArrayList<Part>();
  for(var p:parts)if(p.model().equals("sr:cycle_planter"))planting.add(p);else out.add(p);
  out.addAll(clear(planting,refuges));return List.copyOf(out);
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
 public static Plan apply(JunctionSpec s,Plan base){return apply(s,base,true);}
 static Plan apply(JunctionSpec s,Plan base,boolean current){return apply(s,base,current,true);}
 static Plan apply(JunctionSpec s,Plan base,boolean current,boolean exact){return apply(s,base,current,exact,false);}
 static Plan apply(JunctionSpec s,Plan base,boolean current,boolean exact,boolean transitionPaint){
  return apply(s,base,current,exact,transitionPaint,false);
 }
 static Plan apply(JunctionSpec s,Plan base,boolean current,boolean exact,boolean transitionPaint,boolean smoothEdges){
  if(!enabled(s))return base;
  var approaches=base.pieces().subList(0,s.arms().size()).stream().map(Piece::mesh).toList();var layout=layout(s,approaches,exact,smoothEdges);var profiles=new JunctionSlipProfiles(s,approaches,exact,smoothEdges);base=profiles.apply(base);var lanes=layout.stream().map(Slip::mesh).toList();
  var replaced=new ArrayList<>(lanes);replaced.addAll(profiles.refugeStrips());
  var laneMask=new RoadSurface.PaintMask(polygons(replaced));
  var islands=layout.stream().map(v->islandMesh(s,v)).toList();
  var pieces=new ArrayList<Piece>();for(var p:base.pieces()){
   var retained=clear(p.structures(),replaced).stream().filter(v->v.material()!=Material.TACTILE||!JunctionSlipWalks.affected(s,approaches,layout,v)).toList();var paving=retained.stream().filter(v->v.material().name().startsWith("WALK_")).toList();
   var parts=new ArrayList<>(retained.stream().filter(v->!v.material().name().startsWith("WALK_")).toList());parts.addAll(new SidewalkJoins(islands,List.of()).clip(paving));
   pieces.add(new Piece(p.mesh(),clipPaint(p.paint(),laneMask),List.copyOf(parts),p.arm()));
  }
  for(var slip:layout){int i=slip.from();var old=pieces.get(i);var paint=new ArrayList<>(old.paint());var a=s.arms().get(i);var m=approaches.get(i);
   var divider=new ArrayList<V>();for(var p:m.samples())if(p.distance()>=slip.departure()-28&&p.distance()<=slip.departure()+2)divider.add(p.at(a.motorEdge(true,false),-.014));JunctionPaint.path(paint,divider,.14,true,JunctionPaint.WHITE);
   pieces.set(i,new Piece(old.mesh(),List.copyOf(paint),old.structures(),old.arm()));
  }
  var movements=new ArrayList<>(base.movements());var walks=new LinkedHashMap<>(base.sidewalks());walks.keySet().removeIf(c->lanes.stream().anyMatch(m->RoadQueries.contains(m,new V(c.x()+.5,c.y(),c.z()+.5),.1,.7)));
  var original=base.pieces().stream().map(Piece::mesh).toList();
  var oldRoadMask=new RoadSurface.PaintMask(polygons(original));
  for(var slip:layout){
   Arm arm=s.arms().get(slip.from());var road=slip.mesh();var paint=new ArrayList<Face>();var parts=new ArrayList<Part>();
   for(int side:new int[]{-1,1}){var path=road.samples().stream().map(v->v.at(side*(v.halfWidth()-.12),-.016)).toList();JunctionPaint.path(paint,path,.14,false,JunctionPaint.WHITE);}
   paint=new ArrayList<>(clipPaint(paint,oldRoadMask));
   // The auxiliary lane is still opening on the approach. Put its arrow on the
   // complete bypass deck, not at a full-lane offset inside the narrowing taper.
   var arrow=RoadStructures.sample(slip.motor(),slip.motor().length()*.18);
   JunctionPaint.arrow(paint,arrow.center().add(new V(0,-.02,0)),arrow.left().left().mul(-1),RIGHT,Math.min(1,arrow.halfWidth()/1.9));
   var pavement=new ArrayList<Part>();for(var tri:islandTriangles(slip,exact))pavement.add(trianglePart(tri,Material.WALK_STONE_BRICKS,.2));
   pavement=new ArrayList<>(new SidewalkJoins(original,List.of()).clip(pavement));
   if(pavement.stream().mapToDouble(p->Math.abs(JunctionPaint.area(p.base()))).sum()<12)
    throw new IllegalArgumentException("独立右转岛区空间不足，请增大转角半径或调整相邻道路夹角");
   // One radial fan supplies the triangle's asphalt/structural base, with the road
   // surface union owning overlap with existing approaches and the central pad.
   var islandMesh=islandMesh(s,slip);
   boolean pedestrian=arm.external().options().sidewalk().enabled()||arm.cycleWidth()>.01||exact&&walkSpan(s.arms().get(slip.to()))>0;
   boolean green=!current&&!pedestrian&&arm.median()>0&&arm.medianKind()==RoadProfile.Median.GREEN;
   var islandParts=new ArrayList<Part>();var islandPaint=new ArrayList<Face>();
   for(var p:pavement){
    if(pedestrian)islandParts.add(material(p,arm.external().options().sidewalk().enabled()?RoadSidewalks.Finish.of(arm.external().options().sidewalk().material()).material():Material.WALK_STONE_BRICKS,.2));
    else if(green){islandParts.add(material(p,Material.SOIL,.25));var soil=material(p,Material.GREEN,.35);islandParts.add(new Part(soil.a().add(new V(0,.25,0)),soil.b().add(new V(0,.25,0)),soil.width(),soil.height(),false,soil.material(),soil.frameA(),soil.frameB()));}
    else hatch(islandPaint,p.base(),exact);
   }
   var config=arm.external().options().sidewalk();
   if(slip.cycleWidth()>0){
    var cycleArm=arm.cycleWidth()>.01?arm:s.arms().get(slip.to());double separation=RoadStreetscape.separatorWidth(cycleArm.external().options());
    for(int k=1;k<road.samples().size();k++){var a=road.samples().get(k-1);var b=road.samples().get(k);double ca=slip.cycle(k-1),cb=slip.cycle(k),ia=a.halfWidth()-ca,ib=b.halfWidth()-cb;
     if(Math.max(ca,cb)<.01)continue;
     double fa=ca/slip.cycleWidth(),fb=cb/slip.cycleWidth();
     JunctionPaint.line(paint,a.at(ia,-.016),b.at(ib,-.016),.13,JunctionPaint.WHITE);
     if(!cycleArm.external().options().cycleAsphalt())paint.add(new Face(List.of(a.at(ia+(separation+.05)*fa,-.009),a.at(a.halfWidth()-.08*fa,-.009),b.at(b.halfWidth()-.08*fb,-.009),b.at(ib+(separation+.05)*fb,-.009)),0x536F61));
     boolean paintOnly=transitionPaint&&((arm.cycleWidth()<=.01&&b.distance()<=slip.turnEnd()*.5+1.8)||(s.arms().get(slip.to()).cycleWidth()<=.01&&a.distance()>=slip.turnEnd()*.5-1.8));
     if(separation>0&&!paintOnly&&(current||Math.abs(a.distance()-road.length()*.5)>2.2)){
      RoadStructures.cyclePlanter(parts,a,b,1,ia,ib,separation,fa,fb);
     }else if(transitionPaint&&!paintOnly&&cycleArm.external().options().streetscape().separator()==RoadStreetscape.Separator.RAIL){
      RoadStructures.barrier(parts,a.at(ia,0),b.at(ib,0),false,false,a.distance());
     }
    }
   }
   if(pedestrian){
    if(current){
     // Cut the planter, base and edge paint with one physical crossing opening.
     var opening=crossingOpening(road,exact?slip.turnEnd()*.5:road.length()*.5);
     paint=new ArrayList<>(clipPaint(paint,new RoadSurface.PaintMask(polygons(List.of(opening)))));
     parts=new ArrayList<>(clear(parts,List.of(opening)));
    }
    var walk=config.enabled()?config:new RoadSidewalks.Config(true,RoadSidewalks.Side.RIGHT,3,"minecraft:stone_bricks");
    walk=walk.side(RoadSidewalks.Side.RIGHT);
    var walking=exact?RoadSidewalks.variableParts(road,walk.tactile(false),road.samples().stream().map(p->walkWidth(s,slip,p.distance(),smoothEdges)).toList()):RoadSidewalks.parts(road,walk.tactile(false));
    parts.addAll(new SidewalkJoins(original,pieces.stream().flatMap(p->p.structures().stream()).toList()).clip(walking));
    // The crossing links the refuge to the outer footway. Both ends receive a
    // warning pad; no tactile strip is painted across the vehicle lane itself.
    double station=exact?slip.turnEnd()*.5:road.length()*.5;var at=RoadStructures.sample(road,station);V forward=at.left().left().mul(-1);
    for(double x=-at.halfWidth()+.35;x<at.halfWidth()-.2;x+=1.4)JunctionPaint.line(paint,at.at(x,-.018).sub(forward.mul(1.8)),at.at(x,-.018).add(forward.mul(1.8)),.7,JunctionPaint.WHITE);
    V inner=at.at(-at.halfWidth()-.6,0),outer=at.at(at.halfWidth()+.6,0);
    warning(islandParts,inner,forward);warning(parts,outer,forward);
    if(walk.tactile())JunctionSlipWalks.add(s,approaches,profiles,slip,walk,inner,outer,parts,islandParts,exact,smoothEdges);
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
   movements.removeIf(m->m.from()==slip.from()&&m.turn()==RIGHT);movements.add(new Movement(slip.from(),arm.incoming(),slip.to(),s.arms().get(slip.to()).outgoing()-1,RIGHT,slip.motor().samples().stream().map(Sample::center).toList()));
  }
  return new Plan(List.copyOf(pieces),List.copyOf(movements),base.boundary(),Collections.unmodifiableMap(walks),base.signals(),base.heads());
 }
 private static List<List<V>> polygons(List<Mesh> meshes){var out=new ArrayList<List<V>>();for(var mesh:meshes)for(int i=1;i<mesh.samples().size();i++){var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);out.add(List.of(a.at(-a.halfWidth(),0),a.at(a.halfWidth(),0),b.at(b.halfWidth(),0),b.at(-b.halfWidth(),0)));}return out;}
 private static List<List<V>> islandTriangles(Slip slip,boolean exact){
  if(!exact)return JunctionPaint.triangulate(slip.island());var out=new ArrayList<List<V>>();var p=slip.island();V center=p.get(p.size()-1);
  for(int i=1;i<p.size()-1;i++)out.add(List.of(center,p.get(i-1),p.get(i)));
  double sum=out.stream().mapToDouble(q->Math.abs(JunctionPaint.area(q))).sum();
  if(Math.abs(sum-Math.abs(JunctionPaint.area(p)))>Math.max(1e-7,sum*1e-9))return JunctionPaint.triangulate(p);
  return List.copyOf(out);
 }
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
 private static void hatch(List<Face> out,List<V> polygon,boolean exact){if(exact){hatchBands(out,polygon);return;}double low=polygon.stream().mapToDouble(p->p.x()+p.z()).min().orElse(0),high=polygon.stream().mapToDouble(p->p.x()+p.z()).max().orElse(0);for(double v=Math.ceil(low/3)*3;v<high;v+=3){var hits=new ArrayList<V>();for(int i=0;i<polygon.size();i++){V a=polygon.get(i),b=polygon.get((i+1)%polygon.size());double d=b.x()+b.z()-a.x()-a.z();if(Math.abs(d)<1e-9)continue;double t=(v-a.x()-a.z())/d;if(t>=0&&t<1)hits.add(a.add(b.sub(a).mul(t)).add(new V(0,-.018,0)));}if(hits.size()==2){var stripe=new ArrayList<Face>();JunctionPaint.line(stripe,hits.get(0),hits.get(1),.35,JunctionPaint.WHITE);for(var face:stripe){var points=exact?RoadSurface.intersect(face.points(),polygon):face.points();if(points.size()>=3)out.add(new Face(points,face.color()));}}}}
 // Clip a complete stripe, including the width on both sides of a fan edge.
 // Centerline segment caps leave triangular holes between neighboring triangles.
 private static void hatchBands(List<Face> out,List<V> polygon){
  V normal=new V(1,0,1).horizontalUnit(),direction=new V(1,0,-1).horizontalUnit();
  double low=polygon.stream().mapToDouble(p->p.x()+p.z()).min().orElse(0),high=polygon.stream().mapToDouble(p->p.x()+p.z()).max().orElse(0);
  double from=polygon.stream().mapToDouble(p->p.dot(direction)).min().orElse(0)-1,to=polygon.stream().mapToDouble(p->p.dot(direction)).max().orElse(0)+1;
  double half=.175,margin=half*Math.sqrt(2),y=polygon.get(0).y()-.018;
  for(double v=Math.ceil((low-margin)/3)*3;v<high+margin;v+=3){V axis=normal.mul(v/Math.sqrt(2)).add(new V(0,y,0)),a=axis.add(direction.mul(from)),b=axis.add(direction.mul(to)),n=normal.mul(half);
   var points=RoadSurface.intersect(List.of(a.add(n),a.sub(n),b.sub(n),b.add(n)),polygon);if(points.size()>=3)out.add(new Face(points,JunctionPaint.WHITE));
  }
 }
 public static double advanceSignStation(JunctionSpec s,int arm,Mesh approach){return approach.length()-run(s,arm,target(s,arm))-36;}
 public static Mesh crossingOpening(Mesh road){return crossingOpening(road,road.length()*.5);}
 static Mesh crossingOpening(Mesh road,double station){var at=RoadStructures.sample(road,station);V f=at.left().left().mul(-1);return RoadRibbon.mesh(List.of(new Sample(at.center().sub(f.mul(2.3)),at.left(),0,at.halfWidth()+1),new Sample(at.center().add(f.mul(2.3)),at.left(),0,at.halfWidth()+1)),JunctionPlanner.settings((at.halfWidth()+1)*2,road.settings().thickness()));}
 private JunctionSlips(){}
}
