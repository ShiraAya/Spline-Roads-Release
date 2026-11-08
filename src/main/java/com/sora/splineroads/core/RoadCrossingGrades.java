package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Local vertical transitions on the newly authored ordinary road only. */
public final class RoadCrossingGrades {
  private record Patch(double station,double half,double transition,double height,double gradeX,double gradeZ,V origin){
    double weight(double d){return Settings.smooth(Math.max(0,Math.min(1,(half+transition-Math.abs(d-station))/transition)));}
    double height(V at){return height+(at.x()-origin.x())*gradeX+(at.z()-origin.z())*gradeZ;}
  }
  private static double cross(V a,V b){return a.x()*b.z()-a.z()*b.x();}
  private static V median(Mesh m,Sample s){return s.at(RoadProfile.layout(m,s).medianCenter(),0);}
  public static Mesh fit(Mesh proposed,List<Mesh> existing){
    if(RoadProfile.catalog(proposed.settings().style()).type()!=RoadProfile.Type.ORDINARY||proposed.closed()||RoadTunnelFit.enabled(proposed.settings()))return proposed;
    var patches=new ArrayList<Patch>();
    for(var other:existing){
      if(other.closed()||other.settings().style()!=Style.RAILBED&&RoadProfile.catalog(other.settings().style()).type()!=RoadProfile.Type.ORDINARY)continue;
      if(proposed.max().x()<other.min().x()||proposed.min().x()>other.max().x()||proposed.max().z()<other.min().z()||proposed.min().z()>other.max().z())continue;
      var seen=new ArrayList<Double>();var index=RoadPathIndex.segments(other);List<RoadClearance.DetailedContact> contacts=null;
      for(int i=1;i<proposed.samples().size();i++){
        var a=proposed.samples().get(i-1);var b=proposed.samples().get(i);V p=median(proposed,a),u=median(proposed,b).sub(p);
        for(int segment:index.query(RoadPathIndex.segment(p,p.add(u)),2)){
          var c=other.samples().get(segment);var d=other.samples().get(segment+1);V q=median(other,c),v=median(other,d).sub(q);double det=cross(u,v);
          if(Math.abs(det)<1e-7*u.horizontalLength()*v.horizontalLength())continue;
          double t=cross(q.sub(p),v)/det,s=cross(q.sub(p),u)/det;if(t< -1e-7||t>1+1e-7||s< -1e-7||s>1+1e-7)continue;
          t=Math.max(0,Math.min(1,t));s=Math.max(0,Math.min(1,s));double station=a.distance()+(b.distance()-a.distance())*t,otherStation=c.distance()+(d.distance()-c.distance())*s;
          if(station<.01||station>proposed.length()-.01||otherStation<.01||otherStation>other.length()-.01||seen.stream().anyMatch(x->Math.abs(x-station)<.1))continue;seen.add(station);
          V ours=p.add(u.mul(t)),theirs=q.add(v.mul(s));double difference=ours.y()-theirs.y();
          if(Math.abs(difference)>1.0000001){
            if(contacts==null)contacts=RoadClearance.detailedContacts(proposed,other);
            for(var contact:contacts)if(contact.contact().from()<=station+other.settings().width()+1&&contact.contact().to()>=station-other.settings().width()-1&&contact.contact().blocked())throw new IllegalArgumentException("交汇高差超过1格且下方净高不足："+new RoadClearance.Conflict(contact.contact()).getMessage());
            continue;
          }
          if(Math.abs(difference)<1e-7)continue;
          double angle=Math.abs(cross(u.horizontalUnit(),v.horizontalUnit()));
          double half=Math.max(18,(a.halfWidth()+c.halfWidth())*2/Math.max(.1,angle));
          double transition=Math.max(24,Math.abs(difference)*40);
          if(station-half-transition<.5||station+half+transition>proposed.length()-.5)throw new IllegalArgumentException("交汇高差在1格以内，但端点附近没有足够的平齐及缓坡过渡空间，请延长新道路");
          double grade=other.settings().style()==Style.RAILBED?v.y()/Math.max(1e-8,v.horizontalLength()):0;
          var direction=v.horizontalUnit();patches.add(new Patch(station,half,transition,theirs.y(),grade*direction.x(),grade*direction.z(),theirs));
        }
      }
    }
    if(patches.isEmpty())return proposed;
    var stations=new TreeSet<Double>();proposed.samples().forEach(s->stations.add(s.distance()));
    for(var patch:patches)for(double d=patch.station()-patch.half()-patch.transition();d<=patch.station()+patch.half()+patch.transition();d+=.5){var before=stations.floor(d);var after=stations.ceiling(d);if((before==null||d-before>1e-5)&&(after==null||after-d>1e-5))stations.add(d);}
    var samples=new ArrayList<Sample>();
    for(double d:stations){var sample=RoadStructures.sample(proposed,d);double weight=0,target=sample.center().y();
      for(var patch:patches){double w=patch.weight(d);if(w>.999999&&weight>.999999&&Math.abs(target-patch.height(sample.center()))>.02)throw new IllegalArgumentException("相邻交汇需要不同高度且平齐区域重叠，请调整道路间距或端点高度");if(w>weight){weight=w;target=patch.height(sample.center());}}
      var p=sample.center();samples.add(new Sample(new V(p.x(),p.y()*(1-weight)+target*weight,p.z()),sample.left(),d,sample.halfWidth()));
    }
    double configured=proposed.settings().options().infrastructure().maxGrade();double cap=configured>0?configured:.2;
    for(int i=1;i<samples.size();i++){V delta=samples.get(i).center().sub(samples.get(i-1).center());if(Math.abs(delta.y())>delta.horizontalLength()*cap+1e-7)throw new IllegalArgumentException("交汇缓坡超过道路设置的最大坡度，请延长过渡距离或调整端点高度");}
    return RoadRibbon.mesh(samples,proposed.settings());
  }
  private RoadCrossingGrades(){}
}
