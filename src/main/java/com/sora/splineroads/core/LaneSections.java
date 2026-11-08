package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/**
 * A reversible lane reservation: the lane departs at begin and is optionally replaced at end.
 * Raw road samples remain authored data; rendering AND collision use the same effective ribbon.
 * Slot IDs never renumber when an outer lane is absent. An absent slot remains a REPLACE target.
 */
public final class LaneSections {
  public record Cut(UUID connection,int lane,int sign,double begin,double end,double transition,UUID replacement,boolean temporary,boolean arrival,boolean rectangular,boolean underpass) {
    public Cut(UUID connection,int lane,int sign,double begin,double end,double transition,UUID replacement,boolean temporary,boolean arrival,boolean rectangular){this(connection,lane,sign,begin,end,transition,replacement,temporary,arrival,rectangular,false);}
    public Cut(UUID connection,int lane,int sign,double begin,double end,double transition,UUID replacement,boolean temporary,boolean arrival){this(connection,lane,sign,begin,end,transition,replacement,temporary,arrival,false);}
    public Cut(UUID connection,int lane,int sign,double begin,double end,double transition,UUID replacement,boolean temporary){this(connection,lane,sign,begin,end,transition,replacement,temporary,false);}
    public Cut(UUID connection,int lane,int sign,double begin,double end,double transition,UUID replacement){this(connection,lane,sign,begin,end,transition,replacement,false);}
    public Cut(UUID connection,int lane,int sign,double begin,double end,double transition){this(connection,lane,sign,begin,end,transition,null);}
    public Cut {
      if(arrival&&!temporary||connection==null||lane<0||lane>31||(sign!=1&&sign!=-1)||!RoadGeometry.finite(begin,end,transition)
          ||transition<2||transition>256||sign*(end-begin)<-.0001)
        throw new IllegalArgumentException("车道分离区间无效");
    }
    public double removed(double station){
      double distance=sign*(end-begin);if(distance<1e-6)return 0;
      if(rectangular)return sign*(station-begin)>1e-8&&sign*(end-station)>1e-8?1:0;
      double span=Math.min(transition,distance/2);
      if(arrival)return sign*(station-end)>=-1e-8?0:smooth(sign*(station-begin)/span);
      return Math.min(smooth(sign*(station-begin)/span),smooth(sign*(end-station)/span));
    }
    private static double smooth(double t){return Settings.smooth(Math.max(0,Math.min(1,t)));}
  }
  public enum Kind { DEPART, REPLACE, TEMPORARY, ARRIVE }
  public record Event(UUID connection,Kind kind,int lane,int sign,double station,double transition,double returnStation,boolean rectangular,boolean underpass){
    public Event(UUID connection,Kind kind,int lane,int sign,double station,double transition,double returnStation,boolean rectangular){this(connection,kind,lane,sign,station,transition,returnStation,rectangular,false);}
    public Event(UUID connection,Kind kind,int lane,int sign,double station,double transition,double returnStation){this(connection,kind,lane,sign,station,transition,returnStation,false);}
    public Event(UUID connection,Kind kind,int lane,int sign,double station,double transition){this(connection,kind,lane,sign,station,transition,Double.NaN);}
  }
  /** Derive from the complete live link set, so deleting a branch restores authored pavement. */
  public static List<Cut> derive(Mesh mesh,List<Event> events){
    var raw=reference(mesh);var cuts=new ArrayList<Cut>();var used=new HashSet<UUID>();
    Set<UUID> provisional=new HashSet<>();for(var e:events)if(e.kind()==Kind.ARRIVE&&!Double.isFinite(e.returnStation()))provisional.add(e.connection());
    var ordered=new ArrayList<>(events);ordered.sort(Comparator.comparingDouble(e->e.station()*e.sign()));
    for(Event event:ordered)if(event.kind()!=Kind.REPLACE&&event.kind()!=Kind.ARRIVE){
      var lane=LanePoints.lane(raw,event.station(),event.lane());
      var layout=RoadProfile.layout(raw,RoadStructures.sample(raw,event.station()));
      var c=layout.catalog();
      if(event.sign()!=lane.sign())throw new IllegalArgumentException("分离方向与所选车道的实际行驶方向不一致");
      boolean outer=edge(raw,event.station(),event.lane(),cuts,event.connection());
      if(!outer&&event.kind()!=Kind.TEMPORARY)throw new IllegalArgumentException(c.twoWay()?"双向整车道分离请选择该方向最外侧车道，不能挖走内部车道或中央隔离":"整车道分离须选择单向主线的一侧边缘车道");
      double remainder=event.sign()>0?raw.length()-event.station():event.station();
      if(remainder<.02)continue; // A free road end already has no downstream continuation.
      if(c.lanes()+raw.settings().options().lanePoints().additions().stream().filter(a->a.fraction(event.station())>.999999).count()<=1&&event.kind()!=Kind.TEMPORARY)throw new IllegalArgumentException("单车道内部一分二请选择普通分流（原车道直行）；整车道分离会清空主路");
      Event replacement=null;
      for(Event candidate:ordered)if(event.kind()==Kind.DEPART&&candidate.kind()==Kind.REPLACE&&candidate.lane()==event.lane()&&candidate.sign()==event.sign()
          &&candidate.sign()*(candidate.station()-event.station())>1e-4){replacement=candidate;break;}
      double end=event.kind()==Kind.TEMPORARY&&Double.isFinite(event.returnStation())?event.returnStation():
          replacement==null?(event.sign()>0?raw.length()+2*event.transition():-2*event.transition()):replacement.station();
      if(event.kind()==Kind.TEMPORARY&&Double.isFinite(event.returnStation())&&
          (end<0||end>raw.length()||event.sign()*(end-event.station())<(event.rectangular()?.02:2*event.transition())))
        throw new IllegalArgumentException("保留车道分离没有足够空间完成封闭及安全恢复");
      for(Cut old:cuts)if(old.lane()==event.lane()&&old.sign()==event.sign()
          &&event.sign()*(event.station()-old.begin())>=0&&event.sign()*(event.station()-old.end())<-.01)
        throw new IllegalArgumentException("同一车道空位内重复整车道分离；Y 分叉请使用普通分流（原车道直行）");
      if(replacement!=null&&!used.add(replacement.connection()))throw new IllegalArgumentException("补入车道同时匹配多个分离接头");
      cuts.add(new Cut(event.connection(),event.lane(),event.sign(),event.station(),end,event.transition(),replacement==null?null:replacement.connection(),event.kind()==Kind.TEMPORARY,false,event.rectangular(),event.underpass()));
    }
    for(Event event:ordered)if(event.kind()==Kind.ARRIVE){
      var lane=LanePoints.lane(raw,event.station(),event.lane());
      if(lane.sign()!=event.sign())throw new IllegalArgumentException("汇入车道封闭方向与行驶方向不一致");
      double begin=Double.isFinite(event.returnStation())?event.returnStation():(event.sign()>0?0:raw.length());
      if(event.sign()*(event.station()-begin)<.02)continue; // Free start: no upstream target traffic.
      if(begin<-event.transition()-.001||begin>raw.length()+event.transition()+.001||event.station()<0||event.station()>raw.length())throw new IllegalArgumentException("汇入封闭范围超出实际宿主路段");
      var cut=new Cut(event.connection(),event.lane(),event.sign(),begin,event.station(),event.transition(),null,true,true,event.rectangular(),event.underpass());
      for(var old:cuts)if(!provisional.contains(cut.connection())&&!provisional.contains(old.connection())&&old.lane()==cut.lane()&&Math.min(Math.max(old.begin(),old.end()),Math.max(cut.begin(),cut.end()))-Math.max(Math.min(old.begin(),old.end()),Math.min(cut.begin(),cut.end()))>.01)
        throw new IllegalArgumentException("目标车道的汇入封闭与既有分离/汇入区间冲突，不能覆盖其他连接");
      cuts.add(cut);
    }
    for(Event event:ordered)if(event.kind()==Kind.REPLACE&&!used.contains(event.connection()))
      throw new IllegalArgumentException("补入模式需要上游已有整车道分离或车道点合流缩减形成空位；普通汇入请选并入现有车道");
    return List.copyOf(cuts);
  }
  public static Mesh reference(Mesh mesh){return mesh.reference()==null?mesh:mesh.reference();}
  public static double removed(Mesh mesh,double station,int slot){
    double removed=0;for(Cut cut:mesh.settings().options().lanePoints().cuts())if(cut.lane()==slot)removed=Math.max(removed,cut.removed(station));return removed;
  }
  public static boolean active(Mesh mesh,double station,int slot){return (!LaneAdditions.isAdded(mesh,slot)||LaneAdditions.find(mesh,slot).fraction(station)>.999999)&&removed(mesh,station,slot)<.5;}
  /** Current cross-section, with persistent slot identities kept separate from live counts. */
  public record Live(List<LanePoints.Lane> lanes,int forward,int reverse) {
    public Live {lanes=List.copyOf(lanes);}
    public int count(int sign){return sign>0?forward:reverse;}
    public String description(){return reverse==0?"单向 "+forward+" 车道":"双向 "+forward+"+"+reverse+" 车道";}
  }
  public static Live live(Mesh mesh,double station){
    var raw=reference(mesh);var sample=RoadStructures.sample(raw,station);
    int count=RoadProfile.layout(raw,sample).catalog().lanes(),forward=0,reverse=0;
    var result=new ArrayList<LanePoints.Lane>();
    for(int i:LaneAdditions.slots(mesh,station))if(active(mesh,station,i)){
      var lane=LanePoints.lane(raw,station,i);result.add(lane);
      if(lane.sign()>0)forward++;else reverse++;
    }
    return new Live(result,forward,reverse);
  }
  public static boolean edge(Mesh mesh,double station,int slot){
    return edge(reference(mesh),station,slot,mesh.settings().options().lanePoints().cuts(),null);
  }
  static boolean edge(Mesh raw,double station,int slot,List<Cut> cuts,UUID excluded){
    var chosen=LanePoints.lane(raw,station,slot);var layout=RoadProfile.layout(raw,RoadStructures.sample(raw,station));
    int count=layout.catalog().lanes();
    if((LaneAdditions.isAdded(raw,slot)&&LaneAdditions.find(raw,slot).fraction(station)<.999999)||removed(cuts,station,slot,excluded)>=.5)return false;
    double offset=chosen.position().sub(RoadStructures.sample(raw,station).center()).dot(RoadStructures.sample(raw,station).left());
    boolean low=true,high=true;
    for(int i:LaneAdditions.slots(raw,station))if(i!=slot&&(!LaneAdditions.isAdded(raw,i)||LaneAdditions.find(raw,i).fraction(station)>.999999)&&removed(cuts,station,i,excluded)<.5){
      var other=LanePoints.lane(raw,station,i);if(other.sign()!=chosen.sign())continue;
      double delta=other.position().sub(chosen.position()).dot(RoadStructures.sample(raw,station).left());
      if(delta<-.001)low=false;if(delta>.001)high=false;
    }
    return layout.catalog().twoWay()?(offset<layout.medianCenter()?low:high):low||high;
  }
  static double removed(List<Cut> cuts,double station,int slot,UUID excluded){
    double removed=0;for(var c:cuts)if(c.lane()==slot&&!c.connection().equals(excluded))removed=Math.max(removed,c.removed(station));return removed;
  }
  /** Nearest currently open lane in the SAME driving direction; never a vanished slot. */
  public static int receiver(Mesh mesh,double station,int slot,UUID excluded){
    var raw=reference(mesh);var chosen=LanePoints.lane(raw,station,slot);
    int count=RoadProfile.layout(raw,RoadStructures.sample(raw,station)).catalog().lanes(),best=-1;double distance=Double.POSITIVE_INFINITY;
    for(int i:LaneAdditions.slots(mesh,station))if(i!=slot&&(!LaneAdditions.isAdded(raw,i)||LaneAdditions.find(raw,i).fraction(station)>.999999)&&removed(mesh.settings().options().lanePoints().cuts(),station,i,excluded)<.5){
      var candidate=LanePoints.lane(raw,station,i);if(candidate.sign()!=chosen.sign())continue;
      double d=candidate.position().distance(chosen.position());if(d<distance){distance=d;best=i;}
    }
    if(best<0)throw new IllegalArgumentException("当前位置该方向至少保留两条通行车道才能合流或整车道分离");
    // A temporarily closed neighbour is a physical hole, not permission to skip
    // across it into a more distant open lane. Removed OUTER slots never lie
    // between the newly outer lane and its immediate inward receiver.
    if(distance>chosen.width()*1.01)
      throw new IllegalArgumentException("合流接收车道被封闭，不能跨越中间空位并入更远车道");
    return best;
  }
  private record Section(double shift,double half,RoadProfile.Layout layout){}
  private static Section section(Mesh raw,Sample s){
    var layout=RoadProfile.layout(raw,s);var c=layout.catalog();int count=c.lanes();

    double[] removal=new double[count];
    for(Cut cut:raw.settings().options().lanePoints().cuts()){
      if(cut.temporary()||LaneAdditions.isAdded(raw,cut.lane()))continue;
      if(cut.lane()>=count){if(cut.removed(s.distance())>.001)throw new IllegalArgumentException("分离车道穿过了车道数变化接缝，请在同一断面路段内设置接头");continue;}
      removal[cut.lane()]=Math.max(removal[cut.lane()],cut.removed(s.distance()));
    }
    double trimLow=0,trimHigh=0;
    if(c.twoWay()) {
      int per=layout.lanesOnSide(-1);
      for(int i=0;i<count;i++)if(removal[i]>.000001) {
        int outside=i<per?per-1:count-1;
        for(int j=i+1;j<=outside;j++)if(removal[j]<1-1e-6)
          throw new IllegalArgumentException("双向整车道分离须从当前位置的最外侧依次进行，不能挖走仍有外侧通行车道的内部车道");
        if(count!=RoadProfile.catalog(raw.settings()).lanes())
          throw new IllegalArgumentException("分离区间不能跨越车道数变化接缝，请在同一稳定断面内设置接头");
        if(i<per)trimLow+=layout.laneWidth()*removal[i];else trimHigh+=layout.laneWidth()*removal[i];
      }
      if(layout.medianEdge(-1)-layout.motorMin()-trimLow<-.001||
          layout.motorMax()-layout.medianEdge(1)-trimHigh<-.001)
        throw new IllegalArgumentException("分离范围超出相应半幅机动车道");
    } else {
      int low=0,high=count-1;
      while(low<count&&removal[low]>.000001){trimLow+=layout.laneWidth()*removal[low];low++;}
      while(high>=low&&removal[high]>.000001){trimHigh+=layout.laneWidth()*removal[high];high--;}
      for(int i=low;i<=high;i++)if(removal[i]>.000001)throw new IllegalArgumentException("不能在保留两侧车道时挖走内部车道；请先通过普通分流接头将它引至外侧");
    }
    double motorWidth=layout.motorMax()-layout.motorMin()-layout.median()-trimLow-trimHigh;
    if(motorWidth<layout.laneWidth()*.99)throw new IllegalArgumentException("整车道分离后至少保留一条主路通行车道，不能挖空整条道路");
    double extraLow=0,extraHigh=0;int extraCount=0;
    var addedDividers=new ArrayList<Double>();
    for(var added:LaneAdditions.ordered(raw)){
      double width=layout.laneWidth()*added.fraction(s.distance())*(1-LaneAdditions.permanentRemoval(raw,s.distance(),added.slot()));
      int side=LaneAdditions.side(layout,added.sign());
      // A bounded auxiliary lane appears and disappears on BOTH carriageways.
      // Keep its zero-width boundary at the verge so the existing divider
      // indices do not jump across a lane between two adjacent samples.
      // Unbounded user-created widening needs the same stable divider slots.
      // Dropping the zero-width slot shifts every following sorted divider and
      // draws a transverse stripe at the first nonzero sample.
      addedDividers.add(side<0?layout.motorMin()+trimLow-extraLow:layout.motorMax()-trimHigh+extraHigh);
      if(side<0)extraLow+=width;else extraHigh+=width;
      if(width>layout.laneWidth()*.999999)extraCount++;
    }
    double shift=(trimLow-trimHigh-extraLow+extraHigh)/2,half=s.halfWidth()-(trimLow+trimHigh)/2+(extraLow+extraHigh)/2;
    double min=layout.motorMin()+trimLow-extraLow-shift,max=layout.motorMax()-trimHigh+extraHigh-shift;
    int retained=count;for(double value:removal)if(value>=.5)retained--;
    // A removed lane does not drag its old divider onto the moving outer edge.
    // Keep the authored axis; paint is clipped by actual pavement. Clamping created
    // a false diagonal merge guide during whole-lane DETACH (including reverse traffic).
    var dividers=new ArrayList<Double>();for(double d:layout.dividers())dividers.add(d-shift);for(double d:addedDividers)dividers.add(d-shift);dividers.sort(Double::compare);
    var catalog=new RoadProfile.Catalog(c.type(),c.twoWay()?c.lanes()+extraCount:Math.max(1,retained+extraCount),c.twoWay(),c.median(),c.shoulder());
    return new Section(shift,half,new RoadProfile.Layout(catalog,layout.laneWidth(),layout.median(),min,max,
      layout.cycleWidth(),layout.curbWidth(),layout.shoulderWidth(),layout.outside(),List.copyOf(dividers),layout.medianCenter()-shift));
  }
  public static Mesh apply(Mesh mesh){
    if(mesh.settings().options().lanePoints().cuts().isEmpty()&&mesh.settings().options().lanePoints().additions().isEmpty()||mesh.reference()!=null)return mesh;
    var stations=new TreeSet<Double>();for(var s:mesh.samples())stations.add(s.distance());
    for(var cut:mesh.settings().options().lanePoints().cuts())for(double center:new double[]{cut.begin(),cut.end()})
      for(double d=center-cut.transition();d<=center+cut.transition()+.001;d+=.5)if(d>0&&d<mesh.length())stations.add(d);
    // A complete lane departure has a step at its actual mouth. Preserve both
    // sides of that section instead of interpolating a diagonal inward merge.
    for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.rectangular()&&!cut.temporary())
      for(double center:new double[]{cut.begin(),cut.end()})for(double d:new double[]{center-1e-5,center,center+1e-5})if(d>0&&d<mesh.length())stations.add(d);
    for(var added:mesh.settings().options().lanePoints().additions()){
      double begin=added.station()-added.sign()*added.transition();
      for(double d=Math.min(begin,added.station());d<=Math.max(begin,added.station())+.001;d+=.5)if(d>0&&d<mesh.length())stations.add(d);
      if(added.station()>0&&added.station()<mesh.length())stations.add(added.station());
      if(Double.isFinite(added.end())){double finish=added.end()+added.sign()*added.transition();
        for(double d=Math.min(added.end(),finish);d<=Math.max(added.end(),finish)+.001;d+=.5)if(d>0&&d<mesh.length())stations.add(d);
        if(added.end()>0&&added.end()<mesh.length())stations.add(added.end());
      }
    }
    if(stations.size()>RoadLimits.MAX_SAMPLES)throw new IllegalArgumentException("车道过渡采样数量超限，请缩短路段");
    var samples=new ArrayList<Sample>();
    for(double station:stations){if(!samples.isEmpty()&&station-samples.get(samples.size()-1).distance()<1e-6)continue;var s=RoadStructures.sample(mesh,station);var section=section(mesh,s);
      samples.add(new Sample(s.center().add(s.left().mul(section.shift())),s.left(),s.distance(),section.half()));}
    Mesh physical=RoadRibbon.mesh(samples,mesh.settings());
    // Keep authored longitudinal stations. The reference describes the unshifted slot axes.
    return new Mesh(List.copyOf(samples),mesh.settings(),physical.min(),physical.max(),mesh.length(),mesh.closed(),mesh.controlPoint(),mesh.controls(),mesh);
  }
  public static RoadProfile.Layout layout(Mesh mesh,Sample sample){
    if(mesh.reference()==null)return null;
    return section(mesh.reference(),RoadStructures.sample(mesh.reference(),sample.distance())).layout();
  }
  public static V anchor(Mesh mesh,double station){return RoadStructures.sample(reference(mesh),station).center();}
  private LaneSections(){}
}
