package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Ordinary ground crossings only. Every overlap vertex retains the 0.5 m height limit. */
public final class RailwayCrossings {
  public static boolean pair(Mesh a,Mesh b){boolean ar=a.settings().style()==Style.RAILBED,br=b.settings().style()==Style.RAILBED;return ar!=br&&RoadProfile.catalog((ar?b:a).settings().style()).type()==RoadProfile.Type.ORDINARY;}
  public static boolean ground(Mesh mesh,double from,double to){
    var mode=mesh.settings().structure();if(mode==Structure.GROUND)return true;if(mode!=Structure.AUTO)return false;
    var c=mesh.settings().options().streetscape();
    return java.util.stream.Stream.of(c.tunnels(),c.sides().negativeRaised(),c.sides().positiveRaised(),c.sides().negativeCut(),c.sides().positiveCut()).flatMap(List::stream).noneMatch(s->s.to()>from+1e-7&&s.from()<to-1e-7);
  }
  public static boolean allows(Mesh a,Mesh b,double from,double to,double otherFrom,double otherTo,double min,double max){
    if(!pair(a,b)||Math.max(Math.abs(min),Math.abs(max))>.5000001||!ground(a,from,to)||!ground(b,otherFrom,otherTo))return false;
    var x=RoadStructures.sample(a,(from+to)/2);var y=RoadStructures.sample(b,(otherFrom+otherTo)/2);
    return Math.abs(x.left().horizontalUnit().dot(y.left().horizontalUnit()))<.9999;
  }
  public static List<RoadClearance.DetailedContact> contacts(Mesh a,Mesh b){if(!pair(a,b))return List.of();return RoadClearance.detailedContacts(a,b).stream().filter(c->allows(a,b,c.contact().from(),c.contact().to(),c.otherFrom(),c.otherTo(),c.minimumDifference(),c.maximumDifference())).toList();}
  public static List<RoadClearance.DetailedContact> adjustableContacts(Mesh a,Mesh b){if(!pair(a,b))return List.of();return RoadClearance.detailedContacts(a,b).stream().filter(c->Math.max(Math.abs(c.minimumDifference()),Math.abs(c.maximumDifference()))<=1.0000001&&adjustable(a,c.contact().from(),c.contact().to())&&adjustable(b,c.otherFrom(),c.otherTo())&&Math.abs(RoadStructures.sample(a,(c.contact().from()+c.contact().to())/2).left().horizontalUnit().dot(RoadStructures.sample(b,(c.otherFrom()+c.otherTo())/2).left().horizontalUnit()))<.9999).toList();}
  private static boolean adjustable(Mesh mesh,double from,double to){
    // AUTO's old raised/cut classification must not prevent a new ground crossing.
    // The road is classified again after shaping; explicit structures and the fixed rail remain authoritative.
    return mesh.settings().style()!=Style.RAILBED&&mesh.settings().structure()==Structure.AUTO||ground(mesh,from,to);
  }
  public static boolean any(Mesh a,Mesh b){return !contacts(a,b).isEmpty();}
  public static boolean at(Mesh a,Mesh b,V point,double margin){
    if(!pair(a,b))return false;var x=RoadQueries.horizontal(a,point);var y=RoadQueries.horizontal(b,point);
    return within(a,x,point,margin)&&within(b,y,point,margin)&&x.horizontalDistance()<=x.sample().halfWidth()+margin&&y.horizontalDistance()<=y.sample().halfWidth()+margin
      &&allows(a,b,x.sample().distance(),x.sample().distance()+.001,y.sample().distance(),y.sample().distance()+.001,x.sample().center().y()-y.sample().center().y(),x.sample().center().y()-y.sample().center().y());
  }
  private static boolean within(Mesh mesh,RoadQueries.Projection q,V point,double margin){
    double along=point.sub(q.sample().center()).dot(q.tangent().horizontalUnit());
    return mesh.closed()||!((q.sample().distance()<1e-7&&along< -margin)||(q.sample().distance()>mesh.length()-1e-7&&along>margin));
  }
  /** Crossing furniture must leave the whole walking approach clear as well. */
  public static boolean walkingAt(Mesh rail,Mesh road,V point,double margin){
    if(rail.settings().style()!=Style.RAILBED||!pair(rail,road))return false;
    var q=RoadQueries.horizontal(road,point);int side=q.lateral()<0?-1:1;
    double walk=RoadSidewalks.walkWidth(road,road.settings().options().sidewalk(),q.sample(),side);
    if(walk<=0||q.horizontalDistance()>q.sample().halfWidth()+walk+margin||!within(road,q,point,margin))return false;
    var r=RoadQueries.horizontal(rail,point);double delta=r.sample().center().y()-q.sample().center().y();
    return within(rail,r,point,margin)&&r.horizontalDistance()<=r.sample().halfWidth()+margin
        &&allows(rail,road,r.sample().distance(),r.sample().distance()+.001,q.sample().distance(),q.sample().distance()+.001,delta,delta);
  }
  public static List<RoadJunction.Paint> stopLines(Mesh mesh){
    var out=new ArrayList<RoadJunction.Paint>();
    for(var crossing:mesh.settings().options().levelCrossings())for(int end:new int[]{-1,1}){
      double station=(end<0?crossing.from():crossing.to())+end*1.5;
      if(station<.3||station>mesh.length()-.3)continue;
      var s=RoadStructures.sample(mesh,station);
      for(var lane:LaneSections.live(mesh,station).lanes())if(lane.sign()==-end){
        double lateral=lane.position().sub(s.center()).dot(s.left());
        var a=RoadStructures.sample(mesh,station-.2);var b=RoadStructures.sample(mesh,station+.2);
        double low=lateral-lane.width()/2,high=lateral+lane.width()/2;
        out.add(new RoadJunction.Paint(List.of(a.at(high,-.014),a.at(low,-.014),b.at(low,-.014),b.at(high,-.014)),0xF1F0DE));
      }
    }return List.copyOf(out);
  }
  /** Full road width between the outer edges of the stop bars, including the track bed. */
  public static List<List<V>> paintCuts(Mesh mesh){
    var out=new ArrayList<List<V>>();
    for(var crossing:mesh.settings().options().levelCrossings()){
      double from=Math.max(0,crossing.from()-1.7),to=Math.min(mesh.length(),crossing.to()+1.7);
      for(double d=from;d<to-1e-8;d+=.5){
        var a=RoadStructures.sample(mesh,d);var b=RoadStructures.sample(mesh,Math.min(d+.5,to));
        out.add(List.of(a.at(-a.halfWidth()-.25,0),a.at(a.halfWidth()+.25,0),
            b.at(b.halfWidth()+.25,0),b.at(-b.halfWidth()-.25,0)));
      }
    }
    return List.copyOf(out);
  }
  /** Persisted local rail plane. The ordinary approach returns smoothly to its authored height. */
  public record Surface(double from,double to,V origin,double slopeX,double slopeZ,double transition){
    public Surface(double from,double to,V origin,double slopeX,double slopeZ){this(from,to,origin,slopeX,slopeZ,4);}
    public Surface{if(!RoadGeometry.finite(from,to,origin.x(),origin.y(),origin.z(),slopeX,slopeZ)||from<0||to<=from||!Double.isFinite(transition)||transition<1||transition>128||Math.abs(slopeX)>2||Math.abs(slopeZ)>2)throw new IllegalArgumentException("Invalid railway crossing profile");}
    public double height(V p){return origin.y()+(p.x()-origin.x())*slopeX+(p.z()-origin.z())*slopeZ;}
    public double weight(double station){return Settings.smooth(Math.max(0,Math.min(1,Math.min((station-from+transition)/transition,(to+transition-station)/transition))));}
  }
  public static List<Surface> surfaces(Mesh road,List<Mesh> rails){
    if(road.settings().style()==Style.RAILBED)return List.of();var out=new ArrayList<Surface>();
    for(var rail:rails){var hits=adjustableContacts(road,rail).stream().sorted(Comparator.comparingDouble(h->h.contact().from())).toList();
      for(int i=0;i<hits.size();){var first=hits.get(i);double from=first.contact().from(),to=first.contact().to();int end=i+1;
        while(end<hits.size()&&hits.get(end).contact().from()<=to+.1){to=Math.max(to,hits.get(end).contact().to());end++;}
        var q=RoadQueries.horizontal(rail,RoadStructures.sample(road,(from+to)/2).center());var tangent=q.tangent();double horizontal=tangent.horizontalLength(),grade=horizontal<1e-8?0:tangent.y()/horizontal;var along=tangent.horizontalUnit();
        double delta=0;for(int j=i;j<end;j++)delta=Math.max(delta,Math.max(Math.abs(hits.get(j).minimumDifference()),Math.abs(hits.get(j).maximumDifference())));double cap=road.settings().options().infrastructure().maxGrade();double transition=Math.max(4,Math.max(40*delta,cap>0?1.6*delta/cap:0));if(transition>128)throw new IllegalArgumentException("道口缓坡超过可用过渡长度，请提高道路允许坡度或调整高度");if(delta>1e-7&&(from-transition<.01||to+transition>road.length()-.01))throw new IllegalArgumentException("道口前后没有足够缓坡空间，请延长公路");out.add(new Surface(from,to,q.sample().center(),grade*along.x(),grade*along.z(),transition));i=end;
      }
    }
    out.sort(Comparator.comparingDouble(Surface::from));var merged=new ArrayList<Surface>();
    for(var next:out){
      if(!merged.isEmpty()){var prev=merged.get(merged.size()-1);
        if(next.from()<=prev.to()+.1&&Math.abs(prev.slopeX()-next.slopeX())<1e-6&&Math.abs(prev.slopeZ()-next.slopeZ())<1e-6&&Math.abs(prev.height(next.origin())-next.origin().y())<1e-5){
          merged.set(merged.size()-1,new Surface(prev.from(),Math.max(prev.to(),next.to()),prev.origin(),prev.slopeX(),prev.slopeZ(),Math.max(prev.transition(),next.transition())));continue;
        }
      }merged.add(next);
    }return List.copyOf(merged);
  }
  public static Mesh shape(Mesh mesh){
    var surfaces=mesh.settings().options().levelCrossings();if(surfaces.isEmpty()||mesh.settings().style()==Style.RAILBED)return mesh;
    var stations=new TreeSet<Double>();mesh.samples().forEach(s->stations.add(s.distance()));
    for(var surface:surfaces)for(double d=Math.max(0,surface.from()-surface.transition());d<Math.min(mesh.length(),surface.to()+surface.transition());d+=.5){
      Double before=stations.floor(d),after=stations.ceiling(d);
      if((before==null||d-before>1e-7)&&(after==null||after-d>1e-7))stations.add(d);
    }
    var samples=new ArrayList<Sample>();for(double d:stations){var s=RoadStructures.sample(mesh,d);double weight=0,target=0,bank=0;
      for(var patch:surfaces){double w=patch.weight(d);if(w>.999999&&weight>.999999&&Math.abs(target-patch.height(s.center()))>.02)throw new IllegalArgumentException("相邻铁路道口要求的高度相互冲突");if(w>weight){weight=w;target=patch.height(s.center());bank=patch.slopeX()*s.left().x()+patch.slopeZ()*s.left().z();}}
      samples.add(weight==0?s:new Sample(new V(s.center().x(),s.center().y()*(1-weight)+target*weight,s.center().z()),new V(s.left().x(),s.left().y()*(1-weight)+bank*weight,s.left().z()),d,s.halfWidth()));
    }
    V min=new V(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE),max=new V(-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE);
    for(var sample:samples)for(int side:new int[]{-1,1}){var p=sample.at(side*sample.halfWidth(),0);min=new V(Math.min(min.x(),p.x()),Math.min(min.y(),p.y()-mesh.settings().thickness()),Math.min(min.z(),p.z()));max=new V(Math.max(max.x(),p.x()),Math.max(max.y(),p.y()),Math.max(max.z(),p.z()));}
    return new Mesh(List.copyOf(samples),mesh.settings(),min,max,mesh.length(),mesh.closed(),mesh.controlPoint(),mesh.controls(),mesh.reference(),mesh.slicing());
  }
  private RailwayCrossings(){}
}
