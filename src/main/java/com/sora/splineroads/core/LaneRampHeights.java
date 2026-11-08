package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Grade-separated crossing inside the free span; host contact lanes retain their elevation. */
public final class LaneRampHeights {
  public static Mesh adjust(Mesh base,double from,double to,double lift){return adjust(base,from,to,lift,LaneRampGrade.LEGACY);}
  public static Mesh adjust(Mesh base,double from,double to,double lift,double maxGrade){
    LaneRampGrade.checked(maxGrade);
    if(to-from<8)throw new IllegalArgumentException("主路之间没有足够的升降过渡空间");
    var samples=new ArrayList<Sample>();
    for(var s:base.samples()){
      double t=Math.max(0,Math.min(1,(s.distance()-from)/(to-from)));
      double dy=lift*Math.pow(Math.sin(Math.PI*t),2);
      var p=s.center().add(new V(0,dy,0));
      if(!samples.isEmpty()){V d=p.sub(samples.get(samples.size()-1).center());if(LaneRampGrade.exceeds(d.y(),d.horizontalLength(),maxGrade))throw new IllegalArgumentException("上跨／下穿过渡的坡度超过 "+LaneRampGrade.label(maxGrade));}
      samples.add(new Sample(p,s.left(),s.distance(),s.halfWidth()));
    }
    Mesh result=RoadRibbon.mesh(samples,base.settings());RoadRibbon.checkSelfIntersections(result,4);LaneRampPaths.checkVolume(result);return result;
  }
  public record Constraint(double from,double to,double amount) {
    public Constraint {if(!RoadGeometry.finite(from,to,amount)||from>to||amount<0)throw new IllegalArgumentException("跨越高程约束无效");}
  }
  private record Plateau(double from,double to,double height){}
  private record Profile(Plateau plateau,double rise,double fall){}
  /** Fit each obstacle against the local signed base grade. A steep fixed throat must not
   * consume the grade budget hundreds of blocks away at an unrelated crossing. */
  public static Mesh solve(Mesh base,double freeFrom,double freeTo,List<Constraint> constraints,boolean over){return solve(base,freeFrom,freeTo,constraints,over,LaneRampGrade.LEGACY);}
  public static Mesh solve(Mesh base,double freeFrom,double freeTo,List<Constraint> constraints,boolean over,double maxGrade){
    LaneRampGrade.checked(maxGrade);
    if(constraints.isEmpty())return base;
    if(base.settings().style().connectorRamp())return LaneRampCorridor.solve(base,freeFrom,freeTo,constraints,over,maxGrade);
    int n=base.samples().size();double[] horizontal=new double[n];
    for(int i=1;i<n;i++)horizontal[i]=horizontal[i-1]+base.samples().get(i).center().sub(base.samples().get(i-1).center()).horizontalLength();
    double lo=horizontalAt(base,horizontal,freeFrom),hi=horizontalAt(base,horizontal,freeTo);
    var plateaus=new ArrayList<Plateau>();
    for(var c:constraints)if(c.amount()>.001){
      if(c.amount()>48)throw new IllegalArgumentException("自动跨越需要升降超过 48 格，请扩大道路间距或修改端点高度");
      plateaus.add(new Plateau(horizontalAt(base,horizontal,c.from())-1,horizontalAt(base,horizontal,c.to())+1,c.amount()));
    }
    if(plateaus.isEmpty())return base;
    plateaus.sort(Comparator.comparingDouble(Plateau::from));
    // First combine overlapping obstacle polygons; contacts tessellate the same physical deck.
    for(int i=1;i<plateaus.size();){var a=plateaus.get(i-1);var b=plateaus.get(i);
      if(b.from()<=a.to()+.01){plateaus.set(i-1,merge(a,b));plateaus.remove(i);}else i++;}
    var profiles=new ArrayList<Profile>();
    boolean merged;
    do{
      profiles.clear();merged=false;
      for(int i=0;i<plateaus.size();i++){
        var p=plateaus.get(i);double rise=span(base,horizontal,p,true,over,p.from()-lo,maxGrade),fall=span(base,horizontal,p,false,over,hi-p.to(),maxGrade);
        var profile=new Profile(p,rise,fall);
        if(!profiles.isEmpty()){
          var before=profiles.get(profiles.size()-1);
          if(p.from()-rise<=before.plateau().to()+before.fall()){
            plateaus.set(i-1,merge(before.plateau(),p));plateaus.remove(i);merged=true;break;
          }
        }
        profiles.add(profile);
      }
    }while(merged);
    var samples=new ArrayList<Sample>();
    for(int i=0;i<n;i++){
      var s=base.samples().get(i);double delta=0,x=horizontal[i];
      for(var profile:profiles){var p=profile.plateau();double w=x<p.from()?smooth((x-p.from()+profile.rise())/profile.rise()):x>p.to()?smooth((p.to()+profile.fall()-x)/profile.fall()):1;delta=Math.max(delta,p.height()*w);}
      V position=s.center().add(new V(0,over?delta:-delta,0));
      if(!samples.isEmpty()){V d=position.sub(samples.get(samples.size()-1).center());if(LaneRampGrade.exceeds(d.y(),d.horizontalLength(),maxGrade))throw new IllegalArgumentException("障碍高程拟合后的坡度超过 "+LaneRampGrade.label(maxGrade)+"，需要更长的接头或升降空间");}
      samples.add(new Sample(position,s.left(),s.distance(),s.halfWidth()));
    }
    Mesh result=RoadRibbon.mesh(samples,base.settings());RoadRibbon.checkSelfIntersections(result,4);LaneRampPaths.checkVolume(result);return result;
  }
  private static Plateau merge(Plateau a,Plateau b){return new Plateau(Math.min(a.from(),b.from()),Math.max(a.to(),b.to()),Math.max(a.height(),b.height()));}
  private static double span(Mesh base,double[] x,Plateau p,boolean rise,boolean over,double available,double maxGrade){
    double trial=Math.max(2,1.90*p.height()/(2*maxGrade)),minimum=trial;
    while(trial<=available+.001){
      boolean good=true;double edge=rise?p.from():p.to();
      for(int i=1;i<x.length;i++){
        if(rise?(x[i]<edge-trial||x[i-1]>edge):(x[i]<edge||x[i-1]>edge+trial))continue;
        double wa=rise?smooth((x[i-1]-edge+trial)/trial):smooth((edge+trial-x[i-1])/trial);
        double wb=rise?smooth((x[i]-edge+trial)/trial):smooth((edge+trial-x[i])/trial);
        double delta=base.samples().get(i).center().y()-base.samples().get(i-1).center().y()+(over?1:-1)*p.height()*(wb-wa);
        if(LaneRampGrade.exceeds(delta,x[i]-x[i-1],maxGrade)){good=false;break;}
      }
      if(good)return trial;
      if(trial>=available-.001)break;
      trial=Math.min(available,trial*1.22+1);
    }
    throw new IllegalArgumentException(String.format(Locale.ROOT,"%s障碍区%s仅余 %.1f 格，无法为 %.2f 格高程调整安排不超过 %s 的平滑坡道（候选起始跨度 %.1f 格）",over?"上跨":"下穿",rise?"之前":"之后",Math.max(0,available),p.height(),LaneRampGrade.label(maxGrade),minimum));
  }
  private static double smooth(double t){return Settings.smooth(Math.max(0,Math.min(1,t)));}
  private static double horizontalAt(Mesh mesh,double[] horizontal,double station){
    var p=mesh.samples();if(station<=0)return 0;if(station>=mesh.length())return horizontal[horizontal.length-1];
    int lo=0,hi=p.size()-1;while(hi-lo>1){int mid=(lo+hi)>>>1;if(p.get(mid).distance()<station)lo=mid;else hi=mid;}
    double t=(station-p.get(lo).distance())/(p.get(hi).distance()-p.get(lo).distance());return horizontal[lo]+t*(horizontal[hi]-horizontal[lo]);
  }
  private LaneRampHeights(){}
}
