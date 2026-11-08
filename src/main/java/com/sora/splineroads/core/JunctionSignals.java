package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;

/** Far-side vehicle signals and two opposing pedestrian heads for each crossing. */
public final class JunctionSignals {
  /** Vehicle masts are planned first so an adjacent crossing can share a real post. */
  public record Head(int arm,int lane,boolean left) {}
  public record Layout(List<List<Part>> structures,List<Integer> pedestrianArms,Map<Part,Head> heads){}
  private record Mast(V base,int arm){}
  private record Placement(List<Mesh> approaches,List<Mesh> obstacles,List<Part> paving){}
  /** Regenerate complete groups after the auxiliary lanes and refuge paving have their final outlines. */
  static JunctionPlanner.Plan afterSlips(JunctionSpec s,JunctionPlanner.Plan plan,List<Mesh> original){
    var approaches=plan.pieces().subList(0,s.arms().size()).stream().map(JunctionPlanner.Piece::mesh).toList();
    var obstacles=new ArrayList<>(approaches);obstacles.addAll(JunctionSlips.meshes(plan));
    var paving=plan.pieces().stream().flatMap(p->p.structures().stream()).filter(p->p.material().name().startsWith("WALK_")).toList();
    var layout=structures(s,original,plan.boundary(),true,true,new Placement(approaches,obstacles,paving));
    var pieces=new ArrayList<>(plan.pieces());
    for(int i=0;i<s.arms().size();i++){var p=pieces.get(i);var parts=new ArrayList<>(p.structures());parts.addAll(layout.structures().get(i));pieces.set(i,new JunctionPlanner.Piece(p.mesh(),p.paint(),List.copyOf(parts),p.arm()));}
    return new JunctionPlanner.Plan(List.copyOf(pieces),plan.movements(),plan.boundary(),plan.sidewalks(),new JunctionSignalCycle(s,layout.pedestrianArms()),layout.heads());
  }
  public static Layout structures(JunctionSpec s,List<Mesh> approaches,List<V> boundary) {
    return structures(s,approaches,boundary,true,true);
  }
  public static Layout structures(JunctionSpec s,List<Mesh> approaches,List<V> boundary,boolean grouped) {
    return structures(s,approaches,boundary,grouped,false);
  }
  public static Layout structures(JunctionSpec s,List<Mesh> approaches,List<V> boundary,boolean grouped,boolean sharedPhase) {
    return structures(s,approaches,boundary,grouped,sharedPhase,new Placement(approaches,approaches,List.of()));
  }
  private static Layout structures(JunctionSpec s,List<Mesh> approaches,List<V> boundary,boolean grouped,boolean sharedPhase,Placement placement){
    var result=new ArrayList<List<Part>>();var masts=new ArrayList<Mast>();var heads=new LinkedHashMap<Part,Head>();
    for(int i=0;i<approaches.size();i++)result.add(vehicle(s,i,approaches,boundary,masts,heads,grouped,sharedPhase,placement));
    var controllers=new ArrayList<Integer>();
    for(int i=0;i<approaches.size();i++)controllers.add(pedestrians(s,i,approaches,boundary,masts,result.get(i),placement));
    return new Layout(result.stream().map(List::copyOf).toList(),List.copyOf(controllers),Map.copyOf(heads));
  }
  /** Ordinary circular heads exclude a protected left group and the optional right group. */
  public static int normalMask(JunctionSpec spec,int arm,int lane){
    int mask=0;var lanes=spec.arms().get(arm).lanes();boolean protectedLeft=false;
    for(int i=0;i<lanes.size();i++)protectedLeft|=lanes.get(i).splitLeft()&&(JunctionPlanner.mask(spec,arm,i)&(JunctionSpec.LEFT|JunctionSpec.UTURN))!=0;
    for(int i=0;i<lanes.size();i++)if(lane<0||lane==i){int next=JunctionPlanner.mask(spec,arm,i)&~JunctionSpec.RIGHT;if(protectedLeft)next&=~(JunctionSpec.LEFT|JunctionSpec.UTURN);mask|=next;}
    return mask;
  }
  public static boolean rightControlled(JunctionSpec spec,int arm,int lane){
    var lanes=spec.arms().get(arm).lanes();boolean enabled=lanes.stream().anyMatch(Lane::controlRight);
    if(!enabled)return false;
    for(int i=0;i<lanes.size();i++)if((lane<0||lane==i)&&(JunctionPlanner.mask(spec,arm,i)&JunctionSpec.RIGHT)!=0)return true;
    return false;
  }
  /** Refresh only the heads of saved junctions, preserving terrain-fitted masts and all other structures. */
  public static List<Part> refreshHeads(List<Part> saved,List<Part> current){
    if(saved.isEmpty())return saved;
    var result=new ArrayList<Part>();for(var p:saved)if(!RoadJunctionSignalModel.supports(p))result.add(p);
    for(var p:current)if(RoadJunctionSignalModel.supports(p))result.add(p);
    return List.copyOf(result);
  }
  private static List<Part> vehicle(JunctionSpec s,int index,List<Mesh> approaches,List<V> boundary,List<Mast> masts,Map<Part,Head> heads,boolean grouped,boolean sharedPhase,Placement placement) {
    Arm arm=s.arms().get(index);Mesh mesh=approaches.get(index);var parts=new ArrayList<Part>();
    V forward=mesh.last().left().left().mul(-1);double sign=s.leftTraffic()?-1:1;
    boolean leftControlled=false;for(int lane=0;lane<arm.incoming();lane++)leftControlled|=arm.lanes().get(lane).splitLeft()&&(JunctionPlanner.mask(s,index,lane)&(JunctionSpec.LEFT|JunctionSpec.UTURN))!=0;
    if(arm.incoming()>0&&(normalMask(s,index,-1)!=0||leftControlled||rightControlled(s,index,-1))) {
      double middle=(arm.divider(s.leftTraffic())+sign*arm.median()/2+arm.motorEdge(true,s.leftTraffic()))/2;
      V head,foot;int opposite=-1;double best=.85;
      for(int j=0;j<approaches.size();j++)if(j!=index){double dot=approaches.get(j).last().center().sub(s.center()).horizontalUnit().dot(forward);if(dot>best){best=dot;opposite=j;}}
      if(s.kind()==Kind.ROUNDABOUT) {
        Sample far=RoadStructures.sample(mesh,Math.max(0,mesh.length()-.7));
        head=far.at(middle,-6.8);foot=foot(far,arm,sign,s,approaches,boundary,placement);
      } else if(opposite>=0) {
        Arm other=s.arms().get(opposite);Mesh farMesh=approaches.get(opposite);
        double back=other.crossingSetback()+(other.crosswalk()?other.crossingWidth():0)+1;
        Sample far=RoadStructures.sample(farMesh,Math.max(0,farMesh.length()-back));
        head=far.center().add(forward.left().mul(middle)).add(new V(0,6.8,0));
        foot=foot(far,other,-sign,s,approaches,boundary,placement);
      } else {
        V origin=s.center().add(forward.left().mul(middle));double far=0;
        for(double d=0;d<=JunctionPlanner.radius(s)*2+arm.width();d+=.25)if(JunctionPaint.inside(boundary,origin.add(forward.mul(d))))far=d;
        V base=origin.add(forward.mul(far+.8));head=base.add(new V(0,6.8,0));
        foot=outside(base.add(forward.left().mul(sign*(arm.width()/2+.6)-middle)),forward.left().mul(sign),s,approaches,boundary,placement);
      }
      // The lug meets the mast arm behind the vertical housing, with its base
      // standing on the curb top instead of cutting through the curb mesh.
      double beamY=head.y()+.796875;pole(parts,foot,forward,beamY-foot.y()+.25);masts.add(new Mast(foot,index));
      V top=new V(foot.x(),beamY,foot.z());
      var positions=new ArrayList<V>();
      boolean individual=arm.lanes().stream().anyMatch(Lane::splitLeft);
      if(grouped) {
        // All ordinary lanes share the arm's one phase; adding a protected turn
        // must never replicate that same ordinary head above every lane.
        boolean normal=normalMask(s,index,-1)!=0;var leftGroups=new LinkedHashMap<Integer,Integer>();
        for(int lane=0;lane<arm.incoming();lane++) {
          Lane config=arm.lanes().get(lane);int mask=JunctionPlanner.mask(s,index,lane);
          boolean split=config.splitLeft()&&(mask&(JunctionSpec.LEFT|JunctionSpec.UTURN))!=0;
          if(split)leftGroups.putIfAbsent(sharedPhase?arm.phase():config.effectiveLeftPhase(index),lane);
        }
        if(normal){Part p=new Part(head,head.sub(forward.mul(.939)),.657,1.594,false,Material.SIGNAL_VEHICLE);parts.add(p);heads.put(p,new Head(index,-1,false));positions.add(head);}
        int at=0;
        for(int lane:leftGroups.values()) {
          V p=head.sub(forward.left().mul((normal||rightControlled(s,index,-1)?1:0)+at++));
          Part part=new Part(p,p.sub(forward.mul(.939)),.657,1.594,false,Material.SIGNAL_LEFT);
          parts.add(part);heads.put(part,new Head(index,lane,true));positions.add(p);
        }
        if(rightControlled(s,index,-1)){
          V p=head.add(forward.left().mul(normal?1:0));
          Part part=new Part(p,p.sub(forward.mul(.939)),.657,1.594,false,Material.SIGNAL_VEHICLE,null,null,"sr:signal_right");
          parts.add(part);heads.put(part,new Head(index,-1,false));positions.add(p);
        }
      } else if(!individual&&normalMask(s,index,-1)!=0) {
        Part p=new Part(head,head.sub(forward.mul(.939)),.657,1.594,false,Material.SIGNAL_VEHICLE);
        parts.add(p);heads.put(p,new Head(index,-1,false));positions.add(head);
      } else for(int lane=0;lane<arm.incoming();lane++) {
        Lane config=arm.lanes().get(lane);int mask=JunctionPlanner.mask(s,index,lane);
        V center=head.add(forward.left().mul(arm.laneCenter(true,lane,s.leftTraffic())-middle));
        boolean separate=config.splitLeft()&&(mask&(JunctionSpec.LEFT|JunctionSpec.UTURN))!=0;
        boolean normal=normalMask(s,index,lane)!=0;
        if(normal) {
          V p=center.add(forward.left().mul(separate?.48:0));
          Part part=new Part(p,p.sub(forward.mul(.939)),.657,1.594,false,Material.SIGNAL_VEHICLE);
          parts.add(part);heads.put(part,new Head(index,lane,false));positions.add(p);
        }
        if(separate) {
          V p=center.sub(forward.left().mul(normal?.48:0));
          Part part=new Part(p,p.sub(forward.mul(.939)),.657,1.594,false,Material.SIGNAL_LEFT);
          parts.add(part);heads.put(part,new Head(index,lane,true));positions.add(p);
        }
      }
      if(!grouped&&rightControlled(s,index,-1)){
        // Old saved junction geometry keeps its layout, but supports the new opt-in head.
        double offset=positions.stream().mapToDouble(p->p.sub(head).dot(forward.left())).max().orElse(-1)+1;
        V p=head.add(forward.left().mul(offset));
        Part part=new Part(p,p.sub(forward.mul(.939)),.657,1.594,false,Material.SIGNAL_VEHICLE,null,null,"sr:signal_right");
        parts.add(part);heads.put(part,new Head(index,-1,false));positions.add(p);
      }
      V far=positions.stream().max(Comparator.comparingDouble(p->p.sub(top).horizontalLength())).orElse(head);
      V mount=new V(far.x(),beamY,far.z()),span=mount.sub(top).horizontalUnit();
      parts.add(new Part(top,mount.add(span.mul(.5)),.22,.22,false,Material.CB_ARM));
    }
    return parts;
  }
  private static int pedestrians(JunctionSpec s,int index,List<Mesh> approaches,List<V> boundary,List<Mast> masts,List<Part> parts,Placement placement) {
    Arm arm=s.arms().get(index);Mesh mesh=approaches.get(index);
    if(!arm.crosswalk())return index;
    double overlap=s.kind()==Kind.ROUNDABOUT?s.islandRadius()+s.ringLanes()*s.ringLaneWidth()-mesh.last().center().sub(s.center()).horizontalLength():0;
    // Walkers wait at the intersection-side edge, not midway alongside the stripes.
    Sample crossing=RoadStructures.sample(mesh,Math.max(0,mesh.length()-overlap-arm.crossingSetback()+.65));
    V forward=crossing.left().left().mul(-1);
    int controller=index;double nearest=Double.POSITIVE_INFINITY;
    for(int side:new int[]{-1,1}) {
      V base=foot(crossing,arm,side,s,approaches,boundary,placement),front=crossing.left().mul(-side);
      V shared=null;double best=arm.crossingWidth()+2;
      for(Mast candidate:masts) {
        V mast=candidate.base();V delta=mast.sub(base);double distance=delta.horizontalLength();
        // Same sidewalk edge and elevation only: never borrow a post across a lane.
        if(distance<=best && Math.abs(delta.dot(crossing.left()))<=1.25
            && Math.abs(delta.y())<=.3 && mast.sub(crossing.center()).dot(crossing.left())*side>crossing.halfWidth()) {
          shared=mast;best=distance;if(distance<nearest){nearest=distance;controller=candidate.arm();}
        }
      }
      if(shared==null)pole(parts,base,forward,3.45);else base=shared;
      V mount=base.add(front.mul(.19)).add(new V(0,2.3,0));
      parts.add(new Part(mount,mount.add(front.mul(.688)),.563,1.038,false,Material.SIGNAL_PEDESTRIAN));
    }
    return controller;
  }
  private static V foot(Sample sample,Arm arm,double side,JunctionSpec spec,List<Mesh> approaches,List<V> boundary,Placement placement) {
    // The entire base stands beyond the drivable union, including curved corners.
    Sample actual=RoadStructures.sample(placement.approaches().get(spec.arms().indexOf(arm)),sample.distance());
    return outside(actual.at(side*(actual.halfWidth()+.7),0),actual.left().mul(side),spec,approaches,boundary,placement);
  }
  private static V outside(V base,V outward,JunctionSpec spec,List<Mesh> approaches,List<V> boundary,Placement placement){
    // Try the same sidewalk locally before considering a nearby outside corner.
    for(int step=0;step<=32;step++){
      V p=base.add(outward.mul(step*.25));if(clearFoot(p,placement.obstacles(),boundary))return onWalk(p,spec,placement);
    }
    V nearest=null;double best=Double.POSITIVE_INFINITY,sign=JunctionPaint.area(boundary)>=0?-1:1;
    for(int k=0;k<boundary.size();k++){
      V a=boundary.get(k),b=boundary.get((k+1)%boundary.size());V normal=b.sub(a).horizontalUnit().left().mul(sign);
      for(double offset:new double[]{.7,1,1.5,2,3}){
        V p=a.add(b).mul(.5).add(normal.mul(offset));double distance=p.sub(base).horizontalLength();
        if(distance<best&&clearFoot(p,placement.obstacles(),boundary)){best=distance;nearest=p;}
      }
    }
    if(nearest!=null)return onWalk(nearest,spec,placement);
    throw new IllegalArgumentException("路口外侧空间不足，无法放置信号灯柱");
  }
  private static boolean clearFoot(V base,List<Mesh> approaches,List<V> boundary){
    if(JunctionPaint.inside(boundary,base))return false;
    for(int k=0;k<boundary.size();k++){
      V a=boundary.get(k),b=boundary.get((k+1)%boundary.size()),d=b.sub(a);
      double t=Math.max(0,Math.min(1,base.sub(a).dot(d)/Math.max(1e-9,d.dot(d))));
      if(base.distance(a.add(d.mul(t)))<.55)return false;
    }
    // A center just past a flat ribbon end can still put half the base inside a lane.
    // The 1m square encloses the complete rotated 0.7m CB pedestal.
    for(Mesh approach:approaches)if(RoadSidewalks.overlapsDeck(new Part(base,base,1.12,1,true,Material.CB_BASE),approach))return false;
    for(Mesh approach:approaches)for(double dx:new double[]{-.5,0,.5})for(double dz:new double[]{-.5,0,.5})
      if(RoadQueries.contains(approach,base.add(new V(dx,0,dz)),.03,.6))return false;
    return true;
  }
  private static V onWalk(V base,JunctionSpec spec,Placement placement){
    for(var p:placement.paving())if(JunctionPaint.inside(p.base(),base))return new V(base.x(),Math.max(p.a().y(),p.b().y())+p.height(),base.z());
    var approaches=placement.approaches();
    for(int i=0;i<approaches.size();i++){
      var q=RoadQueries.horizontal(approaches.get(i),base);var c=spec.arms().get(i).external().options().sidewalk();int side=q.lateral()<0?-1:1;
      if(c.enabled()&&(c.side()==RoadSidewalks.Side.BOTH||c.side()==RoadSidewalks.Side.LEFT&&side<0||c.side()==RoadSidewalks.Side.RIGHT&&side>0)
          &&Math.abs(q.lateral())<q.sample().halfWidth()+c.width()&&q.horizontalDistance()<q.sample().halfWidth()+c.width())return base.add(new V(0,.2,0));
    }
    return base;
  }
  private static void pole(List<Part> out,V foot,V forward,double height) {
    out.add(new Part(foot.sub(forward.mul(.35)),foot.add(forward.mul(.35)),.7,1,false,Material.CB_BASE));
    out.add(new Part(foot.sub(forward.mul(.19)),foot.add(forward.mul(.19)),.38,height,false,Material.CB_POST));
  }
  private JunctionSignals(){}
}
