package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** The common boundary of lane-connected decks, at the rail's actual inset.
 * Opening metadata is a broad furniture-clearance capsule, not the paved outline:
 * using it here removes exposed rails around tapers and grade-separated mouths.
 * Triangles retain real deck heights, holes, widths and flat longitudinal ends. */
public final class RoadRailJoin {
  // Keep even the 0.62 m highway footing wholly inside its owning deck.
  // A 0.16 m inset left the base in the adjacent live-lane clearance corridor.
  public static final double INSET=.34;
  private static final double JOIN_HEIGHT=.025;
  /** Fit a new permanent-cut railing inside its existing shoulder. Do not
   * widen the saved deck: an already connected continuation owns that exact port. */
  public static double inset(Mesh mesh,Sample at,int side){
    if(mesh.reference()==null)return INSET;
    var raw=LaneSections.reference(mesh);var original=RoadStructures.sample(raw,at.distance());boolean cutSide=false;
    for(var cut:mesh.settings().options().lanePoints().cuts())if(!cut.temporary()&&cut.rectangular()&&cut.removed(at.distance())>.999){
      var lane=LanePoints.lane(raw,at.distance(),cut.lane());
      if(Math.signum(lane.position().sub(original.center()).dot(original.left()))==side){cutSide=true;break;}
    }
    if(!cutSide)return INSET;
    var layout=RoadProfile.layout(mesh,at);double shoulder=side>0?at.halfWidth()-layout.motorMax():at.halfWidth()+layout.motorMin();
    double half=RoadProfile.highway(mesh.settings().style())?.31:.21;
    return Math.max(half+.015,Math.min(INSET,shoulder-half-.015));
  }
  public record Span(V a,V b) {}
  /** Higher-priority neighbour owns coincident boundaries; strict interiors always win. */
  public record Neighbor(Mesh mesh,boolean ownsBoundary) {}
  private record Range(double from,double to) {}
  private record Face(V a,V b,V c,boolean ownsBoundary,V tangent,Range auxiliary,double heightTolerance) {
    double cross(V p,V q,V r){return (q.x()-p.x())*(r.z()-p.z())-(q.z()-p.z())*(r.x()-p.x());}
    double height(V p){
      double det=cross(a,b,c);
      return a.y()+((p.x()-a.x())*(c.z()-a.z())-(p.z()-a.z())*(c.x()-a.x()))/det*(b.y()-a.y())
          +((b.x()-a.x())*(p.z()-a.z())-(b.z()-a.z())*(p.x()-a.x()))/det*(c.y()-a.y());
    }
    Range intersection(V from,V to){
      double[] t={0,1};V[] p={a,b,c};
      for(int i=0;i<3;i++) {
        V x=p[i],y=p[(i+1)%3];double length=y.sub(x).horizontalLength();
        double fa=cross(x,y,from)/length,fb=cross(x,y,to)/length;
        // A non-owner cannot erase the coincident perimeter of its owner. Positive
        // epsilon also keeps tangential point contacts from creating visible holes.
        if(!clip(t,fa,fb,ownsBoundary?-1e-7:1e-7))return null;
      }
      double da=from.y()-height(from),db=to.y()-height(to);
      if(!clip(t,da,db,-heightTolerance)||!clip(t,-da,-db,-heightTolerance))return null;
      return t[1]-t[0]>1e-7?new Range(t[0],t[1]):null;
    }
  }
  private record Edge(V a,V b,boolean ownsBoundary,int profile,double heightTolerance) {}
  private static final int NONE=-1,SOUND=4;
  /** One profile per actual outer assembly. A deck without a rail cannot own a
   * coincident rail or its terminal post merely because its paint has priority. */
  private static int profile(Mesh mesh,Sample sample,int side){
    var settings=mesh.settings();var outer=settings.options().outerRail();
    if(settings.structure()==Structure.TUNNEL||outer==RoadProfile.OuterRail.OFF)return NONE;
    boolean highway=RoadProfile.highway(settings.style());
    boolean raised=RoadStreetscape.raised(mesh,sample,side);
    boolean modern=RoadStructures.railModern(settings.style());
    if(!modern)return settings.structure()==Structure.GROUND?NONE:5;
    if(!(outer==RoadProfile.OuterRail.ON||highway||settings.style().ramp()||raised))return NONE;
    if(raised&&!settings.style().ramp()&&outer.sound(side))return SOUND;
    return profile(highway,raised);
  }
  private static int profile(boolean highway,boolean raised){return (highway?2:0)+(raised?1:0);}
  private final Map<Long,List<Face>> grid=new HashMap<>();
  private final Map<Long,List<Face>> material=new HashMap<>();
  private final Map<Long,List<Edge>> edges=new HashMap<>();
  private final Mesh current;
  private double buildingTolerance=JOIN_HEIGHT;
  private static boolean normalJoin(Mesh ramp,Mesh host){
    if(ramp==null)return false;var link=ramp.settings().options().lanePoints().link();if(link==null)return false;
    var points=host.settings().options().lanePoints().points();
    return link.options().departure()==LanePoints.Departure.BRANCH&&points.stream().anyMatch(p->p.id().equals(link.from().point()))
        ||link.options().arrival()==LanePoints.Arrival.FLOW&&points.stream().anyMatch(p->p.id().equals(link.to().point()));
  }
  public RoadRailJoin(List<Neighbor> neighbors){this(null,neighbors,false);}
  public RoadRailJoin(Mesh current,List<Neighbor> neighbors){this(current,neighbors,false);}
  public static RoadRailJoin paint(Mesh current,List<Neighbor> neighbors){return new RoadRailJoin(current,neighbors,true);}
  public static RoadRailJoin paintBoundary(Mesh current,List<Neighbor> neighbors){return new RoadRailJoin(current,neighbors,true,false);}
  private static Range auxiliary(Mesh ramp,Mesh host){
    if(ramp==null||host==null)return null;var link=ramp.settings().options().lanePoints().link();if(link==null){var range=RoadJunction.auxiliaryRange(ramp,host);return range==null?null:new Range(range[0],range[1]);}
    for(boolean source:new boolean[]{true,false}){
      if(source?!link.options().sourceExtra():!link.options().targetExtra())continue;
      var ref=source?link.from():link.to();
      for(var point:host.settings().options().lanePoints().points())if(point.id().equals(ref.point())){
        var lane=LanePoints.lane(host,point);double station=lane.station()+(source?0:lane.sign()*link.targetOffset());
        try{var samples=LaneRampApproach.build(host,point.lane(),station,source,ramp.settings(),link.options().transition());
          double length=0;for(int i=1;i<samples.size();i++)length+=samples.get(i).center().distance(samples.get(i-1).center());
          return source?new Range(0,length):new Range(ramp.length()-length,ramp.length());
        }catch(IllegalArgumentException ignored){/* A continuation may own the rest of the port; ordinary clipping still applies. */}
      }
    }
    return null;
  }
  /** Resolve an auxiliary approach across actual continuous ribbons. The clicked
   * point can be on the next record while the taper begins on this one. */
  private static Map<Mesh,Range> auxiliary(Mesh ramp,List<Mesh> context){
    var result=new IdentityHashMap<Mesh,Range>();
    if(ramp==null)return result;var link=ramp.settings().options().lanePoints().link();
    if(link==null)return result;
    for(boolean source:new boolean[]{true,false}){
      if(source?!link.options().sourceExtra():!link.options().targetExtra())continue;
      var ref=source?link.from():link.to();
      for(var host:context)for(var point:host.settings().options().lanePoints().points())if(point.id().equals(ref.point()))try{
        var lane=LanePoints.lane(host,point);double end=lane.station()+lane.sign()*(source?0:link.targetOffset());
        double run=LaneRampApproach.length(host,link.options().transition());
        var used=Collections.newSetFromMap(new IdentityHashMap<Mesh,Boolean>());
        var samples=LaneRampApproach.build(d->{var at=auxiliaryStation(context,host,point.lane(),end+lane.sign()*(source?d:d-run));used.add(at.mesh());return at;},source,ramp.settings(),link.options().transition(),host.settings().structure()==Structure.TUNNEL);
        double length=0;for(int i=1;i<samples.size();i++)length+=samples.get(i).center().distance(samples.get(i-1).center());
        var range=source?new Range(0,length):new Range(ramp.length()-length,ramp.length());
        for(var connected:used)result.put(connected,range);
      }catch(IllegalArgumentException ignored){/* Missing/ambiguous continuations retain their barriers. */}
    }
    return result;
  }
  /** Actual auxiliary paving interval on this ramp for a host in its continuous chain. */
  public static double[] auxiliaryInterval(Mesh ramp,Mesh host,List<Mesh> context){
    var range=auxiliary(ramp,context).get(host);
    if(range==null)range=auxiliary(ramp,host);
    return range==null?null:new double[]{range.from(),range.to()};
  }
  private static LaneRampApproach.Station auxiliaryStation(List<Mesh> context,Mesh root,int slot,double station){
    var seen=Collections.newSetFromMap(new IdentityHashMap<Mesh,Boolean>());Mesh current=root;
    while(station< -1e-6||station>current.length()+1e-6){
      RoadPlanningBudget.check();if(!seen.add(current))throw new IllegalArgumentException("辅助车道连续道路环回");
      boolean before=station<0;double edge=before?0:current.length(),remaining=Math.abs(station-edge);
      var at=RoadStructures.sample(current,edge);var lane=LanePoints.lane(current,edge,slot);
      Mesh found=null;int nextSlot=-1;double nextStation=0;
      for(var candidate:context)if(candidate!=current&&!seen.contains(candidate)&&!candidate.closed()&&candidate.settings().options().lanePoints().link()==null&&LanePoints.supported(candidate.settings()))for(boolean start:new boolean[]{true,false}){
        var port=start?candidate.first():candidate.last();if(at.center().distance(port.center())>.25)continue;
        if(!LanePoints.sameSection(current,edge,candidate,port.distance()))continue;
        for(int otherSlot:LaneAdditions.slots(candidate,port.distance())){
          var next=LanePoints.lane(candidate,port.distance(),otherSlot);
          if(lane.direction().dot(next.direction())<.995||lane.position().distance(next.position())>.25||Math.abs(lane.width()-next.width())>.25)continue;
          int travel=(before?-1:1)*lane.sign();if((start?1:-1)!=travel*next.sign())continue;
          if(found!=null)throw new IllegalArgumentException("辅助车道接缝不唯一");
          found=candidate;nextSlot=otherSlot;nextStation=port.distance()+(start?remaining:-remaining);
        }
      }
      if(found==null)throw new IllegalArgumentException("辅助车道接缝不连续");current=found;slot=nextSlot;station=nextStation;
    }
    return new LaneRampApproach.Station(current,slot,Math.max(0,Math.min(current.length(),station)));
  }
  private static double inset(Mesh mesh,Sample at,int side,boolean paint){return paint?Math.abs(side*at.halfWidth()-RoadSurface.edgeOffset(mesh,at,side)):inset(mesh,at,side);}
  private RoadRailJoin(Mesh current,List<Neighbor> neighbors,boolean paint){
    this(current,neighbors,paint,paint);
  }
  private RoadRailJoin(Mesh current,List<Neighbor> neighbors,boolean paint,boolean paintInset){
    this.current=current;
    var context=new ArrayList<Mesh>();if(current!=null)context.add(current);for(var n:neighbors)context.add(n.mesh());
    var auxiliaryCache=new IdentityHashMap<Mesh,Map<Mesh,Range>>();
    for(var neighbor:neighbors){var mesh=neighbor.mesh();
      // A normal fork follows the parent's longitudinal grade. Its differently
      // oriented transverse samples can differ by centimetres inside that shared
      // surface. Separate-height DETACH/EXTRA decks keep the strict tolerance.
      var ourAuxiliary=auxiliaryCache.computeIfAbsent(current,m->auxiliary(m,context)).get(mesh);
      if(ourAuxiliary==null)ourAuxiliary=auxiliary(current,mesh);
      var theirAuxiliary=auxiliaryCache.computeIfAbsent(mesh,m->auxiliary(m,context)).get(current);
      if(theirAuxiliary==null)theirAuxiliary=auxiliary(mesh,current);
      boolean legacy=ourAuxiliary!=null&&current.settings().options().lanePoints().link()==null
          ||theirAuxiliary!=null&&mesh.settings().options().lanePoints().link()==null;
      buildingTolerance=legacy||RoadJunction.sharedRampFork(current,mesh)||normalJoin(current,mesh)||current!=null&&normalJoin(mesh,current)?.12:JOIN_HEIGHT;
      for(int i=1;i<mesh.samples().size();i++){
        var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);
        for(var strip:LaneDeck.strips(mesh,a,b)) {
          // The rail is inset but the supporting pavement is not. A .15-block
          // welded overlap used by an auxiliary lane used to disappear after both
          // faces were inset .16, leaving a barrier across an otherwise open merge.
          double station=(a.distance()+b.distance())/2;
          V tangent=theirAuxiliary!=null&&station>=theirAuxiliary.from()&&station<=theirAuxiliary.to()?null:b.center().sub(a.center()).horizontalUnit();
          add(material,strip.al(),strip.ar(),strip.br(),true,tangent,ourAuxiliary);
          add(material,strip.al(),strip.br(),strip.bl(),true,tangent,ourAuxiliary);
          // Only exposed band boundaries get an inset. Zero-size slots split a
          // continuous face too, but those split lines must not become false gutters.
          double highA=strip.al().distance(a.at(a.halfWidth(),0))<1e-6?inset(mesh,a,1,paintInset):INSET,highB=strip.bl().distance(b.at(b.halfWidth(),0))<1e-6?inset(mesh,b,1,paintInset):INSET;
          double lowA=strip.ar().distance(a.at(-a.halfWidth(),0))<1e-6?inset(mesh,a,-1,paintInset):INSET,lowB=strip.br().distance(b.at(-b.halfWidth(),0))<1e-6?inset(mesh,b,-1,paintInset):INSET;
          V al=strip.al().sub(a.left().mul(strip.highWall()?highA:0));
          V ar=strip.ar().add(a.left().mul(strip.lowWall()?lowA:0));
          V bl=strip.bl().sub(b.left().mul(strip.highWall()?highB:0));
          V br=strip.br().add(b.left().mul(strip.lowWall()?lowB:0));
          if(al.sub(ar).dot(a.left())<0||bl.sub(br).dot(b.left())<0)continue;
          var sample=RoadStructures.sample(mesh,(a.distance()+b.distance())/2);
          int high=profile(mesh,sample,1),low=profile(mesh,sample,-1);
          boolean boundaryOwner=neighbor.ownsBoundary()&&(paint||high!=NONE||low!=NONE);
          add(al,ar,br,boundaryOwner);add(al,br,bl,boundaryOwner);
          // Hole edges still clip real material, but no invented rail/post may be
          // mitered to a hole boundary which the outer-rail planner never emits.
          if(strip.highWall()&&strip.al().distance(a.at(a.halfWidth(),0))<1e-6&&strip.bl().distance(b.at(b.halfWidth(),0))<1e-6)
            edge(al,bl,neighbor.ownsBoundary(),high);
          if(strip.lowWall()&&strip.ar().distance(a.at(-a.halfWidth(),0))<1e-6&&strip.br().distance(b.at(-b.halfWidth(),0))<1e-6)
            edge(ar,br,neighbor.ownsBoundary(),low);
        }
      }
    }
  }
  /** An exposed union rail may share the perimeter shoulder of coplanar decks.
   * Clipping chooses its owner first. Clearance must not then erase that owner
   * merely because its footing also touches the other deck's outer shoulder. */
  public static boolean sharedRail(Mesh neighbor,V a,V b){
    for(V point:List.of(a,a.add(b).mul(.5),b)){
      var q=RoadQueries.horizontal(neighbor,point);var at=q.sample();
      if(Math.abs(point.y()-at.at(q.lateral(),0).y())>.12)return false;
      int side=q.lateral()<0?-1:1;double edge=at.halfWidth()-inset(neighbor,at,side);
      if(Math.abs(q.lateral())<edge-.015||LaneDeck.outerOpening(neighbor,at.distance(),side))return false;
    }
    return true;
  }
  private void add(V a,V b,V c,boolean owner){add(grid,a,b,c,owner);}
  private void add(Map<Long,List<Face>> target,V a,V b,V c,boolean owner){
    add(target,a,b,c,owner,null,null);
  }
  private void add(Map<Long,List<Face>> target,V a,V b,V c,boolean owner,V tangent,Range auxiliary){
    double area=(b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());
    if(Math.abs(area)<1e-10)return;if(area<0){V swap=b;b=c;c=swap;}
    var face=new Face(a,b,c,owner,tangent,auxiliary,buildingTolerance);
    for(int x=cell(Math.min(a.x(),Math.min(b.x(),c.x()))-1e-6);x<=cell(Math.max(a.x(),Math.max(b.x(),c.x()))+1e-6);x++)
      for(int z=cell(Math.min(a.z(),Math.min(b.z(),c.z()))-1e-6);z<=cell(Math.max(a.z(),Math.max(b.z(),c.z()))+1e-6);z++)
        target.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(face);
  }
  private void edge(V a,V b,boolean owner,int profile){
    if(profile==NONE||a.sub(b).horizontalLength()<1e-9)return;var edge=new Edge(a,b,owner,profile,buildingTolerance);
    for(int x=cell(Math.min(a.x(),b.x())-1e-5);x<=cell(Math.max(a.x(),b.x())+1e-5);x++)
      for(int z=cell(Math.min(a.z(),b.z())-1e-5);z<=cell(Math.max(a.z(),b.z())+1e-5);z++)
        edges.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(edge);
  }
  private boolean touches(Edge edge,V point){
    V d=edge.b().sub(edge.a());double length=d.x()*d.x()+d.z()*d.z();
    double t=((point.x()-edge.a().x())*d.x()+(point.z()-edge.a().z())*d.z())/length;
    if(t< -1e-6||t>1+1e-6)return false;V at=edge.a().add(d.mul(Math.max(0,Math.min(1,t))));
    return point.sub(at).horizontalLength()<5e-5&&Math.abs(point.y()-at.y())<=edge.heightTolerance();
  }
  /** Reciprocal mitres share their cut face across records, not just their centre.
   * Frames are unit-width and may be sign-reversed on a reversed reference direction. */
  public V joint(V point,V direction){return joint(point,direction,-2);}
  public V joint(V point,V direction,boolean highway,boolean raised){return joint(point,direction,profile(highway,raised));}
  private V joint(V point,V direction,int expected){
    V a=direction.horizontalUnit(),found=null;double smallest=1;
    for(var edge:edges.getOrDefault(key(cell(point.x()),cell(point.z())),List.of()))if((expected==-2||edge.profile()==expected)&&touches(edge,point)){
      V b=edge.b().sub(edge.a()).horizontalUnit();double dot=a.dot(b);if(dot<0){b=b.mul(-1);dot=-dot;}
      if(dot<.25||dot>=smallest-1e-9)continue;smallest=dot;found=a.left().add(b.left()).mul(1/(1+dot));
    }
    return found;
  }
  /** One terminal post at a shared edge, with the same stable owner as the rail. */
  public boolean ownsPost(V point){return ownsPost(point,-2);}
  public boolean ownsPost(V point,boolean highway,boolean raised){return ownsPost(point,profile(highway,raised));}
  private boolean ownsPost(V point,int expected){
    for(var edge:edges.getOrDefault(key(cell(point.x()),cell(point.z())),List.of()))
      if(edge.ownsBoundary()&&edge.profile()>=0&&edge.profile()<=3&&(expected==-2||edge.profile()==expected)&&touches(edge,point))return false;
    return true;
  }
  /** Exact continuous cut positions, not rounded .5-block visibility samples. */
  public List<Span> exposed(V a,V b){return exposed(grid,a,b);}
  private List<Span> exposed(Map<Long,List<Face>> surface,V a,V b){return exposed(surface,a,b,false);}
  private List<Span> exposed(Map<Long,List<Face>> surface,V a,V b,boolean parallelOnly){
    if(a.sub(b).horizontalLength()<1e-9)return List.of();
    var faces=new LinkedHashSet<Face>();
    for(int x=cell(Math.min(a.x(),b.x())-1e-6);x<=cell(Math.max(a.x(),b.x())+1e-6);x++)
      for(int z=cell(Math.min(a.z(),b.z())-1e-6);z<=cell(Math.max(a.z(),b.z())+1e-6);z++)
        faces.addAll(surface.getOrDefault(key(x,z),List.of()));
    var cuts=new ArrayList<Range>();V tangent=b.sub(a).horizontalUnit();
    double station=current==null?Double.NaN:RoadQueries.horizontal(current,a.add(b).mul(.5)).sample().distance();
    for(var face:faces){
      boolean auxiliary=face.auxiliary()!=null&&station>=face.auxiliary().from()&&station<=face.auxiliary().to();
      if(parallelOnly&&!auxiliary&&face.tangent()!=null&&Math.abs(face.tangent().dot(tangent))<.999999)continue;
      var hit=face.intersection(a,b);if(hit!=null)cuts.add(hit);}
    cuts.sort(Comparator.comparingDouble(Range::from));
    var result=new ArrayList<Span>();double at=0;V d=b.sub(a);
    double epsilon=1e-5/d.horizontalLength(); // Reject only sub-numerical cracks between adjacent triangle faces.
    for(var cut:cuts){if(cut.from()>at+epsilon)result.add(new Span(a.add(d.mul(at)),a.add(d.mul(cut.from()))));at=Math.max(at,cut.to());}
    if(at<1-epsilon)result.add(new Span(a.add(d.mul(at)),b));return List.copyOf(result);
  }
  public List<Span> exposed(V a,V b,V outside){return exposed(a,b,outside,INSET);}
  public List<Span> exposed(V a,V b,V outside,double inset){
    if(inset<1e-9)return exposed(material,a,b);
    V mid=a.add(b).mul(.5),direction=outside.sub(mid);
    if(direction.horizontalLength()<1e-8)return exposed(a,b);
    // Clip shared merge seams against actual neighboring pavement at the outer
    // edge, not its inset rail. Real positive gaps and height separation retain rails.
    V shift=direction.horizontalUnit().mul(inset+1e-5);
    var inside=exposed(a,b);
    // At a diverging/merging nose the inset boundaries meet before the raw deck
    // boundary. Clipping both independently cuts a gap between their meeting tips.
    // Exact parallel auxiliary seams still need the material test (their inset
    // polygons are disjoint despite the small deliberate paved overlap).
    var boundary=exposed(material,a.add(shift),b.add(shift),true);
    var out=new ArrayList<Span>();V d=b.sub(a);double length=d.dot(d);
    for(var first:inside)for(var second:boundary){
      double lo=Math.max(first.a().sub(a).dot(d)/length,second.a().sub(shift).sub(a).dot(d)/length);
      double hi=Math.min(first.b().sub(a).dot(d)/length,second.b().sub(shift).sub(a).dot(d)/length);
      if(hi-lo>1e-7)out.add(new Span(a.add(d.mul(lo)),a.add(d.mul(hi))));
    }
    return List.copyOf(out);
  }
  /** Account for the slab end cap and the inward rail offset at a declared joining port.
   * The caller supplies only linked neighbors; no proximity-only opening is invented. */
  public List<Span> exposedMouth(V a,V b){return exposed(material,a,b);}
  public static RoadRailJoin mouths(List<Mesh> linked){
    var extensions=new ArrayList<Neighbor>();
    for(var mesh:linked)for(boolean start:new boolean[]{true,false}){
      int n=mesh.samples().size();if(n<2)continue;
      var at=start?mesh.first():mesh.last();var next=mesh.samples().get(start?1:n-2);
      V delta=at.center().sub(next.center());double run=delta.horizontalLength();if(run<1e-8)continue;
      var layout=RoadProfile.layout(mesh,at);
      // The fitted endpoint includes only the selected lane and its legitimate
      // outside shoulder. Clip that whole paved mouth: using motor width leaves
      // a transverse white/rail stub across its shoulder at an ADD/DETACH seam.
      double half=at.halfWidth();
      V center=at.center(),extension=delta.mul(.86/run);
      var samples=List.of(new Sample(center.sub(extension),at.left(),0,half),new Sample(center.add(extension),at.left(),1.72,half));
      extensions.add(new Neighbor(RoadRibbon.mesh(samples,mesh.settings()),true));
    }
    return new RoadRailJoin(extensions);
  }
  private static boolean clip(double[] t,double a,double b,double min){
    double d=b-a;if(Math.abs(d)<1e-12)return a>=min;
    double crossing=(min-a)/d;if(d>0)t[0]=Math.max(t[0],crossing);else t[1]=Math.min(t[1],crossing);
    return t[0]<=t[1]&&t[1]>=0&&t[0]<=1;
  }
  private static int cell(double v){return (int)Math.floor(v/8);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
