package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Lossless record slicing. World writes and dependency migration belong to RoadSplitter. */
public final class RoadSplitGeometry {
  public record Range(double from,double to,String reason){
    public Range {if(from>to){double t=from;from=to;to=t;}}
    public boolean intersects(double a,double b){return a<to+1e-6&&b>from-1e-6;}
  }
  public record Pair(RoadRecord first,RoadRecord second,Node node,BlockPos pos,double station,List<Range> protection){}
  public static double station(Mesh mesh,V point){return RoadQueries.project(mesh,point).sample().distance();}
  private static double horizontal(Mesh mesh,V point){return RoadQueries.horizontal(mesh,point).sample().distance();}
  public static Node node(Mesh mesh,double d){
    var at=RoadSlicing.at(mesh,d);var layout=RoadProfile.layout(mesh,at);
    var p=at.at(layout.medianCenter(),0);var a=RoadSlicing.at(mesh,Math.max(0,d-.01));var b=RoadSlicing.at(mesh,Math.min(mesh.length(),d+.01));
    V delta=b.center().sub(a.center());return new Node(p,RoadPlanner.yaw(at.left().left().mul(-1)),delta.y()/Math.max(1e-9,delta.horizontalLength()));
  }
  public static List<Range> protection(RoadRecord road){
    var mesh=road.mesh();var raw=road.rawMesh();double length=mesh.length();var ranges=new ArrayList<Range>();
    if(RoadInfrastructure.customBridge(mesh)&&Set.of(RoadInfrastructure.Bridge.ARCH,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.SUSPENSION).contains(road.settings().options().infrastructure().bridge()))ranges.add(new Range(0,length,"此桥型属于需要整体维护的桥跨"));
    ranges.add(new Range(0,2,"距原起点过近"));ranges.add(new Range(length-2,length,"距原终点过近"));
    var e=road.settings().options().ends();double span=RoadTransitions.span(road.settings(),length+e.trimmedStart()+e.trimmedEnd());
    if(e.start()!=null&&!e.start().equals(RoadTransitions.Section.of(road.settings()))||road.settings().startWidth()!=road.settings().width())ranges.add(new Range(0,Math.max(0,span-e.trimmedStart()),"这里是起端断面渐变段"));
    if(e.end()!=null&&!e.end().equals(RoadTransitions.Section.of(road.settings()))||road.settings().endWidth()!=road.settings().width())ranges.add(new Range(Math.min(length,length-span+e.trimmedEnd()),length,"这里是末端断面渐变段"));
    var lanes=road.settings().options().lanePoints();
    for(var c:lanes.cuts()){
      if(c.temporary()){
        // A temporary reservation is still a special closure throughout its
        // interior, even where its width is constant.
        protectedRange(ranges,c.begin(),c.end(),length,"这里是车道分离、封闭或恢复区间");
      }else{
        // A permanent outer-lane departure leaves ordinary narrower pavement.
        // Its equation deliberately extends past the authored road end; that
        // stable continuation is not a road-long ramp mouth or transition.
        double cutSpan=Math.min(c.transition(),Math.abs(c.end()-c.begin())/2);
        protectedRange(ranges,c.begin(),c.begin()+c.sign()*cutSpan,length,"这里是车道分离渐变段");
        protectedRange(ranges,c.end()-c.sign()*cutSpan,c.end(),length,"这里是车道恢复渐变段");
      }
    }
    for(var a:lanes.additions())ranges.add(new Range(a.station()-a.sign()*a.transition(),a.station(),"这里是汇入增车道渐变段"));
    for(var p:lanes.points())if(p.mergeLength()!=0){double d=horizontal(raw,p.anchor()==null?p.position():p.anchor());int sign=LanePoints.lane(raw,p).sign();ranges.add(new Range(d,d+sign*Math.abs(p.mergeLength()),"这里是车道点合流渐变段"));}
    for(var opening:lanes.openings()){
      double lo=Double.POSITIVE_INFINITY,hi=-lo;
      for(var p:opening.centerline()){double d=horizontal(mesh,p);lo=Math.min(lo,d-opening.halfWidth()-.4);hi=Math.max(hi,d+opening.halfWidth()+.4);}
      if(Double.isFinite(lo))ranges.add(new Range(lo,hi,"这里是匝道开口及护栏衔接范围"));
    }
    var controls=road.settings().options().attachments().points().stream().filter(RoadAttachments.Point::controlled).sorted(Comparator.comparingDouble(p->horizontal(raw,p.anchor()))).toList();
    for(int i=0;i<controls.size();i++)ranges.add(new Range(i==0?0:horizontal(raw,controls.get(i-1).anchor()),i+1==controls.size()?length:horizontal(raw,controls.get(i+1).anchor()),"这里是附属控制点调形的实际作用区间"));
    for(var c:road.settings().options().streetscape().crossings())ranges.add(new Range(c.start(),c.end(),"这里是慢行通道的立体转换范围"));
    for(int slot:RoadGantry.slots(mesh)){
      var members=RoadGantry.members(mesh,slot,road.structures());if(members.isEmpty())continue;
      double lo=Double.POSITIVE_INFINITY,hi=-lo;for(var p:members)for(V v:p.base()){double d=horizontal(mesh,v);lo=Math.min(lo,d);hi=Math.max(hi,d);}
      ranges.add(new Range(lo,hi,"这里穿过整座龙门架，请在架体外分割"));
    }
    var supportMembers=new HashSet<>(supports(mesh,road.structures()));
    for(var p:road.structures())if(!linear(p)||supportMembers.contains(p)){
      double lo=Double.POSITIVE_INFINITY,hi=-lo;for(V v:p.base()){double d=horizontal(mesh,v);lo=Math.min(lo,d);hi=Math.max(hi,d);}
      ranges.add(new Range(lo,hi,"这里穿过桥柱、灯具或不可拆整体构件"));
    }
    return List.copyOf(ranges);
  }
  private static void protectedRange(List<Range> ranges,double a,double b,double length,String reason){
    double from=Math.max(0,Math.min(a,b)),to=Math.min(length,Math.max(a,b));
    if(from<=to)ranges.add(new Range(from,to,reason));
  }
  public static Pair split(RoadRecord road,double d,RoadSlicing.Connection mode,UUID second){
    if(road.junction()!=null||road.toll()!=null||road.settings().style().ramp()||road.settings().options().lanePoints().link()!=null)throw new IllegalArgumentException("匝道本体、路口及收费站不能直接分割");
    if(road.mesh().closed())throw new IllegalArgumentException("闭合道路暂不支持分割");
    if(!Double.isFinite(d)||d<=0||d>=road.mesh().length())throw new IllegalArgumentException("未命中道路内部");
    var protection=protection(road);requireClear(protection,d,d);
    Node node=node(road.mesh(),d);BlockPos pos=BlockPos.containing(node.position().x(),node.position().y(),node.position().z());
    if(pos.equals(road.a())||pos.equals(road.b()))throw new IllegalArgumentException("新端点与已有端点方块重叠");
    return new Pair(range(road,0,d,road.id(),road.a(),pos,road.start(),node,mode),range(road,d,road.mesh().length(),second,pos,road.b(),node,road.end(),mode),node,pos,d,protection);
  }
  public static void requireClear(List<Range> ranges,double from,double to){for(var r:ranges)if(r.intersects(from,to))throw new IllegalArgumentException(r.reason()+String.format(Locale.ROOT,"（%.2f–%.2f 格）",r.from(),r.to()));}
  /** Retains original station distances, raw lane baseline, boundary triangles and all metadata. */
  public static RoadRecord range(RoadRecord road,double from,double to,UUID id,BlockPos a,BlockPos b,Node start,Node end,RoadSlicing.Connection mode){
    return range(road,from,to,id,a,b,start,end,mode,false);
  }
  /** An automatic intersection replaces surface furniture at its new mouths.
   * Keep physical bridge bearings protected; ordinary lossless splitting still
   * rejects any indivisible fixture cut by the selected plane. */
  static RoadRecord junctionRange(RoadRecord road,double from,double to,UUID id,BlockPos a,BlockPos b,Node start,Node end,RoadSlicing.Connection mode){
    return range(road,from,to,id,a,b,start,end,mode,true);
  }
  private static RoadRecord range(RoadRecord road,double from,double to,UUID id,BlockPos a,BlockPos b,Node start,Node end,RoadSlicing.Connection mode,boolean rebuildFurniture){
    Mesh mesh=road.mesh(),raw=road.rawMesh();double length=to-from;var s=road.settings();var o=s.options();
    var lanes=o.lanePoints();var points=lanes.points().stream().filter(p->owns(horizontal(raw,p.anchor()==null?p.position():p.anchor()),from,to,raw.length())).toList();
    var cuts=lanes.cuts().stream().filter(c->Math.max(c.begin(),c.end())>from&&Math.min(c.begin(),c.end())<to).map(c->new LaneSections.Cut(c.connection(),c.lane(),c.sign(),c.begin()-from,c.end()-from,c.transition(),c.replacement(),c.temporary(),c.arrival(),c.rectangular(),c.underpass())).toList();
    // Stable added lanes may continue beyond their original merge point; retain that equation.
    var additions=lanes.additions().stream().filter(v->v.fraction(from)>0||v.fraction(to)>0||Double.isFinite(v.end())&&Math.max(v.station(),v.end())>=from&&Math.min(v.station(),v.end())<=to).map(v->new LaneAdditions.Addition(v.connection(),v.slot(),v.sign(),v.station()-from,v.transition(),v.end()-from)).toList();
    var openings=lanes.openings().stream().filter(v->v.centerline().stream().anyMatch(p->owns(horizontal(raw,p),from,to,raw.length()))).toList();
    var access=new ArrayList<ElevatedAccess.Reservation>();
    for(var r:lanes.access()){
      double lo=r.cuts().stream().mapToDouble(c->Math.min(c.begin(),c.end())).min().orElseThrow();
      double hi=r.cuts().stream().mapToDouble(c->Math.max(c.begin(),c.end())).max().orElseThrow();
      for(var extra:r.additions()){lo=Math.min(lo,Math.min(extra.station(),extra.end())-extra.transition());hi=Math.max(hi,Math.max(extra.station(),extra.end())+extra.transition());}
      if(hi<=from||lo>=to)continue;
      if(lo<from-1e-6||hi>to+1e-6)throw new IllegalArgumentException("分割范围穿过高架出入口，请先删除该连接");
      access.add(ElevatedAccess.shifted(r,-from));
    }
    var laneData=new LanePoints.Data(points,lanes.link(),openings,lanes.priorityDepth(),cuts,additions,lanes.bands().stream().filter(v->v.station()>=from&&v.station()<=to).map(v->new LanePoints.Band(v.station()-from,v.low(),v.high())).toList(),access);
    var attached=o.attachments();var control=attached.points().stream().filter(p->owns(horizontal(raw,p.position()),from,to,raw.length())).toList();var spans=new ArrayList<RoadAttachments.Span>();
    for(var v:attached.spans()){double x=horizontal(raw,v.start()),y=horizontal(raw,v.end()),lo=Math.max(from,Math.min(x,y)),hi=Math.min(to,Math.max(x,y));if(hi<=lo)continue;
      V low=lo==Math.min(x,y)?(x<y?v.start():v.end()):RoadSlicing.at(raw,lo).center();V high=hi==Math.max(x,y)?(x<y?v.end():v.start()):RoadSlicing.at(raw,hi).center();spans.add(new RoadAttachments.Span(x<y?low:high,x<y?high:low,v.paint()));}
    var attachments=new RoadAttachments.Data(control,spans);var edits=new ArrayList<RoadGantry.Edit>();
    for(int slot:RoadGantry.slots(mesh)){var st=RoadGantry.station(mesh,slot);if(!owns(st.distance(),from,to,mesh.length()))continue;var e=st.edit();edits.add(new RoadGantry.Edit(slot,0,e.clearance(),e.kind(),e.reverse(),e.independent(),Math.max(0,Math.min(1,(st.distance()-from)/length)),true,(e.automatic()||o.infrastructure().gantryEdits().stream().noneMatch(v->v.slot()==slot))&&RoadGantry.members(mesh,slot,road.structures()).isEmpty()));}
    var streetscape=o.streetscape();var raised=new ArrayList<RoadStreetscape.Span>();for(var v:streetscape.raisedSpans())if(v.to()>from&&v.from()<to)raised.add(new RoadStreetscape.Span(Math.max(from,v.from())-from,Math.min(to,v.to())-from));
    var tunnels=streetscape.tunnels().stream().filter(v->v.to()>from&&v.from()<to).map(v->new RoadStreetscape.Span(Math.max(from,v.from())-from,Math.min(to,v.to())-from)).toList();
    var crossings=streetscape.crossings().stream().filter(v->v.end()>from&&v.start()<to).map(v->new RoadsideBypass.Span(v.side(),v.start()-from,v.from()-from,v.to()-from,v.end()-from,v.delta())).toList();
    s=s.options(o.ends(o.ends().trim(from,mesh.length()-to)).lanePoints(laneData).attachments(attachments).infrastructure(o.infrastructure().fixedStations(edits)).streetscape(streetscape.raisedSpans(raised).sideStructures(streetscape.sides().slice(from,to)).tunnels(tunnels).crossings(crossings)));
    var rawSlice=RoadSlicing.slice(raw,from,to,s);var shapedSlice=RoadSlicing.slice(mesh,from,to,s);
    var previous=road.fragment();var fragment=new RoadSlicing.Fragment(previous==null?road.id():previous.source(),(previous==null?0:previous.origin())+from,previous==null?mesh.length():previous.total(),mode,attachments,shapedSlice.geometry());
    var parts=new ArrayList<Part>();for(var p:rebuildFurniture?supports(mesh,road.structures()):road.structures()){Part clipped=part(mesh,p,from,to);if(clipped!=null)parts.add(clipped);}
    fragment=new RoadSlicing.Fragment(fragment.source(),fragment.origin(),fragment.total(),fragment.connection(),fragment.applied(),fragment.geometry(),supports(mesh,parts));
    int caps=(from==0?road.endCaps()&1:0)|(to==mesh.length()?road.endCaps()&2:0);
    return new RoadRecord(id,road.owner(),a,b,start,end,s,road.automatic(),road.clearance(),parts,caps,road.buildVersion(),road.assembly(),rawSlice.mesh().samples(),(road.furniturePhase()==null?RoadFurniture.Phase.DEFAULT:road.furniturePhase()).shift(from),null,null,fragment);
  }
  static List<Part> supports(Mesh mesh,List<Part> parts){
    var candidates=parts.stream().filter(p->p.material()==Material.CONCRETE&&p.model().isEmpty()&&(p.pier()||Math.abs(horizontal(mesh,p.a())-horizontal(mesh,p.b()))<=Math.max(3,p.width()*1.5))&&supportBelowDeck(mesh,p)).toList();
    var shafts=candidates.stream().filter(p->p.pier()&&p.height()>1).toList();var out=new ArrayList<Part>();
    for(var p:candidates){if(shafts.contains(p)){out.add(p);continue;}var box=box(p);for(var shaft:shafts)if(touches(box,box(shaft))){out.add(p);break;}}
    return List.copyOf(out);
  }
  private static boolean supportBelowDeck(Mesh mesh,Part part){
    // A horizontal cap intersects its graded owning deck at one longitudinal
    // edge. Testing every corner against the local underside misclassified the
    // cap as furniture and discarded it when its shaft became a fixed support.
    var top=part.top();V center=new V(0,0,0);for(var v:top)center=center.add(v);center=center.mul(1.0/top.size());
    var at=RoadQueries.horizontal(mesh,center).sample();return center.y()<=at.center().y()-mesh.settings().thickness()+.03;
  }
  private static double[] box(Part p){var q=new ArrayList<>(p.base());q.addAll(p.top());return new double[]{q.stream().mapToDouble(V::x).min().orElseThrow(),q.stream().mapToDouble(V::y).min().orElseThrow(),q.stream().mapToDouble(V::z).min().orElseThrow(),q.stream().mapToDouble(V::x).max().orElseThrow(),q.stream().mapToDouble(V::y).max().orElseThrow(),q.stream().mapToDouble(V::z).max().orElseThrow()};}
  private static boolean touches(double[] a,double[] b){for(int i=0;i<3;i++)if(a[i]>b[i+3]+.05||b[i]>a[i+3]+.05)return false;return true;}
  private static boolean owns(double d,double from,double to,double total){return d>=from-1e-7&&(d<to-1e-7||to>=total-1e-7&&d<=to+1e-7);}
  /** Only planar, longitudinal solids can be cut; modeled/indivisible fixtures stay whole. */
  static boolean linear(Part p){
    if(p.material().name().contains("LOG")||p.material().name().contains("LEAVES")||p.material().name().startsWith("FLOWER")||p.pier()||RoadSignals.signal(p)||RoadPoleModel.support(p)||TollModels.supports(p)||p.model().startsWith("sr:gantry/")||p.a().sub(p.b()).horizontalLength()<1e-5)return false;
    // These tags mark longitudinal prisms, not imported indivisible models.
    // A continuous planted median must remain splittable between its trees and
    // end caps; the planar/height checks below still govern its exact cut.
    if(!p.model().isEmpty()&&!Set.of("sr:cycle_wall","sr:cycle_bypass","sr:planting_soil","sr:cycle_planter").contains(p.model()))return false;
    var q=p.base();V n=cross(q.get(1).sub(q.get(0)),q.get(2).sub(q.get(0)));if(Math.abs(n.dot(q.get(3).sub(q.get(0))))>1e-7*Math.max(1,n.distance(new V(0,0,0))))return false;
    return p.heights()==null;
  }
  private static V cross(V a,V b){return new V(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
  static Part part(Mesh mesh,Part p,double from,double to){
    double lo=Double.POSITIVE_INFINITY,hi=-lo;for(V v:p.base()){double d=horizontal(mesh,v);lo=Math.min(lo,d);hi=Math.max(hi,d);}
    if(hi<from-1e-7||lo>to+1e-7)return null;
    if(lo>=from-1e-7&&hi<=to+1e-7){double d=horizontal(mesh,p.a().add(p.b()).mul(.5));return owns(d,from,to,mesh.length())?p:null;}
    if(!linear(p))throw new IllegalArgumentException("切口穿过不可拆的整体构件");
    Part result=p;if(from>0)result=clip(result,RoadSlicing.at(mesh,from),true);if(result!=null&&to<mesh.length())result=clip(result,RoadSlicing.at(mesh,to),false);return result;
  }
  private static Part clip(Part p,Sample cut,boolean after){
    V forward=cut.left().left().mul(after?-1:1);var q=p.base();double[] d=new double[4];for(int i=0;i<4;i++)d[i]=q.get(i).sub(cut.center()).dot(forward);
    if(Arrays.stream(d).allMatch(v->v>=-1e-8))return p;if(Arrays.stream(d).allMatch(v->v<=1e-8))return null;
    if((d[0]>=0)!=(d[1]>=0)||(d[2]>=0)!=(d[3]>=0))throw new IllegalArgumentException("该构件无法沿当前横断面无损裁分，请在构件外分割");
    V left=q.get(0).add(q.get(3).sub(q.get(0)).mul(d[0]/(d[0]-d[3]))),right=q.get(1).add(q.get(2).sub(q.get(1)).mul(d[1]/(d[1]-d[2])));
    V center=left.add(right).mul(.5),frame=left.sub(right).mul(.5);V oldA=q.get(0).add(q.get(1)).mul(.5),oldB=q.get(2).add(q.get(3)).mul(.5);
    return d[0]<0?new Part(center,oldB,p.width(),p.height(),false,p.material(),frame,q.get(3).sub(q.get(2)).mul(.5),p.model()):new Part(oldA,center,p.width(),p.height(),false,p.material(),q.get(0).sub(q.get(1)).mul(.5),frame,p.model());
  }
  private RoadSplitGeometry(){}
}
