package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Solver coordinates are motor-lane axes. Convert them to pavement centers and fit both mouths. */
public final class LaneRampAlignment {
  /** .75 m on either side seats both ordinary and highway rail assemblies.
   * The persisted motor port distinguishes refreshed connectors from old saves. */
  private static double motorWidth(Settings settings){var p=RoadProfile.layout(settings,settings.width());return p.motorMax()-p.motorMin();}
  public static Settings usableWidth(Settings settings,double drive){
    drive=Math.max(4,drive);var o=settings.options();
    var port=new RoadTransitions.Port(-drive/2,drive/2,0,List.of(),0,0);
    return new Settings(settings.mode(),settings.style(),drive+1.5,settings.thickness(),settings.tension(),settings.arcDegrees(),
        o.ends().start()==null?drive+1.5:settings.startWidth(),o.ends().end()==null?drive+1.5:settings.endWidth(),
        settings.structure(),settings.taperVersion(),settings.rampTurn(),o.ends(o.ends().port(port)));
  }
  public static Mesh fit(Mesh path,double startLane,double endLane,double transition){
    var profile=RoadProfile.layout(path.settings(),path.settings().width());
    double lane=profile.motorMax()-profile.motorMin(),margin=path.settings().width()-lane;
    double span=Math.max(.01,Math.min(Math.max(16,transition),path.length()/2));
    var out=new ArrayList<Sample>();
    for(var s:path.samples()){
      double a=1-Settings.smooth(Math.min(1,s.distance()/span)),b=1-Settings.smooth(Math.min(1,(path.length()-s.distance())/span));
      double width=lane+(startLane-lane)*a+(endLane-lane)*b+margin;
      V center=s.center().sub(s.left().mul(profile.motorCenter()));
      out.add(new Sample(center,s.left(),s.distance(),width/2));
    }
    return RoadRibbon.mesh(out,path.settings());
  }
  /** A port retains its motor axis while the pavement also meets an outside shoulder. */
  public record Mouth(double lane,double low,double high,boolean auxiliary){
    public Mouth(double lane,double low,double high){this(lane,low,high,false);}
    public static Mouth extra(double lane){return new Mouth(lane,0,0,true);}
    public static Mouth extra(double lane,int outside){return new Mouth(lane,outside<0?.75:0,outside>0?.75:0,true);}
    boolean internal(){return !auxiliary&&low<1e-6&&high<1e-6;}
  }
  public static Mouth mouth(Mesh host,LanePoints.Lane lane){
    var at=RoadStructures.sample(host,lane.station());var layout=RoadProfile.layout(host,at);
    double center=lane.position().sub(at.center()).dot(at.left());
    double low=Math.abs(center-lane.width()/2-layout.motorMin())<.01?Math.max(0,at.halfWidth()+layout.motorMin()):0;
    double high=Math.abs(center+lane.width()/2-layout.motorMax())<.01?Math.max(0,at.halfWidth()-layout.motorMax()):0;
    // A connector owns a .75 m rail shoulder at an exposed motor edge.
    // Reusing 1.5 m of a cycle verge (or a highway's wider shoulder) made its
    // initial white line sit much farther inward than its rail, then bow back
    // as that inherited verge shrank. Interior slots still have no spare width.
    low=low>1e-6?.75:0;high=high>1e-6?.75:0;
    // Convert the host construction frame into this lane's driving frame.
    return lane.direction().dot(at.left().left().mul(-1))>0?new Mouth(lane.width(),low,high):new Mouth(lane.width(),high,low);
  }
  private static RoadTransitions.Section section(Settings settings,Mouth mouth){
    double shift=(mouth.high()-mouth.low())/2,width=mouth.lane()+mouth.low()+mouth.high();
    return RoadTransitions.Section.of(new Settings(settings.mode(),settings.style(),width,settings.thickness(),settings.tension(),settings.arcDegrees()).options(settings.options())).port(new RoadTransitions.Port(-mouth.lane()/2-shift,mouth.lane()/2-shift,0,List.of(),0,0));
  }
  public static Mesh fit(Mesh path,Mouth from,Mouth to){
    return fit(path,from,to,List.of(),List.of());
  }
  public static Mesh fit(Mesh path,Mouth from,Mouth to,List<Mesh> sourceGuides,List<Mesh> targetGuides){
    var clean=path.settings().options(path.settings().options().lanePoints(path.settings().options().lanePoints().bands(List.of())).ends(RoadTransitions.Ends.NONE.port(path.settings().options().ends().port())));
    var a=section(clean,from);var b=section(clean,to);
    var settings=RoadTransitions.ends(clean,a,b);Mesh fitted=path;
    double[] sourceFree=outsideProgress(path,from,true,sourceGuides),targetFree=outsideProgress(path,to,false,targetGuides);
    double shoulder=Math.max(0,(settings.width()-motorWidth(settings))/2);
    var link=path.settings().options().lanePoints().link();
    double transition=link==null?32:link.options().transition();
    double auxiliary=Math.max(64,transition*2)+LaneRampApproach.parallel(transition)+Math.max(8,settings.width());
    // Widening one shoulder changes physical arc length. Evaluate profile weights
    // on that final distance too, so serialization/reload cannot shift the motor axis.
    for(int pass=0;pass<12;pass++){
      double span=RoadTransitions.span(settings,fitted.length());var out=new ArrayList<Sample>();
      for(int i=0;i<path.samples().size();i++){
        var p=path.samples().get(i);double distance=fitted.samples().get(i).distance();
        double wa=1-Settings.smooth(Math.min(1,distance/span)),wb=1-Settings.smooth(Math.min(1,(fitted.length()-distance)/span));
        double shift=((from.high()-from.low())*wa+(to.high()-to.low())*wb)/2;
        // Grow a new rail shoulder OUTWARD while the axis still follows an outer
        // host slot. Symmetric widening of a straight lead invaded its unchanged
        // inward neighbor immediately, making every fixed-start route impossible.
        double bias=Math.signum(from.high()-from.low())*(1-sourceFree[i])+Math.signum(to.high()-to.low())*(1-targetFree[i]);
        shift+=shoulder*(1-wa)*(1-wb)*Math.max(-1,Math.min(1,bias));
        double width=settings.width()+(a.width()-settings.width())*wa+(b.width()-settings.width())*wb;
        // An interior fixed mouth has no spare shoulder in either adjacent lane.
        // Keep its authored slot width through the straight lead; widening starts
        // with the separated turn, whose whole ribbon still undergoes clearance.
        if(from.internal())width=Math.min(width,from.lane()+2*shoulder*sourceFree[i]);
        if(to.internal())width=Math.min(width,to.lane()+2*shoulder*targetFree[i]);
        // Different host lane widths also change the motor ribbon, not only its
        // rail shoulders. While following an outer slot keep the inward edge on
        // that slot boundary; otherwise a wider source lane leaks into the next
        // target lane all the way to the fixed mouth on a curved tail.
        if(!from.auxiliary()&&!from.internal()){
          double edge=Math.signum(from.high()-from.low())*(width-from.lane())/2;
          shift=edge*(1-sourceFree[i])+shift*sourceFree[i];
        }
        if(!to.auxiliary()&&!to.internal()){
          double edge=Math.signum(to.high()-to.low())*(width-to.lane())/2;
          shift=edge*(1-targetFree[i])+shift*targetFree[i];
        }
        // EXTRA already has an exact host-following pavement taper. A second,
        // shorter width interpolation must not pull that shoulder away from it.
        if(from.auxiliary()&&distance<=auxiliary||to.auxiliary()&&fitted.length()-distance<=auxiliary){
          width=p.halfWidth()*2;
          var mouth=from.auxiliary()&&distance<=auxiliary&&(!to.auxiliary()||distance<=fitted.length()/2)?from:to;
          if(mouth.auxiliary())shift=auxiliaryShift(width,mouth,settings.width());
        }
        out.add(new Sample(p.center().add(p.left().mul(shift)),p.left(),p.distance(),width/2));
      }
      // Clearance can release a shoulder within only a metre or two of vertical
      // separation. Spread that widening along the free route, always INSIDE
      // the already checked ribbon; never grow into a neighbouring live lane.
      smoothWidths(out);
      var next=RoadRibbon.mesh(out,settings);double movement=0;
      for(int i=0;i<out.size();i++)movement=Math.max(movement,next.samples().get(i).center().distance(fitted.samples().get(i).center()));
      fitted=next;if(movement<1e-9)break;
    }
    return painted(fitted,from,to);
  }
  private static void smoothWidths(List<Sample> samples){
    for(int direction:new int[]{1,-1})for(int i=direction>0?1:samples.size()-2;i>=0&&i<samples.size();i+=direction){
      var p=samples.get(i-direction);var q=samples.get(i);
      double run=q.center().sub(p.center()).horizontalLength();
      double half=Math.min(q.halfWidth(),p.halfWidth()+.025*run);
      if(half<q.halfWidth())samples.set(i,new Sample(q.center(),q.left(),q.distance(),half));
    }
  }
  public static double auxiliaryShift(double width,Mouth mouth,double full){
    double narrow=mouth.lane()+mouth.low()+mouth.high();
    double grow=full>narrow+1e-8?Math.max(0,Math.min(1,(width-narrow)/(full-narrow))):1;
    return (mouth.high()-mouth.low())*(1-grow)/2;
  }
  /** Paint follows the physical shoulders after leaving the fixed motor mouth.
   * The route solver's old interior axis is not a second, independent road edge. */
  private static LanePoints.Band band(Mesh mesh,Sample sample,Mouth from,Mouth to){
    boolean first=sample.distance()<=mesh.length()/2;
    var mouth=first?from:to;
    var link=mesh.settings().options().lanePoints().link();double transition=link==null?32:link.options().transition();
    double auxiliary=Math.max(64,transition*2)+LaneRampApproach.parallel(transition)+Math.max(8,mesh.settings().width());
    if(from.auxiliary()&&sample.distance()<=auxiliary&&(!to.auxiliary()||first)){mouth=from;first=true;}
    else if(to.auxiliary()&&mesh.length()-sample.distance()<=auxiliary){mouth=to;first=false;}
    double distance=first?sample.distance():mesh.length()-sample.distance();
    double full=mesh.settings().width(),narrow=mouth.lane()+mouth.low()+mouth.high();
    double shoulder=Math.max(0,(full-motorWidth(mesh.settings()))/2);
    double grow=full-narrow>1e-6?Math.max(0,Math.min(1,(sample.halfWidth()*2-narrow)/(full-narrow))):Settings.smooth(Math.min(1,distance/RoadTransitions.span(mesh.settings(),mesh.length())));
    double low=mouth.low()+(shoulder-mouth.low())*grow,high=mouth.high()+(shoulder-mouth.high())*grow;
    return new LanePoints.Band(sample.distance(),-sample.halfWidth()+low,sample.halfWidth()-high);
  }
  private static Mesh painted(Mesh mesh,Mouth from,Mouth to){
    var bands=mesh.samples().stream().map(s->band(mesh,s,from,to)).toList();
    var settings=mesh.settings().options(mesh.settings().options().lanePoints(mesh.settings().options().lanePoints().bands(bands)));
    return new Mesh(mesh.samples(),settings,mesh.min(),mesh.max(),mesh.length(),mesh.closed(),mesh.controlPoint(),mesh.controls(),mesh.reference());
  }
  private static double[] outsideProgress(Mesh path,Mouth mouth,boolean first){return outsideProgress(path,mouth,first,List.of());}
  private static double[] outsideProgress(Mesh path,Mouth mouth,boolean first,List<Mesh> guides){
    int n=path.samples().size();var end=first?path.first():path.last();double side=Math.signum(mouth.high()-mouth.low()),progress=0;
    var result=new double[n];
    for(int i=first?0:n-1;i>=0&&i<n;i+=first?1:-1){
      var delta=path.samples().get(i).center().sub(end.center());
      double lateral=delta.dot(end.left());
      if(!guides.isEmpty()){
        var p=path.samples().get(i).center();var near=guides.stream().map(g->RoadQueries.horizontal(g,p)).min(Comparator.comparingDouble(RoadQueries.Projection::horizontalDistance)).orElseThrow();
        lateral=near.horizontalDistance();delta=new V(delta.x(),p.y()-near.sample().center().y(),delta.z());
      }
      progress=Math.max(progress,side==0?Math.abs(lateral)-.5:Math.abs(lateral));
      double lateralFree=Settings.smooth(Math.max(0,Math.min(1,progress/Math.max(1,path.settings().width()))));
      // Vertical clearance releases the whole shoulder once the decks are apart.
      // Subtracting 5.5 and then dividing by pavement width left an 8 m overpass
      // permanently narrowed to ~4.6 m INCLUDING both guardrail footings.
      double verticalFree=Settings.smooth(Math.max(0,Math.min(1,(Math.abs(delta.y())-4.25)/1.25)));
      var link=path.settings().options().lanePoints().link();
      if(!first&&!guides.isEmpty()&&link!=null&&link.closesTarget()){
        // An underpassing MERGE must be able to rise into its selected slot.
        // Expanding inward merely because the initial profile is far below B
        // makes the neighboring lane a ceiling, trapping the solver on a shelf.
        double reserve=Math.max(96,link.options().transition()*4);
        double remaining=path.length()-path.samples().get(i).distance();
        verticalFree*=Settings.smooth(Math.max(0,Math.min(1,(remaining-reserve)/32)));
      }
      result[i]=mouth.auxiliary()?1:Math.max(lateralFree,verticalFree);
    }return result;
  }
  /** Old saved paths can have correct lane axes and still miss an outside shoulder. */
  public static boolean matches(Mesh mesh,Mouth from,Mouth to){
    if(mesh.settings().style().connectorRamp()&&mesh.settings().options().lanePoints().bands().isEmpty())return false;
    for(boolean first:new boolean[]{true,false}){
      var s=first?mesh.first():mesh.last();var mouth=first?from:to;
      var layout=RoadProfile.layout(mesh,s);
      V center=motorAxis(mesh,s).add(s.left().mul((mouth.high()-mouth.low())/2));
      if(Math.abs(layout.motorMax()-layout.motorMin()-mouth.lane())>1e-6||center.distance(s.center())>1e-6
          ||Math.abs(s.halfWidth()*2-mouth.lane()-mouth.low()-mouth.high())>1e-6)return false;
    }
    for(var sample:mesh.samples()){
      var expected=band(mesh,sample,from,to);var actual=RoadProfile.layout(mesh,sample);
      if(Math.abs(actual.motorMin()-expected.low())>.003||Math.abs(actual.motorMax()-expected.high())>.003)return false;
    }
    return true;
  }
  public static Mesh refit(Mesh mesh,Mouth from,Mouth to){
    // Existing correctly fitted pavement must not drift merely to refresh paint.
    if(!mesh.settings().options().lanePoints().bands().isEmpty()
        &&Math.abs(mesh.first().halfWidth()*2-from.lane()-from.low()-from.high())<1e-6
        &&Math.abs(mesh.last().halfWidth()*2-to.lane()-to.low()-to.high())<1e-6)return painted(mesh,from,to);
    var axis=new ArrayList<Sample>();
    for(var s:mesh.samples())axis.add(new Sample(motorAxis(mesh,s),s.left(),s.distance(),s.halfWidth()));
    if(mesh.settings().options().lanePoints().bands().isEmpty()){
      // Older fits stored the shifted pavement, but only the endpoint part of
      // that shift in their motor profile. Invert the same outward-shoulder term
      // before re-fitting, so an explicit rebuild repairs paint without drifting
      // the previously authored route sideways on every subsequent save.
      double shoulder=Math.max(0,(mesh.settings().width()-motorWidth(mesh.settings()))/2);
      double span=RoadTransitions.span(mesh.settings(),mesh.length());
      for(int pass=0;pass<12;pass++){
        var path=RoadRibbon.mesh(axis,mesh.settings());var source=outsideProgress(path,from,true);var target=outsideProgress(path,to,false);var next=new ArrayList<Sample>();
        for(int i=0;i<mesh.samples().size();i++){
          var s=mesh.samples().get(i);double wa=1-Settings.smooth(Math.min(1,s.distance()/span)),wb=1-Settings.smooth(Math.min(1,(mesh.length()-s.distance())/span));
          double bias=Math.signum(from.high()-from.low())*(1-source[i])+Math.signum(to.high()-to.low())*(1-target[i]);
          V center=motorAxis(mesh,s).sub(s.left().mul(shoulder*(1-wa)*(1-wb)*Math.max(-1,Math.min(1,bias))));
          next.add(new Sample(center,s.left(),s.distance(),s.halfWidth()));
        }axis=next;
      }
    }
    return fit(RoadRibbon.mesh(axis,mesh.settings()),from,to);
  }
  private static V motorAxis(Mesh mesh,Sample sample){return sample.at(RoadProfile.layout(mesh,sample).motorCenter(),0);}
  public static V axis(Mesh mesh,boolean first){return motorAxis(mesh,first?mesh.first():mesh.last());}
  private LaneRampAlignment(){}
}
