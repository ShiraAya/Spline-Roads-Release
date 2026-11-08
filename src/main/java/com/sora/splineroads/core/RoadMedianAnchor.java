package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Authored ordinary two-way nodes are the median axis, not the pavement bounding-box centre.
 * The physical ribbon stays symmetric about each Sample; layout offsets stay unchanged.
 * Apply once to an authored path, before lane cuts and never to an already physical alignment. */
public final class RoadMedianAnchor {
  public static V position(Mesh m,boolean first){var s=first?m.first():m.last();var l=RoadProfile.layout(m,s);return l.catalog().twoWay()?s.at(l.medianCenter(),0):s.center();}
  public static Mesh apply(Mesh raw){
    var c=RoadProfile.catalog(raw.settings());
    if(!c.twoWay()||(c.type()!=RoadProfile.Type.ORDINARY&&c.type()!=RoadProfile.Type.HIGHWAY))return raw;
    var samples=new ArrayList<Sample>();boolean moved=false;
    for(var s:raw.samples()){
      double offset=RoadProfile.layout(raw,s).medianCenter();moved|=Math.abs(offset)>1e-9;
      samples.add(new Sample(s.at(-offset,0),s.left(),s.distance(),s.halfWidth()));
    }
    if(!moved)return raw;
    var bounds=RoadRibbon.mesh(samples,raw.settings());
    return new Mesh(List.copyOf(samples),raw.settings(),bounds.min(),bounds.max(),raw.length(),raw.closed(),raw.controlPoint(),raw.controls(),null);
  }
  private RoadMedianAnchor(){}
}
