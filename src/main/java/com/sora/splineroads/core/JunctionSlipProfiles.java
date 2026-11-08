package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.JunctionPlanner.*;
import java.util.*;

/** One lateral map for the deck, furniture and paint of each auxiliary lane. */
public final class JunctionSlipProfiles {
 private static double smooth(double t){return Settings.smooth(Math.max(0,Math.min(1,t)));}
 final JunctionSpec spec;final List<Mesh> approaches;final boolean exact,smoothEdges;
 public JunctionSlipProfiles(JunctionSpec s,List<Mesh> m){this(s,m,true,true);}
 public JunctionSlipProfiles(JunctionSpec s,List<Mesh> m,boolean exact){this(s,m,exact,false);}
 public JunctionSlipProfiles(JunctionSpec s,List<Mesh> m,boolean exact,boolean smoothEdges){spec=s;approaches=m;this.exact=exact;this.smoothEdges=smoothEdges;}
 private List<Sample> samples(int arm){
  var m=approaches.get(arm);if(!exact)return m.samples();var stations=new TreeSet<Double>();for(var p:m.samples())stations.add(p.distance());
  for(int side:new int[]{-1,1}){double start=cut(arm,side);if(!Double.isFinite(start))continue;for(double delta:new double[]{-32,0,8,24}){double d=start+delta;if(d>0&&d<m.length()){stations.removeIf(v->Math.abs(v-d)<1e-7);stations.add(d);}}}
  return stations.stream().map(d->RoadSlicing.at(m,d)).toList();
 }
 public double addition(int i,double d){var a=spec.arms().get(i);if(!a.rightSlip())return 0;int to=JunctionSlips.target(spec,i);double split=approaches.get(i).length()-JunctionSlips.run(spec,i,to);double t=Math.max(0,(d-split+32)/32);return a.laneWidth()*(smoothEdges?smooth(t):t*t)*(1-smooth((d-split)/24));}
 public double reservation(int i,double d){if(!JunctionSlips.receives(spec,i))return 0;int from=0;while(!spec.arms().get(from).rightSlip()||JunctionSlips.target(spec,from)!=i)from++;double join=approaches.get(i).length()-JunctionSlips.run(spec,from,i);return spec.arms().get(i).laneWidth()*smooth((d-join)/8);}
 public V map(int i,V p){var q=RoadQueries.horizontal(approaches.get(i),p);var a=spec.arms().get(i);double x=q.lateral(),d=q.sample().distance(),delta=0;
  double high=a.motorEdge(true,false),low=a.motorEdge(false,false),width=a.laneWidth();
  if(x>=high-.3)delta+=addition(i,d);
  if(x<low+width)delta+=reservation(i,d)*Math.min(1,(low+width-x)/width);
  return p.add(q.sample().left().mul(delta));
 }
 private int arm(V p){int best=-1;double distance=Double.POSITIVE_INFINITY;for(int i=0;i<approaches.size();i++){var q=RoadQueries.horizontal(approaches.get(i),p);double d=q.horizontalDistance();if(d<distance){distance=d;best=i;}}return best;}
 private double cut(int arm,int side){for(int i=0;i<spec.arms().size();i++)if(spec.arms().get(i).rightSlip()){int to=JunctionSlips.target(spec,i);if(side>0&&i==arm||side<0&&to==arm)return approaches.get(arm).length()-JunctionSlips.run(spec,i,to);}return Double.POSITIVE_INFINITY;}
 private double edge(int i,int side,double d){var a=spec.arms().get(i);return side>0?a.motorEdge(true,false):a.motorEdge(false,false)+reservation(i,d);}
 /** Old cycle spurs now belong to the refuge; cycling continues only outside the bypass. */
 public List<Mesh> refugeStrips(){var result=new ArrayList<Mesh>();for(int i=0;i<approaches.size();i++)for(int side:new int[]{-1,1}){double start=cut(i,side);if(!Double.isFinite(start))continue;var points=new ArrayList<Sample>();for(var p:samples(i))if(p.distance()>=start-1e-8){V inner=p.at(edge(i,side,p.distance()),0),outer=map(i,p.at(side*p.halfWidth(),0));V delta=outer.sub(inner);points.add(new Sample(inner.add(outer).mul(.5),delta.horizontalUnit(),0,delta.horizontalLength()/2));}if(points.size()>1)result.add(footprint(points,JunctionPlanner.settings(4,spec.thickness())));}return List.copyOf(result);}
 Mesh approachMesh(int i){var points=new ArrayList<Sample>();for(var p:samples(i)){V low=(exact?p.distance()>cut(i,-1)+1e-8:p.distance()>=cut(i,-1))?p.at(edge(i,-1,p.distance()),0):map(i,p.at(-p.halfWidth(),0));V high=(exact?p.distance()>cut(i,1)+1e-8:p.distance()>=cut(i,1))?p.at(edge(i,1,p.distance()),0):map(i,p.at(p.halfWidth(),0));points.add(new Sample(low.add(high).mul(.5),p.left(),p.distance(),high.sub(low).horizontalLength()/2));}var m=approaches.get(i);var bounds=new ArrayList<V>();for(var p:points){bounds.add(p.at(-p.halfWidth(),0));bounds.add(p.at(p.halfWidth(),0));}return new Mesh(List.copyOf(points),m.settings(),new V(bounds.stream().mapToDouble(V::x).min().orElseThrow(),m.min().y(),bounds.stream().mapToDouble(V::z).min().orElseThrow()),new V(bounds.stream().mapToDouble(V::x).max().orElseThrow(),m.max().y(),bounds.stream().mapToDouble(V::z).max().orElseThrow()),m.length(),false);}
 /** Clipped verge footprints can be thinner than a drivable road ribbon. */
 private static Mesh footprint(List<Sample> raw,Settings settings){var samples=new ArrayList<Sample>();var bounds=new ArrayList<V>();double d=0;V before=null;for(var p:raw){if(before!=null)d+=before.distance(p.center());samples.add(new Sample(p.center(),p.left(),d,p.halfWidth()));bounds.add(p.at(-p.halfWidth(),0));bounds.add(p.at(p.halfWidth(),0));before=p.center();}return new Mesh(List.copyOf(samples),settings,new V(bounds.stream().mapToDouble(V::x).min().orElseThrow(),bounds.stream().mapToDouble(V::y).min().orElseThrow()-settings.thickness(),bounds.stream().mapToDouble(V::z).min().orElseThrow()),new V(bounds.stream().mapToDouble(V::x).max().orElseThrow(),bounds.stream().mapToDouble(V::y).max().orElseThrow(),bounds.stream().mapToDouble(V::z).max().orElseThrow()),d,false);}
 public V map(V p){return map(arm(p),p);}
 public Part part(int i,Part p){
  V foot=exact?RoadStreetscape.lampFoot(p):null;if(foot!=null){V delta=(i<0?map(foot):map(i,foot)).sub(foot);return new Part(p.a().add(delta),p.b().add(delta),p.width(),p.height(),p.pier(),p.material(),p.frameA(),p.frameB(),p.model(),p.heights());}
  V a=i<0?map(p.a()):map(i,p.a()),b=i<0?map(p.b()):map(i,p.b());if(p.pier())return new Part(a,b,p.width(),p.height(),true,p.material(),p.frameA(),p.frameB(),p.model(),p.heights());var corners=p.base();V fa=(i<0?map(corners.get(0)):map(i,corners.get(0))).sub(a),fb=(i<0?map(corners.get(3)):map(i,corners.get(3))).sub(b);return new Part(a,b,p.width(),p.height(),p.pier(),p.material(),fa,fb,p.model(),p.heights());}
 private Mesh mesh(int i,Mesh m){var samples=new ArrayList<Sample>();var bounds=new ArrayList<V>();for(var p:m.samples()){V a=i<0?map(p.at(-p.halfWidth(),0)):map(i,p.at(-p.halfWidth(),0)),b=i<0?map(p.at(p.halfWidth(),0)):map(i,p.at(p.halfWidth(),0));V delta=b.sub(a);samples.add(new Sample(a.add(b).mul(.5),delta.horizontalLength()<1e-8?p.left():delta.horizontalUnit(),p.distance(),delta.horizontalLength()/2));bounds.add(a);bounds.add(b);}return new Mesh(List.copyOf(samples),m.settings(),new V(bounds.stream().mapToDouble(V::x).min().orElseThrow(),m.min().y(),bounds.stream().mapToDouble(V::z).min().orElseThrow()),new V(bounds.stream().mapToDouble(V::x).max().orElseThrow(),m.max().y(),bounds.stream().mapToDouble(V::z).max().orElseThrow()),m.length(),m.closed());}
 public Plan apply(Plan p){var pieces=new ArrayList<Piece>();for(var q:p.pieces()){int i=q.arm();var paint=new ArrayList<RoadSurface.Face>();for(var f:q.paint()){var points=f.points().stream().map(v->i<0?v:map(i,v)).toList();if(Math.abs(JunctionPaint.area(points))>1e-8)paint.add(new RoadSurface.Face(points,f.color()));}pieces.add(new Piece(i<0?q.mesh():approachMesh(i),List.copyOf(paint),q.structures().stream().map(v->i<0?v:part(i,v)).toList(),i));}var heads=new HashMap<Part,JunctionSignals.Head>();p.heads().forEach((part,head)->heads.put(part(head.arm(),part),head));return new Plan(List.copyOf(pieces),p.movements(),p.boundary(),p.sidewalks(),p.signals(),Map.copyOf(heads));}
}
