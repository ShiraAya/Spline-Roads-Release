package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Lane-specific ramp ports. No attached-road-point conversion exists. */
public final class LanePoints {
  public enum Origin { AUTOMATIC_START, AUTOMATIC_END, MANUAL }
  /** Anchor is the spine station. Positive mergeLength narrows; negative expands an outer lane. */
  public record Point(UUID id,Origin origin,int lane,V position,V anchor,double mergeLength) {
    public Point(UUID id,Origin origin,int lane,V position,V anchor){this(id,origin,lane,position,anchor,0);}
    public Point {Objects.requireNonNull(id);Objects.requireNonNull(origin);if(!Double.isFinite(mergeLength)||mergeLength!=0&&(Math.abs(mergeLength)<8||Math.abs(mergeLength)>256)||origin!=Origin.MANUAL&&mergeLength!=0||lane<0||lane>31||position==null||!RoadGeometry.finite(position.x(),position.y(),position.z())||anchor!=null&&!RoadGeometry.finite(anchor.x(),anchor.y(),anchor.z()))throw new IllegalArgumentException("车道点数据无效");}
    /** Read compatibility for pre-anchor saves. Canonicalize once against the actual road mesh. */
    public Point(UUID id,Origin origin,int lane,V position){this(id,origin,lane,position,null);}
    public boolean automatic(){return origin!=Origin.MANUAL;}
    public Point at(Lane selected,Mesh mesh){return new Point(id,origin,selected.index(),selected.position(),LaneSections.anchor(mesh,selected.station()),mergeLength);}
    public Point merge(double length){return new Point(id,origin,lane,position,anchor,length);}
  }
  public record Ref(UUID road,UUID point,UUID junction) {
    public Ref {if((junction!=null)==(road!=null||point!=null)||junction==null&&(road==null||point==null))throw new IllegalArgumentException("连接目标必须为具体车道点或路口中心");}
    public static Ref lane(UUID road,UUID point){return new Ref(road,point,null);}
    public static Ref junction(UUID junction){return new Ref(null,null,junction);}
  }
  public enum Path { AUTO("自动"),RIGHT("右转"),LEFT_LOOP("左转回环"),DIRECT("直接连接"),LEFT("定向左转");public final String label;Path(String label){this.label=label;} }
  public enum Departure { BRANCH("普通分流（原车道直行）"), DETACH("整车道分离"), EXTRA("额外扩出"), TEMPORARY("保留车道分离"); public final String label; Departure(String label){this.label=label;} }
  public enum Arrival { MERGE("并入现有车道"), REPLACE("旧版空位恢复"), EXTRA("额外扩入"), FLOW("普通汇流（主路不断行）"), ADD("最外侧补入（增加一车道）"); public final String label; Arrival(String label){this.label=label;} }
  public enum Elevation { AUTO("自动避让"), OVER("上跨既有道路"), UNDER("下穿既有道路"), KEEP("保持原高程"); public final String label; Elevation(String label){this.label=label;} }
  public enum Landing { FLEXIBLE("同车道弹性落点"), EXACT("精确锁定 B"); public final String label; Landing(String label){this.label=label;} }
  public record Options(Path path,Departure departure,Arrival arrival,double radius,double transition,Elevation elevation,Landing landing,boolean gradeOverride,boolean allowTunnel,int lanes) {
    public Options(Path path,Departure departure,Arrival arrival,double radius,double transition,Elevation elevation,Landing landing,boolean gradeOverride,boolean allowTunnel){this(path,departure,arrival,radius,transition,elevation,landing,gradeOverride,allowTunnel,1);}
    public Options(Path path,Departure departure,Arrival arrival,double radius,double transition,Elevation elevation,Landing landing,boolean gradeOverride){this(path,departure,arrival,radius,transition,elevation,landing,gradeOverride,false);}
    public Options(Path path,Departure departure,Arrival arrival,double radius,double transition,Elevation elevation,Landing landing){this(path,departure,arrival,radius,transition,elevation,landing,false);}
    // Existing saves and ordinary branching keep their original meaning. DETACH is explicit.
    public static final Options DEFAULT=new Options(Path.AUTO,Departure.BRANCH,Arrival.MERGE,24,32,Elevation.AUTO,Landing.FLEXIBLE);
    public Options(Path path,boolean sourceExtra,boolean targetExtra,double radius,double transition){this(path,sourceExtra?Departure.EXTRA:Departure.BRANCH,targetExtra?Arrival.EXTRA:Arrival.MERGE,radius,transition,Elevation.AUTO,Landing.FLEXIBLE);}
    public Options {if(path==null||departure==null||arrival==null||elevation==null||landing==null||!RoadGeometry.finite(radius,transition)||radius<8||radius>256||transition<8||transition>256||lanes<1||lanes>2||lanes>1&&(arrival==Arrival.ADD||arrival==Arrival.REPLACE))throw new IllegalArgumentException("半径与过渡长度须为 8–256 格，且连接模式有效");}
    public Options lanes(int value){return new Options(path,departure,arrival,radius,transition,elevation,landing,gradeOverride,allowTunnel,value);}
    public boolean sourceExtra(){return departure==Departure.EXTRA;}
    public boolean separatesLane(){return departure==Departure.DETACH||departure==Departure.TEMPORARY;}
    public boolean targetExtra(){return arrival==Arrival.EXTRA;}
    public Options withoutApproaches(){return new Options(path,Departure.BRANCH,Arrival.MERGE,radius,transition,elevation,landing,gradeOverride,allowTunnel,lanes);}
  }
  /** Target offset is measured along the selected lane's driving direction, never another lane. */
  public record Link(Ref from,Ref to,Options options,V junctionMouth,double targetOffset,boolean protectedMerge,boolean rectangularClosure) {
    public Link(Ref from,Ref to,Options options,V mouth,double offset,boolean protectedMerge){this(from,to,options,mouth,offset,protectedMerge,false);}
    public Link(Ref from,Ref to,Options options,V mouth,double offset){this(from,to,options,mouth,offset,false);}
    public Link(Ref from,Ref to,Options options,V junctionMouth){this(from,to,options,junctionMouth,0);}
    public Link {Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(options);if(from.junction()!=null||from.equals(to))throw new IllegalArgumentException("匝道须从车道点汇出，且不能接回同一点");if(to.junction()!=null&&junctionMouth==null)throw new IllegalArgumentException("缺少路口实际接入口");if(!Double.isFinite(targetOffset)||to.junction()!=null&&targetOffset!=0)throw new IllegalArgumentException("汇入偏移超出允许范围");}
    public boolean closesTarget(){return protectedMerge&&to.road()!=null&&options.arrival()==Arrival.MERGE;}
    public Link withProtectedMerge(){return new Link(from,to,options,junctionMouth,targetOffset,true,rectangularClosure);}
    public Link withRectangularClosure(){return new Link(from,to,options,junctionMouth,targetOffset,protectedMerge,true);}
    public Link targetOffset(double value){return new Link(from,to,options,junctionMouth,value,protectedMerge,rectangularClosure);}
  }
  /** Actual connector centerline at a contact, used to clear crossing rail/curb geometry. */
  public record Opening(UUID connection,List<V> centerline,double halfWidth) {
    public Opening {centerline=List.copyOf(centerline);if(centerline.size()>16000||!Double.isFinite(halfWidth)||halfWidth<=0||halfWidth>64)throw new IllegalArgumentException("匝道接头范围无效");}
  }
  /** Persist the actual motor strip independently of asymmetric pavement shoulders. */
  public record Band(double station,double low,double high){
    public Band{if(!RoadGeometry.finite(station,low,high)||station<0||low>=high||Math.abs(low)>32||Math.abs(high)>32)throw new IllegalArgumentException("无效的匝道机动车断面");}
  }
  public record Data(List<Point> points,Link link,List<Opening> openings,int priorityDepth,List<LaneSections.Cut> cuts,List<LaneAdditions.Addition> additions,List<Band> bands,List<ElevatedAccess.Reservation> access) {
    public Data(List<Point> points,Link link,List<Opening> openings,int depth,List<LaneSections.Cut> cuts,List<LaneAdditions.Addition> additions,List<Band> bands){this(points,link,openings,depth,cuts,additions,bands,List.of());}
    public Data(List<Point> points,Link link,List<Opening> openings,int depth,List<LaneSections.Cut> cuts,List<LaneAdditions.Addition> additions){this(points,link,openings,depth,cuts,additions,List.of());}
    public Data(List<Point> points,Link link,List<Opening> openings,int priorityDepth,List<LaneSections.Cut> cuts){this(points,link,openings,priorityDepth,cuts,List.of());}
    public static final Data EMPTY=new Data(List.of(),null,List.of(),0,List.of());
    public Data(List<Point> points,Link link,List<Opening> openings){this(points,link,openings,link==null?0:1,List.of());}
    public Data(List<Point> points,Link link,List<Opening> openings,int priorityDepth){this(points,link,openings,priorityDepth,List.of());}
    public Data {access=List.copyOf(access);if(access.stream().map(ElevatedAccess.Reservation::connection).distinct().count()!=access.size())throw new IllegalArgumentException("高架出入口身份重复");points=List.copyOf(points);openings=List.copyOf(openings);cuts=List.copyOf(cuts);additions=List.copyOf(additions);bands=List.copyOf(bands);if(bands.size()>RoadLimits.MAX_SAMPLES||additions.size()>24||additions.stream().map(LaneAdditions.Addition::slot).distinct().count()!=additions.size()||cuts.size()>128||points.size()>512||openings.size()>128||priorityDepth<0||priorityDepth>1024||points.stream().map(Point::id).distinct().count()!=points.size())throw new IllegalArgumentException("车道点数量超限、身份重复或依赖深度无效");
      double last=-1;for(var band:bands){if(band.station()<=last)throw new IllegalArgumentException("匝道断面站位未递增");last=band.station();}
    }
    public Data points(List<Point> v){return new Data(v,link,openings,priorityDepth,cuts,additions,bands,access);}
    public Data link(Link v){return new Data(points,v,openings,v==null?0:Math.max(1,priorityDepth),cuts,additions,bands,access);}
    public Data openings(List<Opening> v){return new Data(points,link,v,priorityDepth,cuts,additions,bands,access);}
    public Data priorityDepth(int v){return new Data(points,link,openings,v,cuts,additions,bands,access);}
    public Data additions(List<LaneAdditions.Addition> v){return new Data(points,link,openings,priorityDepth,cuts,v,bands,access);}
    public Data cuts(List<LaneSections.Cut> v){return new Data(points,link,openings,priorityDepth,v,additions,bands,access);}
    public Data access(List<ElevatedAccess.Reservation> v){return new Data(points,link,openings,priorityDepth,cuts,additions,bands,v);}
    public Data bands(List<Band> v){return new Data(points,link,openings,priorityDepth,cuts,additions,v,access);}
  }
  public record Lane(int index,V position,V direction,double width,double station,int sign){}
  public static Lane lane(Mesh mesh,double station,int index){
    if(LaneAdditions.isAdded(mesh,index))return LaneAdditions.lane(mesh,station,index);
    mesh=LaneSections.reference(mesh);Sample sample=RoadStructures.sample(mesh,Math.max(0,Math.min(mesh.length(),station)));var l=RoadProfile.layout(mesh,sample);var c=l.catalog();
    if(index<0||index>=c.lanes())throw new IllegalArgumentException("所选车道已不存在");
    double offset;if(c.twoWay()){int per=l.lanesOnSide(-1),side=index<per?-1:1;offset=l.medianCenter()+side*(l.median()/2+((side<0?index:index-per)+.5)*l.laneWidth());}else offset=l.motorMin()+(index+.5)*l.laneWidth();
    int sign=c.twoWay()&&(offset<l.medianCenter()?-1:1)!=l.outside()?-1:1;
    return new Lane(index,sample.at(offset,0),sample.left().left().mul(-sign),l.laneWidth(),sample.distance(),sign);
  }
  public static Lane lane(Mesh mesh,Point point){return lane(mesh,RoadQueries.project(LaneSections.reference(mesh),point.anchor()==null?point.position():point.anchor()).sample().distance(),point.lane());}
  /** Adjacent authored slots in one driving direction, beginning at the inner slot. */
  public static Lane group(Mesh mesh,Point point,int count){var first=lane(mesh,point);return group(mesh,first.station(),point.lane(),count);}
  public static Lane group(Mesh mesh,double station,int slot,int count){var first=lane(mesh,station,slot);if(count==1)return first;var last=lane(mesh,station,slot+count-1);if(first.sign()!=last.sign()||LaneAdditions.isAdded(mesh,slot)||LaneAdditions.isAdded(mesh,slot+count-1))throw new IllegalArgumentException("双车道匝道需要相邻且同向的两条原车道");return new Lane(first.index(),first.position().add(last.position()).mul(.5),first.direction(),first.width()*count,first.station(),first.sign());}
  public static Point snap(Mesh mesh,Point point){var selected=lane(mesh,point);V anchor=LaneSections.anchor(mesh,selected.station());if(point.anchor()!=null&&point.position().distance(selected.position())<1e-7&&point.anchor().distance(anchor)<1e-7)return point;return point.at(selected,mesh);}
  public static Point migrate(Mesh previous,Mesh target,Point point){
    Lane old=lane(previous,point);target=LaneSections.reference(target);var q=RoadQueries.horizontal(target,point.position());
    Lane best=null;double distance=Double.POSITIVE_INFINITY;
    int count=RoadProfile.layout(target,q.sample()).catalog().lanes();
    for(int index:LaneAdditions.slots(target,q.sample().distance())){
      Lane candidate=lane(target,q.sample().distance(),index);
      if(candidate.direction().dot(old.direction())<.999)continue;
      double d=candidate.position().sub(point.position()).horizontalLength();
      if(d<distance){distance=d;best=candidate;}
    }
    if(best==null||distance>Math.max(.1,old.width()*.25))
      throw new IllegalArgumentException("相邻道路找不到保持位置及通行方向的对应车道，已取消归属迁移");
    return point.at(best,target);
  }
  public static Point point(UUID id,Origin origin,Mesh mesh,double station,int index){var selected=lane(mesh,station,index);return new Point(id,origin,index,selected.position(),LaneSections.anchor(mesh,selected.station()));}
  public static Lane clicked(Mesh mesh,V hit){
    var raw=LaneSections.reference(mesh);var q=RoadQueries.project(raw,hit);var l=RoadProfile.layout(raw,q.sample());
    Lane best=null;double distance=Double.POSITIVE_INFINITY;
    for(int i:LaneAdditions.slots(mesh,q.sample().distance())){if(!LaneSections.active(mesh,q.sample().distance(),i))continue;var lane=lane(mesh,q.sample().distance(),i);double d=lane.position().sub(hit).horizontalLength();if(d<distance){distance=d;best=lane;}}
    if(best==null||distance>best.width()/2+.05||Math.abs(hit.y()-best.position().y())>2)throw new IllegalArgumentException("请点击机动车道内，不能在中央隔离带、人行道或非机动车道放点");return best;
  }
  public static String label(Mesh mesh,int index){return label(mesh,mesh.length()/2,index);}
  /** Joined records may continue a landing search only with the same section. */
  public static boolean sameSection(Mesh a,double at,Mesh b,double bt){
    var x=RoadProfile.layout(a,RoadStructures.sample(a,at));var y=RoadProfile.layout(b,RoadStructures.sample(b,bt));
    return x.catalog().type()==y.catalog().type()&&x.catalog().lanes()==y.catalog().lanes()&&x.catalog().twoWay()==y.catalog().twoWay()
        &&Math.abs(x.laneWidth()-y.laneWidth())<.01&&Math.abs(x.median()-y.median())<.01
        &&Math.abs(x.cycleWidth()-y.cycleWidth())<.01&&Math.abs(x.shoulderWidth()-y.shoulderWidth())<.01
        &&a.settings().options().leftTraffic()==b.settings().options().leftTraffic();
  }
  public static String label(Mesh mesh,double station,int index){
    var chosen=lane(mesh,station,index);var live=LaneSections.live(mesh,station);
    var same=live.lanes().stream().filter(l->l.sign()==chosen.sign()).toList();
    int ordinal=1;for(var l:same){if(l.index()==index)break;ordinal++;}
    boolean present=same.stream().anyMatch(l->l.index()==index);
    boolean twoWay=RoadProfile.catalog(mesh.settings().style()).twoWay();
    return (twoWay?(chosen.sign()>0?"正向":"反向")+" ":"单向 ")+
        (present?ordinal+" / "+same.size()+" 车道"+(LaneSections.edge(mesh,station,index)?"（边缘）":""):"预留空位 "+(index+1));
  }
  public static boolean supported(Settings s){var type=RoadProfile.catalog(s.style()).type();if(type!=RoadProfile.Type.ORDINARY&&type!=RoadProfile.Type.HIGHWAY&&!s.style().connectorRamp())return false;if(s.structure()!=Structure.BRIDGE)return true;return switch(s.options().infrastructure().bridge()){case STANDARD,BEAM,OVERPASS->true;default->false;};}
  public static boolean opening(Mesh mesh,V location){
    for(var opening:mesh.settings().options().lanePoints().openings())for(int i=1;i<opening.centerline().size();i++){
      V a=opening.centerline().get(i-1),b=opening.centerline().get(i),d=b.sub(a);double length=d.x()*d.x()+d.z()*d.z();if(length<1e-9)continue;double t=Math.max(0,Math.min(1,(location.x()-a.x())*d.x()/length+(location.z()-a.z())*d.z()/length));V p=a.add(d.mul(t));
      if(Math.abs(location.y()-p.y())>=2.2||location.sub(p).horizontalLength()>=opening.halfWidth()+.4)continue;
      if((t==0||t==1)&&mesh.settings().options().lanePoints().access().stream().anyMatch(r->r.connection().equals(opening.connection()))){
        double past=(location.x()-p.x())*d.x()+(location.z()-p.z())*d.z();
        if(t==0&&past<0||t==1&&past>0)continue;
      }
      return true;
    }return false;
  }
  private LanePoints(){}
}
