package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Reversible outer-lane growth. New authored IDs are 0..13 and new additions
 * use 16..31. Legacy additions at 8..15 remain identifiable by their metadata. */
public final class LaneAdditions {
  public static final int FIRST_NEW_SLOT=16;
  public static boolean isAdded(Mesh mesh,int slot){return slot>=8&&LaneSections.reference(mesh).settings().options().lanePoints().additions().stream().anyMatch(a->a.slot()==slot);}
  /** Only an explicit wider-section edit can make a legacy added ID collide. */
  public static LanePoints.Data reserveAuthored(LanePoints.Data data,int count){
    var remap=new HashMap<Integer,Integer>();var used=new HashSet<Integer>();for(var a:data.additions())used.add(a.slot());
    for(var a:data.additions())if(a.slot()<count){int next=FIRST_NEW_SLOT;while(used.contains(next))next++;
      if(next>31)throw new IllegalArgumentException("扩宽道路需要空闲的新增车道编号，请先删除部分扩出连接");used.add(next);remap.put(a.slot(),next);}
    if(remap.isEmpty())return data;
    var additions=data.additions().stream().map(a->new Addition(a.connection(),remap.getOrDefault(a.slot(),a.slot()),a.sign(),a.station(),a.transition(),a.end())).toList();
    var points=data.points().stream().map(p->new LanePoints.Point(p.id(),p.origin(),remap.getOrDefault(p.lane(),p.lane()),p.position(),p.anchor(),p.mergeLength())).toList();
    var cuts=data.cuts().stream().map(c->new LaneSections.Cut(c.connection(),remap.getOrDefault(c.lane(),c.lane()),c.sign(),c.begin(),c.end(),c.transition(),c.replacement(),c.temporary(),c.arrival(),c.rectangular(),c.underpass())).toList();
    return data.additions(additions).points(points).cuts(cuts);
  }
  public record Addition(UUID connection,int slot,int sign,double station,double transition,double end) {
    public Addition(UUID connection,int slot,int sign,double station,double transition){this(connection,slot,sign,station,transition,Double.NaN);}
    public Addition {if(connection==null||slot<8||slot>31||(sign!=1&&sign!=-1)
        ||!RoadGeometry.finite(station,transition)||transition<8||transition>256||!Double.isNaN(end)&&(!Double.isFinite(end)||sign*(end-station)<0))
      throw new IllegalArgumentException("新增外侧车道数据无效");}
    public double fraction(double at){double growth=Settings.smooth(Math.max(0,Math.min(1,1+sign*(at-station)/transition)));
      return Double.isNaN(end)?growth:Math.min(growth,Settings.smooth(Math.max(0,Math.min(1,1+sign*(end-at)/transition))));}
  }
  public static List<Integer> slots(Mesh mesh){return slots(mesh,mesh.first().distance());}
  public static List<Integer> slots(Mesh mesh,double station){
    var raw=LaneSections.reference(mesh);var out=new ArrayList<Integer>();
    for(int i=0;i<RoadProfile.layout(raw,RoadStructures.sample(raw,station)).catalog().lanes();i++)out.add(i);
    for(var a:raw.settings().options().lanePoints().additions())out.add(a.slot());
    return out;
  }
  public static Addition find(Mesh mesh,int slot){return LaneSections.reference(mesh).settings().options().lanePoints().additions().stream()
      .filter(a->a.slot()==slot).findFirst().orElseThrow(()->new IllegalArgumentException("所选新增车道已不存在"));}
  public static Addition owned(Mesh mesh,UUID connection){return LaneSections.reference(mesh).settings().options().lanePoints().additions().stream()
      .filter(a->a.connection().equals(connection)).findFirst().orElseThrow(()->new IllegalArgumentException("新增车道尚未完成规划"));}
  public static int side(RoadProfile.Layout l,int sign){return l.catalog().twoWay()?l.outside()*sign:l.outside();}
  public static List<Addition> ordered(Mesh raw){return raw.settings().options().lanePoints().additions().stream()
      .sorted(Comparator.comparingDouble((Addition a)->a.station()*a.sign()).thenComparing(Addition::connection)).toList();}
  public static double cutRemoval(Mesh raw,double station,int slot){double value=0;
    for(var c:raw.settings().options().lanePoints().cuts())if(c.lane()==slot)value=Math.max(value,c.removed(station));return value;}
  public static double permanentRemoval(Mesh raw,double station,int slot){double value=0;
    for(var c:raw.settings().options().lanePoints().cuts())if(!c.temporary()&&c.lane()==slot)value=Math.max(value,c.removed(station));return value;}
  /** Outer edge of the remaining authored lanes; opposite carriageway is never touched. */
  public static double edge(Mesh raw,Sample at,int side){
    var l=RoadProfile.layout(raw,at);double edge=side<0?l.motorMin():l.motorMax();
    int count=l.catalog().lanes(),low=l.catalog().twoWay()?l.lanesOnSide(-1):count;
    for(int i=0;i<count;i++){
      if(l.catalog().twoWay()&&(i<low?-1:1)!=side)continue;
      if(!l.catalog().twoWay()){
        // Trimming follows a contiguous side; do not count a cut at the other edge.
        boolean contiguous=true;
        if(side<0){for(int j=0;j<i;j++)contiguous&=permanentRemoval(raw,at.distance(),j)>1-1e-6;}
        else {for(int j=count-1;j>i;j--)contiguous&=permanentRemoval(raw,at.distance(),j)>1-1e-6;}
        if(!contiguous)continue;
      }
      double removal=0;for(var c:raw.settings().options().lanePoints().cuts())if(!c.temporary()&&c.lane()==i)removal=Math.max(removal,c.removed(at.distance()));
      edge-=side*l.laneWidth()*removal;
    }
    return edge;
  }
  public static LanePoints.Lane lane(Mesh mesh,double station,int slot){
    var raw=LaneSections.reference(mesh);var at=RoadStructures.sample(raw,Math.max(0,Math.min(raw.length(),station)));
    var a=find(raw,slot);var l=RoadProfile.layout(raw,at);int side=side(l,a.sign());
    double offset=edge(raw,at,side);
    for(var prior:ordered(raw)){
      if(prior.slot()==slot)break;
      if(side(l,prior.sign())==side)offset+=side*l.laneWidth()*prior.fraction(at.distance())*(1-permanentRemoval(raw,at.distance(),prior.slot()));
    }
    offset+=side*l.laneWidth()*a.fraction(at.distance())/2;
    return new LanePoints.Lane(slot,at.at(offset,0),at.left().left().mul(-a.sign()),l.laneWidth(),at.distance(),a.sign());
  }
  public static void validate(Mesh mesh){
    var raw=LaneSections.reference(mesh);var checks=new TreeSet<Double>();checks.add(0d);checks.add(raw.length());
    for(var a:raw.settings().options().lanePoints().additions()){
      checks.add(a.station());checks.add(Math.max(0,Math.min(raw.length(),a.station()-a.sign()*a.transition())));
    }
    for(var c:raw.settings().options().lanePoints().cuts())for(double d:new double[]{c.begin(),c.end(),c.begin()+c.sign()*c.transition(),c.end()-c.sign()*c.transition()})
      checks.add(Math.max(0,Math.min(raw.length(),d)));
    for(double at:checks){var live=LaneSections.live(mesh,at);
      if(live.forward()>RoadLanes.MAX_DIRECTION+auxiliary(raw,at,1)||live.reverse()>RoadLanes.MAX_DIRECTION+auxiliary(raw,at,-1))throw new IllegalArgumentException("汇入方向已达 7 条车道，不能补入第 8 条；请检查该点及下游车道数");}
  }
  private static int auxiliary(Mesh raw,double station,int sign){return (int)raw.settings().options().lanePoints().access().stream().flatMap(r->r.additions().stream()).filter(a->a.sign()==sign&&a.fraction(station)>0).count();}
  private LaneAdditions(){}
}
