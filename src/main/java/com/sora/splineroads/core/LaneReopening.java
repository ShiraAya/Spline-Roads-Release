package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** A temporary lane cut reopens beyond the LAST 3-D obstruction, not a fixed A offset. */
public final class LaneReopening {
  private static final double SIDE_MARGIN=.30, END_MARGIN=1.0;
  public static double restoreStation(Mesh host,int slot,double begin,Mesh ramp,List<RoadStructures.Part> parts,double transition){
    return restoreStation(host,slot,begin,ramp,parts,transition,false);
  }
  public static double restoreStation(Mesh host,int slot,double begin,Mesh ramp,List<RoadStructures.Part> parts,double transition,boolean rectangular){
    var raw=LaneSections.reference(host);int sign=LanePoints.lane(raw,begin,slot).sign();var sweep=laneSweep(raw,slot);
    var prepared=RoadClearance.prepare(sweep);double last=sign*begin;
    last=lastBlocked(prepared,ramp,begin,sign,last);
    for(var part:parts){RoadPlanningBudget.check();for(var contact:structureContacts(prepared,sweep,part,ramp.settings()))if(contact.blocked()){double d=sign>0?contact.to():-contact.from();if(d>=sign*begin-.01)last=Math.max(last,d);}}
    double reopen=rectangular?Math.max(sign*begin+.25,last+END_MARGIN):Math.max(sign*begin+2*transition,last+END_MARGIN);
    double end=(reopen+(rectangular?0:transition))*sign;
    if(end<.001||end>raw.length()-.001)throw new IllegalArgumentException("匝道/结构未在本路段让出通行空间，或不足以渐变恢复车道；请延长主路或调整汇入位置/高程");
    return end;
  }
  /** Target lane closes before the FIRST unsafe upstream crossing, and remains closed
   * right up to B, where the connector terminates on its lane axis. Other slots are unchanged. */
  public static double closeBeforeStation(Mesh host,int slot,double end,Mesh ramp,List<RoadStructures.Part> parts,double transition){
    return closeBeforeStation(host,slot,end,ramp,parts,transition,false);
  }
  public static double closeBeforeStation(Mesh host,int slot,double end,Mesh ramp,List<RoadStructures.Part> parts,double transition,boolean rectangular){
    var raw=LaneSections.reference(host);int sign=LanePoints.lane(raw,end,slot).sign();
    var sweep=laneSweep(raw,slot);var prepared=RoadClearance.prepare(sweep);double first=sign*end;
    for(var contact:RoadClearance.contacts(prepared,ramp))if(contact.blocked()){
      double lo=sign>0?contact.from():-contact.to();if(lo<=sign*end+.01)first=Math.min(first,lo);
      double hi=sign>0?contact.to():-contact.from();
      if(hi>sign*end+.10&&!terminalSeam(raw,slot,end,ramp,contact))
        throw new IllegalArgumentException("汇入点之后仍有低净空横穿，不能开放目标车道（同高同向的正常接缝允许重合）");
    }
    var downstream=downstreamLane(raw,slot,end,sign);
    for(var part:parts){
      for(var contact:structureContacts(prepared,sweep,part,ramp.settings()))if(contact.blocked()){
        double lo=sign>0?contact.from():-contact.to();if(lo<=sign*end+.01)first=Math.min(first,lo);
      }
      if(downstream!=null&&RoadClearance.structureInvades(part,downstream,RoadClearance.REQUIRED))
        throw new IllegalArgumentException("汇入点之后的目标车道被实际结构构件挡住（不是正常路面接缝）");
    }
    double begin=(rectangular?Math.min(sign*end-.25,first-END_MARGIN):Math.min(sign*end-2*transition,first-transition-END_MARGIN))*sign;
    begin=Math.max(0,Math.min(raw.length(),begin));
    double available=sign*(end-begin),span=Math.min(transition,available/2);
    // At a free road start there is no upstream lane to taper: the slot starts
    // closed. LaneCrossSections rejects this fallback if a preceding road exists.
    if(!rectangular&&available>.02&&first-sign*begin<span-.01)begin=sign>0?-transition:raw.length()+transition;
    return begin;
  }
  private static boolean terminalSeam(Mesh host,int slot,double end,Mesh ramp,RoadClearance.Contact contact){
    var lane=LanePoints.lane(host,end,slot);var q=RoadQueries.horizontal(ramp,contact.other());
    double beyond=lane.sign()*((lane.sign()>0?contact.to():contact.from())-end);
    return beyond<=.5&&q.sample().distance()>=ramp.length()-1&&Math.abs(contact.ours().y()-contact.other().y())<.035
        &&q.sample().left().left().mul(-1).dot(lane.direction())>.995;
  }
  private static Mesh downstreamLane(Mesh raw,int slot,double end,int sign){
    double lo=sign>0?end+.12:raw.first().distance(),hi=sign>0?raw.length():end-.12;if(hi-lo<1e-5)return null;
    var stations=new TreeSet<Double>();stations.add(lo);stations.add(hi);
    for(var s:raw.samples())if(s.distance()>lo&&s.distance()<hi)stations.add(s.distance());
    var samples=new ArrayList<Sample>();for(double d:stations){var lane=LanePoints.lane(raw,d,slot);var s=RoadStructures.sample(raw,d);
      // Do not count a side rail resting on the lane rim as an overhead obstruction.
      samples.add(new Sample(lane.position(),s.left(),d,Math.max(.05,lane.width()/2-.22)));}
    var settings=raw.settings().options(raw.settings().options().lanePoints(LanePoints.Data.EMPTY));
    var m=RoadRibbon.mesh(samples,settings);return new Mesh(samples,settings,m.min(),m.max(),raw.length(),false,null);
  }
  private static double lastBlocked(Mesh sweep,Mesh obstacle,double begin,int sign,double last){return lastBlocked(RoadClearance.prepare(sweep),obstacle,begin,sign,last);}
  private static double lastBlocked(RoadClearance.Prepared sweep,Mesh obstacle,double begin,int sign,double last){
    for(var c:RoadClearance.contacts(sweep,obstacle))if(c.blocked()){
      double d=sign>0?c.to():-c.from();if(d>=sign*begin-.01)last=Math.max(last,d);
    }return last;
  }
  /** Authored stations are retained even for curved roads. */
  private static final java.util.concurrent.ConcurrentHashMap<Integer,WeakIdentityCache<Mesh,Mesh>> SWEEPS=new java.util.concurrent.ConcurrentHashMap<>();
  public static Mesh laneSweep(Mesh host,int slot){
    var raw=LaneSections.reference(host);
    // One sweep per requested slot is memoized by a bounded weak raw-host key.
    // Values contain their own clean settings/samples, never their host key.
    if(slot<0||slot>31)return makeLaneSweep(raw,slot);
    return SWEEPS.computeIfAbsent(slot,k->new WeakIdentityCache<>(8,8_000,v->v.samples().size())).get(raw,k->makeLaneSweep(k,slot));
  }
  private static Mesh makeLaneSweep(Mesh raw,int slot){var samples=new ArrayList<Sample>();int count=RoadProfile.catalog(raw.settings()).lanes();
    for(var s:raw.samples()){
      // A total-count change on another slot is not a reason to reject this lane.
      // LanePoints.lane still rejects a selected slot that actually ceases to exist.
      var lane=LanePoints.lane(raw,s.distance(),slot);samples.add(new Sample(lane.position(),s.left(),s.distance(),lane.width()/2+SIDE_MARGIN));
    }
    var clean=raw.settings().options(raw.settings().options().lanePoints(LanePoints.Data.EMPTY));
    var bounds=RoadRibbon.mesh(samples,clean);return new Mesh(List.copyOf(samples),clean,bounds.min(),bounds.max(),raw.length(),raw.closed(),null);
  }
  /** Tapered lining uses both actual planes, never its maximum-thickness envelope. */
  public static List<RoadClearance.Contact> structureContacts(RoadClearance.Prepared prepared,Mesh sweep,RoadStructures.Part part,Settings settings){
    return part.heights()==null?RoadClearance.contacts(prepared,envelope(part,settings)):RoadClearance.structureContacts(part,sweep,RoadClearance.REQUIRED);
  }
  /** Exact Part.base prism catches piers and tilted/offset beams after structure planning.
   * The mesh's slab thickness describes the entire prism, not a paper-thin top. */
  public static Mesh envelope(RoadStructures.Part part,Settings settings){
    var base=part.base();V first=base.get(0).add(base.get(1)).mul(.5),last=base.get(2).add(base.get(3)).mul(.5);
    V fa=base.get(0).sub(base.get(1)).mul(.5),fb=base.get(3).sub(base.get(2)).mul(.5);
    double wa=Math.sqrt(fa.dot(fa)),wb=Math.sqrt(fb.dot(fb)),length=first.sub(last).horizontalLength();
    V up=new V(0,part.height(),0);
    var points=List.of(new Sample(first.add(up),wa>1e-12?fa.mul(1/wa):new V(1,0,0),0,wa),
        new Sample(last.add(up),wb>1e-12?fb.mul(1/wb):new V(1,0,0),Math.max(1e-8,length),wb));
    var volume=new Settings(settings.mode(),settings.style(),settings.width(),part.height(),settings.tension(),settings.arcDegrees());
    double x0=base.stream().mapToDouble(V::x).min().orElseThrow(),x1=base.stream().mapToDouble(V::x).max().orElseThrow();
    double z0=base.stream().mapToDouble(V::z).min().orElseThrow(),z1=base.stream().mapToDouble(V::z).max().orElseThrow();
    double y0=base.stream().mapToDouble(V::y).min().orElseThrow(),y1=base.stream().mapToDouble(V::y).max().orElseThrow()+part.height();
    return new Mesh(points,volume,new V(x0,y0,z0),new V(x1,y1,z1),Math.max(1e-8,length),false,null);
  }

  public static void validateRestored(Mesh host,int slot,Mesh ramp,UUID connection){
    var cut=host.settings().options().lanePoints().cuts().stream().filter(c->c.connection().equals(connection)&&c.lane()==slot).findFirst();
    if(cut.isEmpty())return; // A free road end has no downstream lane to restore.
    var c=cut.get();
    // This record ends while the reservation continues on the next tracked host.
    // There is no restored material inside this record to validate at that seam.
    if(c.sign()>0&&c.end()>=host.length()-1e-7||c.sign()<0&&c.end()<=1e-7)return;
    double reopen=c.sign()*c.end()-(c.rectangular()?0:c.transition());
    if(lastBlocked(laneSweep(host,slot),ramp,c.begin(),c.sign(),c.sign()*c.begin())>=reopen-.01)
      throw new IllegalArgumentException("车道恢复范围仍有匝道净空冲突，已取消建造");
  }
  private LaneReopening(){}
}
