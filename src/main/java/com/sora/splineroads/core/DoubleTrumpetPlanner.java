package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;

/** Two perpendicular trumpet junctions joined by one continuous two-way road. */
public final class DoubleTrumpetPlanner {
 public static final String CONNECTOR="双喇叭中央连接段";
 private static double cross(V a,V b){return a.x()*b.z()-a.z()*b.x();}
 public static Plan plan(Node[] nodes,Settings a,Settings b,Options o){
  if(nodes.length!=4)throw new IllegalArgumentException("双喇叭需要四个主路端点");
  for(var s:List.of(a,b)){s.validate();if(s.style().ramp()||!RoadProfile.catalog(s).twoWay())throw new IllegalArgumentException("双喇叭两条主路都需要完整双向道路");}
  double ab=nodes[1].position().sub(nodes[0].position()).horizontalLength(),cd=nodes[3].position().sub(nodes[2].position()).horizontalLength();
  if(ab<100||cd<100||ab>4096+1e-6||cd>4096+1e-6)throw new IllegalArgumentException("双喇叭两条主路总长各需 100–4096 格");
  var levels=InterchangePlanner.levels(nodes,a,b,o);
  if(o.flatConnector()&&(o.connectorHeight()<Math.min(levels.first(),levels.second())-1e-7||o.connectorHeight()>Math.max(levels.first(),levels.second())+1e-7))throw new IllegalArgumentException("双喇叭水平段高度必须在两条主路高度之间（含边界），且为整数");
  V u=nodes[1].position().sub(nodes[0].position()).horizontalUnit(),v=nodes[3].position().sub(nodes[2].position()).horizontalUnit();double det=cross(u,v);
  if(Math.abs(det)<.5)throw new IllegalArgumentException("双喇叭要求主路夹角至少 30 度");
  V center=nodes[0].position().add(u.mul(cross(nodes[2].position().sub(nodes[0].position()),v)/det));
  double alongA=center.sub(nodes[0].position()).dot(u),alongB=center.sub(nodes[2].position()).dot(v);
  if(Math.min(alongA,alongB)<45||Math.min(ab-alongA,cd-alongB)<=0)
    throw new IllegalArgumentException("双喇叭两轴需在端点之间相交，喇叭所在的 A、C 端距交点至少 45 格");
  // Only the A and C arms host the two trumpet junctions. Opposite
  // through-road tails must not constrain their placement or grow in lockstep.
  double shortest=Math.min(nodes[0].position().sub(center).horizontalLength(),nodes[2].position().sub(center).horizontalLength());
  double offset=shortest*.60,reach=offset*.75;
  V na=u.left().mul(u.left().dot(v)<0?1:-1),nb=v.left().mul(v.left().dot(u)<0?1:-1);
  double ya=levels.first(),yb=levels.second();
  V pa=center.sub(u.mul(offset)).add(na.mul(reach)),pb=center.sub(v.mul(offset)).add(nb.mul(reach));
  pa=new V(pa.x(),ya,pa.z());pb=new V(pb.x(),yb,pb.z());
  var ac=RoadLanes.counts(a);var bc=RoadLanes.counts(b);
  int lanes=Math.min(Math.min(ac.forward(),ac.reverse()),Math.min(bc.forward(),bc.reverse()));
  boolean lamps=!(RoadProfile.catalog(a).type()==RoadProfile.Type.HIGHWAY&&RoadProfile.catalog(b).type()==RoadProfile.Type.HIGHWAY);
  double laneWidth=Math.min(RoadProfile.layout(a,a.width()).laneWidth(),RoadProfile.layout(b,b.width()).laneWidth());
  Settings connector=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_RAIL,10,1,.4,90).structure(Structure.AUTO)
      .options(RoadProfile.Options.DEFAULT.traffic(o.leftTraffic()).streetscape(RoadStreetscape.Config.DEFAULT.lamps(lamps))),
      RoadProfile.Type.ORDINARY,new RoadLanes.Counts(lanes,lanes),laneWidth);
  var child=new Options(Preset.TRUMPET,o.leftTraffic(),o.lanes(),o.transition(),o.radius(),o.clearance(),o.upper(),o.rampWidth(),false);
  V ca=center.sub(u.mul(offset)),cb=center.sub(v.mul(offset));
  Plan first=InterchangePlanner.plan(new Node[]{near(nodes[0],ca,u),near(nodes[1],ca,u),new Node(pa,RoadPlanner.yaw(na.mul(-1)),0)},a,connector,child);
  Plan second=InterchangePlanner.plan(new Node[]{near(nodes[2],cb,v),near(nodes[3],cb,v),new Node(pb,RoadPlanner.yaw(nb.mul(-1)),0)},b,connector,child);
  V outerA=landing(pa,na,o),outerB=landing(pb,nb,o);
  var legs=new ArrayList<Leg>();
  legs.add(new Leg("主路 1",0,2,extend(first.legs().get(0).mesh(),nodes[0],nodes[1])));legs.add(new Leg("主路 2",1,3,extend(second.legs().get(0).mesh(),nodes[2],nodes[3])));
  for(int i=1;i<first.legs().size();i++){var l=first.legs().get(i);legs.add(new Leg("AB 侧 "+l.name(),arm(l.from(),0),arm(l.to(),0),i==1?approach(l.mesh(),outerA):l.mesh()));}
  int secondStart=legs.size();
  for(int i=1;i<second.legs().size();i++){var l=second.legs().get(i);legs.add(new Leg("CD 侧 "+l.name(),arm(l.from(),1),arm(l.to(),1),i==1?approach(l.mesh(),outerB):l.mesh()));}
  // The toll road starts as soon as the trumpet has a full, unbranched section.
  // Keep each narrowing throat in its own leg and join the remaining straight
  // approaches to the central curve without changing their pavement.
  Mesh approachA=legs.get(2).mesh(),approachB=legs.get(secondStart).mesh();
  double cutA=connectorStart(approachA,first),cutB=connectorStart(approachB,second);
  double extraA=0,extraB=0;
  if(o.toll().enabled()){
   double required=DoubleTrumpetToll.length(connector,o)+16;
   if(o.toll().near()==DoubleTrumpetToll.Near.AB)extraA=Math.max(0,required-cutA);
   else extraB=Math.max(0,required-cutB);
  }
  V curveA=outerA.add(na.mul(extraA)),curveB=outerB.add(nb.mul(extraB));
  Mesh middle=connector(curveA,curveB,na,nb.mul(-1),connector,o);
  var central=new ArrayList<Sample>();
  if(cutA>0){var prefix=new ArrayList<>(RoadSlicing.slice(approachA,0,cutA,connector).mesh().samples());Collections.reverse(prefix);
   // Alignment storage omits the horizontal frame's Y component. Keep its
   // canonical +0 so an unchanged plan equals its saved/reloaded shape.
   for(var p:prefix)central.add(new Sample(p.center(),new V(-p.left().x(),0,-p.left().z()),0,p.halfWidth()));}
  line(central,outerA,curveA,na.left(),connector.width()/2);
  central.addAll(middle.samples().subList(central.isEmpty()?0:1,middle.samples().size()));
  line(central,curveB,outerB,nb.mul(-1).left(),connector.width()/2);
  if(cutB>0){var suffix=RoadSlicing.slice(approachB,0,cutB,connector).mesh().samples();central.addAll(suffix.subList(1,suffix.size()));}
  middle=RoadRibbon.mesh(central,connector);
  if(!o.flatConnector()){
   var graded=new ArrayList<Sample>();for(var p:middle.samples()){double t=p.distance()/middle.length(),blend=t*t*t*(10+t*(-15+6*t));graded.add(new Sample(new V(p.center().x(),ya+(yb-ya)*blend,p.center().z()),p.left(),0,p.halfWidth()));}
   middle=RoadRibbon.mesh(graded,connector);
  }
  if(RoadGrades.maximum(middle)>TollPlanner.MAX_GRADE+1e-6)throw new IllegalArgumentException("双喇叭特殊段纵坡超过收费站 12% 上限，请扩大范围或启用水平段");
  Leg la=legs.get(2),lb=legs.get(secondStart);
  legs.set(2,new Leg(la.name(),la.from(),la.to(),throat(approachA,cutA)));
  legs.set(secondStart,new Leg(lb.name(),lb.from(),lb.to(),throat(approachB,cutB)));
  if(o.toll().enabled())legs.addAll(DoubleTrumpetToll.split(middle,o));else legs.add(new Leg(CONNECTOR,-1,-1,middle));
  // Each trumpet has already passed its own exact throat and ramp checks.
  // Check every newly introduced cross-group pair and the central road.
  for(int i=0;i<legs.size();i++)for(int j=0;j<i;j++){
   boolean firstI=i==0||i>=2&&i<secondStart,firstJ=j==0||j>=2&&j<secondStart;
   if(!DoubleTrumpetToll.connector(legs.get(i).name())&&!DoubleTrumpetToll.connector(legs.get(j).name())&&firstI==firstJ)continue;
   Mesh x=legs.get(i).mesh(),y=legs.get(j).mesh();
   for(var contact:RoadClearance.detailedContacts(x,y)){
    if(sharedEnd(x,y,contact))continue;
    if(contact.contact().usableClearance()+1e-6<Math.max(o.clearance(),contact.contact().required()))throw new IllegalArgumentException(legs.get(i).name()+"与"+legs.get(j).name()+"净空不足，请扩大双喇叭范围");
   }
  }
  return new Plan(List.copyOf(legs),8,legs.stream().filter(l->l.mesh().settings().style().ramp()).mapToDouble(l->RoadRibbon.minRadius(l.mesh())).min().orElse(o.radius()),legs.stream().mapToDouble(l->l.mesh().max().y()).max().orElse(0),center,List.of(nodes));
 }
 private static Mesh throat(Mesh source,double from){
  var s=source.settings();var ends=s.options().ends();
  var local=s.options(s.options().ends(ends.trim(from,0)));
  return RoadSlicing.slice(source,from,source.length(),local).mesh();
 }
 private static int arm(int child,int axis){return child==0?axis:child==2?axis+2:-1;}
 private static double connectorStart(Mesh approach,Plan trumpet){
  double cut=approach.length()-2,half=approach.first().halfWidth();
  for(var p:approach.samples())if(Math.abs(p.center().y()-approach.first().center().y())>1e-8)return 0;
  for(var p:approach.samples())if(Math.abs(p.halfWidth()-half)>1e-6){cut=Math.min(cut,p.distance()-1);break;}
  for(var leg:trumpet.legs())if(leg.mesh().settings().style().ramp())for(var c:RoadClearance.detailedContacts(approach,leg.mesh()))cut=Math.min(cut,c.contact().from()-1);
  if(cut<1)throw new IllegalArgumentException("双喇叭收束后没有完整中央断面，请扩大范围");return cut;
 }
 private static V landing(V base,V outward,Options o){
  if(!o.flatConnector()||Math.abs(base.y()-o.connectorHeight())<1e-8)return base;
  // Place the vertical approach wholly beyond the trumpet's already validated mouths.
  double run=1.875*Math.abs(base.y()-o.connectorHeight())/RoadGrades.MAX_RAMP_GRADE+12;
  V p=base.add(outward.mul(run));return new V(p.x(),o.connectorHeight(),p.z());
 }
 private static Mesh approach(Mesh mesh,V outer){
  V end=mesh.first().center();if(end.distance(outer)<1e-7)return mesh;
  int count=(int)Math.ceil(end.distance(outer)/.5);var points=new ArrayList<Sample>();
  for(int i=0;i<count;i++){double t=i/(double)count,blend=t*t*t*(10+t*(-15+6*t));V p=outer.add(end.sub(outer).mul(t));points.add(new Sample(new V(p.x(),outer.y()+(end.y()-outer.y())*blend,p.z()),mesh.first().left(),0,mesh.first().halfWidth()));}
  points.addAll(mesh.samples());var result=RoadRibbon.mesh(points,mesh.settings());
  if(RoadGrades.maximum(result)>RoadGrades.MAX_RAMP_GRADE+1e-6)throw new IllegalArgumentException("双喇叭水平段引道坡度超过 15%，请扩大范围");return result;
 }
 private static Node near(Node n,V center,V axis){double d=n.position().sub(center).dot(axis);V at=center.add(axis.mul(Math.max(-900,Math.min(900,d))));return new Node(new V(at.x(),n.position().y(),at.z()),RoadPlanner.yaw(axis),0);}
 private static Mesh extend(Mesh mesh,Node a,Node b){
  var points=new ArrayList<Sample>();line(points,a.position(),mesh.first().center(),mesh.first().left(),mesh.first().halfWidth());if(!points.isEmpty())points.remove(points.size()-1);points.addAll(mesh.samples());line(points,mesh.last().center(),b.position(),mesh.last().left(),mesh.last().halfWidth());
  return RoadRibbon.mesh(points,mesh.settings());
 }
 private static void line(List<Sample> out,V a,V b,V left,double width){if(a.distance(b)<1e-7)return;int n=(int)Math.ceil(a.distance(b)/.5);for(int i=0;i<=n;i++){if(i==0&&!out.isEmpty())continue;out.add(new Sample(a.add(b.sub(a).mul(i/(double)n)),left,0,width));}}
 private static boolean sharedEnd(Mesh a,Mesh b,RoadClearance.DetailedContact c){
  if(Math.max(Math.abs(c.minimumDifference()),Math.abs(c.maximumDifference()))>1e-5)return false;
  for(var x:List.of(a.first(),a.last()))for(var y:List.of(b.first(),b.last()))if(x.center().distance(y.center())<1e-5&&Math.abs(x.left().dot(y.left()))>.99999&&Math.min(c.contact().from(),a.length()-c.contact().to())<.01&&Math.min(c.otherFrom(),b.length()-c.otherTo())<.01)return true;
  return false;
 }
 private static Mesh connector(V a,V b,V from,V to,Settings settings,Options o){
  double handle=a.sub(b).horizontalLength()*.55;V p=a.add(from.mul(handle)),q=b.sub(to.mul(handle));int n=Math.max(16,(int)Math.ceil((a.distance(p)+p.distance(q)+q.distance(b))/.5));
  var samples=new ArrayList<Sample>();
  for(int i=0;i<=n;i++){double t=i/(double)n,w=1-t;V at=a.mul(w*w*w).add(p.mul(3*w*w*t)).add(q.mul(3*w*t*t)).add(b.mul(t*t*t));double blend=t*t*t*(10+t*(-15+6*t));at=new V(at.x(),a.y()+(b.y()-a.y())*blend,at.z());V d=p.sub(a).mul(3*w*w).add(q.sub(p).mul(6*w*t)).add(b.sub(q).mul(3*t*t));samples.add(new Sample(at,d.horizontalUnit().left(),0,settings.width()/2));}
  // Grade is checked after the full-width approaches have been incorporated;
  // this short middle curve is only one part of the usable vertical transition.
  var mesh=RoadRibbon.mesh(samples,settings);RoadRibbon.checkSelfIntersections(mesh,o.clearance());if(RoadRibbon.minRadius(mesh)+.1<o.radius())throw new IllegalArgumentException("双喇叭中央连接段半径不足，请扩大范围");return mesh;
 }
 private DoubleTrumpetPlanner(){}
}
