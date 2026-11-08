package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** A fixed shared cross-section around explicitly selected real rail paths. */
public final class RailbedGeometry {
  public static final int MAX_TRACKS=8;
  public static final double MAX_WIDTH=64, HEIGHT_TOLERANCE=.01, WALL=.35, HEADROOM=6;
  public record Plan(Mesh mesh,double minimumWidth,double offset,int tracks,List<RailbedPath.Path> paths){public Plan{paths=List.copyOf(paths);}}
  private record Hit(V point,V tangent,double parameter){}
  private record IndexedPath(List<V> points,RoadBoundsIndex index){
    IndexedPath(List<V> points){this(points,index(points));}
    private static RoadBoundsIndex index(List<V> points){var bounds=new ArrayList<RoadBoundsIndex.Bounds>();for(int i=1;i<points.size();i++)bounds.add(RoadPathIndex.segment(points.get(i-1),points.get(i)));return new RoadBoundsIndex(bounds,16);}
  }
  private record Stations(List<V> points,List<V> tangents){}
  private static boolean official(RailbedPath.Path path){return path.segments().stream().allMatch(s->!s.styles().isEmpty()&&s.styles().stream().allMatch(id->id.equals("default")||id.replaceFirst("_[12]$", "").equals("default_3d")||id.replaceFirst("_[12]$", "").equals("default_3d_siding")));}
  public static String unsupported(List<RailbedPath.Path> paths){for(var path:paths)if(!official(path))return "轨道模型尚未标定："+path.segments().stream().flatMap(s->s.styles().stream()).distinct().toList();return null;}
  public static Plan plan(List<RailbedPath.Path> paths,double requestedWidth,Structure mode){
    if(paths.isEmpty()||paths.size()>MAX_TRACKS)throw new IllegalArgumentException("请选择 1–8 股轨道");
    if(paths.stream().map(p->p.segments().stream().map(RailbedPath.Segment::id).sorted().toList()).distinct().count()!=paths.size())throw new IllegalArgumentException("不能重复加入同一轨道");
    var rails=paths.stream().map(p->new IndexedPath(p.positions())).toList();var stations=stations(rails);var base=stations.points();if(base.size()>RoadLimits.MAX_SAMPLES)throw new IllegalArgumentException("铁路采样过多，请分段选择");
    double lo=Double.POSITIVE_INFINITY,hi=-lo;var sections=new ArrayList<Sample>();double swept=rails.stream().mapToDouble(r->sweep(r.points())).max().orElse(0);
    double[][] offsets=new double[paths.size()][base.size()];double[] progress=new double[paths.size()];Arrays.fill(progress,Double.NaN);
    int[] directions=new int[paths.size()];
    for(int i=0;i<base.size();i++){
      RoadPlanningBudget.check();
      V p=base.get(i),forward=stations.tangents().get(i),left=forward.left();double floor=p.y();
      double margin=2.5+WALL+swept;
      for(int track=0;track<rails.size();track++){
        Hit hit=track==0?new Hit(p,forward,i):intersection(rails.get(track),p,forward,i==0||i==base.size()-1);
        if(Math.abs(hit.tangent.dot(forward))<Math.cos(Math.toRadians(15)))throw new IllegalArgumentException("邻轨不平行，请缩短区间或分开生成");
        if(Math.abs(hit.point.y()-p.y())>HEIGHT_TOLERANCE)throw new IllegalArgumentException("轨道支承高度不一致："+Math.round(p.x())+", "+Math.round(p.y())+", "+Math.round(p.z()));
        floor=Math.min(floor,hit.point.y());double lateral=hit.point.sub(p).dot(left);offsets[track][i]=lateral;
        lo=Math.min(lo,lateral-margin);hi=Math.max(hi,lateral+margin);
        if(!Double.isNaN(progress[track])){double delta=hit.parameter-progress[track];int sign=delta>1e-6?1:delta< -1e-6?-1:0;
          if(sign!=0&&directions[track]!=0&&sign!=directions[track])throw new IllegalArgumentException("邻轨存在折返或重复横断面，请分开生成：断面 "+i+"，轨道 "+track+"，变化 "+delta);if(sign!=0)directions[track]=sign;}
        progress[track]=hit.parameter;
      }
      sections.add(new Sample(new V(p.x(),floor,p.z()),left,i,1));
    }
    for(int a=0;a<rails.size();a++)for(int b=a+1;b<rails.size();b++){
      double first=offsets[a][0]-offsets[b][0];for(int i=0;i<base.size();i++)if(Math.abs(offsets[a][i]-offsets[b][i])<2.5||first*(offsets[a][i]-offsets[b][i])<=0)throw new IllegalArgumentException("轨道间距不足或横向顺序交换，请分开生成");
    }
    double minimum=Math.ceil((hi-lo)*2-1e-9)/2,width=requestedWidth==0?minimum:requestedWidth,offset=(hi+lo)/2;
    if(!Double.isFinite(width)||width<minimum-1e-8||width>MAX_WIDTH)throw new IllegalArgumentException("路基总宽需为 "+minimum+"–64 格；超限请分组生成");
    var settings=new Settings(Mode.STRAIGHT,Style.RAILBED,width,.5,.35,90).structure(Structure.GROUND);
    var samples=sections.stream().map(s->new Sample(s.center().add(s.left().mul(offset)),s.left(),s.distance(),width/2)).toList();
    return new Plan(RoadRibbon.mesh(samples,settings),minimum,offset,paths.size(),paths);
  }
  /** Conservative vehicle sweep for the supported 26 m / 18 m bogie-spacing preset. */
  public static double sweep(List<V> points){double result=0;
    for(int i=1;i+1<points.size();i++){var before=tangent(points,i-1);var after=tangent(points,i+1);double angle=Math.acos(Math.max(-1,Math.min(1,before.dot(after))));double arc=points.get(i).distance(points.get(i-1))+points.get(i+1).distance(points.get(i));
      if(angle>1e-6){double radius=arc/angle;if(radius<16)throw new IllegalArgumentException("铁路弯道半径小于首版净空预设范围，请分段或改用独立结构");result=Math.max(result,Math.max(18*18,26*26-18*18)/(8*radius));}
    }return result;
  }
  private static V tangent(List<V> points,int i){return RailbedPath.tangent(points,i);}
  private static Hit intersection(IndexedPath indexed,V origin,V forward,boolean endpoint){
    var path=indexed.points();
    Hit match=null;
    V side=forward.left().mul(MAX_WIDTH);for(int segment:indexed.index().query(RoadPathIndex.segment(origin.sub(side),origin.add(side)),.001)){int i=segment+1;
      V a=path.get(i-1),b=path.get(i);double da=a.sub(origin).dot(forward),db=b.sub(origin).dot(forward);
      if(endpoint&&i==1&&Math.abs(da)<=.006)da=0;if(endpoint&&i==path.size()-1&&Math.abs(db)<=.006)db=0;
      if(da*db>1e-9||Math.abs(da-db)<1e-10)continue;double t=da/(da-db);if(t< -1e-7||t>1+1e-7)continue;t=Math.max(0,Math.min(1,t));V point=a.mul(1-t).add(b.mul(t));
      if(point.sub(origin).horizontalLength()>MAX_WIDTH)continue;
      var candidate=new Hit(point,b.sub(a).horizontalUnit(),i-1+t);
      if(match!=null&&match.point.distance(point)>1e-5)throw new IllegalArgumentException("邻轨与同一横断面多次相交，请分段选择");match=candidate;
    }
    if(match==null)throw new IllegalArgumentException("邻轨未覆盖整个 A–B 区间，请缩短区间");return match;
  }
  /** Include every neighboring polyline breakpoint, not only samples from the first rail. */
  private static Stations stations(List<IndexedPath> rails){
    var base=rails.get(0);var points=base.points();var frames=new ArrayList<V>();for(int i=0;i<points.size();i++)frames.add(tangent(points,i));
    var cuts=new TreeSet<Double>();for(int i=0;i<points.size();i++)cuts.add((double)i);
    for(int rail=1;rail<rails.size();rail++)for(var p:rails.get(rail).points()){
      RoadPlanningBudget.check();
      for(int segment:base.index().query(RoadPathIndex.segment(p,p),MAX_WIDTH)){
        var a=points.get(segment);var b=points.get(segment+1);var fa=frames.get(segment);var fb=frames.get(segment+1);
        double da=p.sub(a).dot(fa),db=p.sub(b).dot(fb);if(da*db>1e-9)continue;double lo=0,hi=1;
        for(int k=0;k<24;k++){double t=(lo+hi)/2;double d=p.sub(a.mul(1-t).add(b.mul(t))).dot(fa.mul(1-t).add(fb.mul(t)));if(d*da>0)lo=t;else hi=t;}
        double t=(lo+hi)/2;if(Math.abs(da)<1e-8)t=0;else if(Math.abs(db)<1e-8)t=1;var q=a.mul(1-t).add(b.mul(t));if(p.sub(q).horizontalLength()>MAX_WIDTH)continue;
        cuts.add(segment+t);if(cuts.size()>RoadLimits.MAX_SAMPLES)throw new IllegalArgumentException("多轨共同断面超过采样预算，请缩短区间");
      }
    }
    var merged=new ArrayList<V>();var tangents=new ArrayList<V>();double previous=-1;
    for(double at:cuts){if(at-previous<1e-6)continue;int i=Math.min(points.size()-2,(int)Math.floor(at));double t=at-i;merged.add(points.get(i).mul(1-t).add(points.get(i+1).mul(t)));tangents.add(frames.get(i).mul(1-t).add(frames.get(i+1).mul(t)).horizontalUnit());previous=at;}
    return new Stations(List.copyOf(merged),List.copyOf(tangents));
  }
  private RailbedGeometry(){}
}
