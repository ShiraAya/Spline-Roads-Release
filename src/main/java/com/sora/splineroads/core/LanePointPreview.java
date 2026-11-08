package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Actual road surface and lane hit regions, viewed from above at the point's station. */
public final class LanePointPreview {
  public record Marker(int lane,int x,int y,int dx,int dy) {}
  public record Image(int width,int height,int[] pixels,int[] lanes,List<Marker> markers) {
    public int laneAt(int x,int y){return x<0||y<0||x>=width||y>=height?-1:lanes[y*width+x];}
  }
  public static Image render(Mesh mesh,LanePoints.Point point,int width,int height,int selected) {
    double station=LanePoints.lane(mesh,point).station();var raw=LaneSections.reference(mesh);var sample=RoadStructures.sample(raw,station);
    V origin=sample.center(),forward=sample.left().left().mul(-1),right=forward.left();
    var layout=RoadProfile.layout(raw,sample);
    double actualHalfWidth=sample.halfWidth();
    for(int slot:LaneAdditions.slots(mesh,station)){
      var lane=LanePoints.lane(mesh,station,slot);
      actualHalfWidth=Math.max(actualHalfWidth,Math.abs(lane.position().sub(origin).dot(sample.left()))+lane.width()/2);
    }
    var effective=RoadStructures.sample(mesh,station);
    actualHalfWidth=Math.max(actualHalfWidth,Math.abs(effective.center().sub(origin).dot(sample.left()))+effective.halfWidth());
    double halfWidth=actualHalfWidth+(mesh.settings().options().sidewalk().enabled()?mesh.settings().options().sidewalk().width():0)+3;
    double halfLength=Math.max(5,halfWidth*(height-20)/Math.max(1,width-20));
    var painter=new JunctionPreview.Painter(new V(-halfWidth,0,-halfLength),new V(halfWidth,0,halfLength),width,height);
    var hit=new JunctionPreview.Painter(new V(-halfWidth,0,-halfLength),new V(halfWidth,0,halfLength),width,height);
    Arrays.fill(hit.pixels,-1);Arrays.fill(painter.pixels,0xff28382e);
    java.util.function.Function<V,V> project=v->{var d=v.sub(origin);return new V(d.dot(right),v.y(),-d.dot(forward));};
    double window=Math.hypot(halfWidth,halfLength)+6,lo=Math.max(0,station-window),hi=Math.min(mesh.length(),station+window);
    var samples=new ArrayList<Sample>();samples.add(RoadStructures.sample(mesh,lo));
    for(var s:mesh.samples())if(s.distance()>lo&&s.distance()<hi)samples.add(s);
    samples.add(RoadStructures.sample(mesh,hi));
    // Keep original stations and total length: actual taper widths and painted intervals stay exact.
    var visible=new Mesh(samples,mesh.settings(),mesh.min(),mesh.max(),mesh.length(),false,mesh.controlPoint(),mesh.controls(),mesh.reference());
    for(var part:RoadSidewalks.parts(visible,mesh.settings().options().sidewalk()))
      painter.polygon(part.base().stream().map(project).toList(),part.material()==RoadStructures.Material.TACTILE?0xffdbc449:0xff929a99);
    var surface=RoadSurface.build(visible,List.of(),List.of());
    for(var face:surface.pavement())painter.polygon(face.points().stream().map(project).toList(),0xff000000|face.color());
    for(int lane:LaneAdditions.slots(mesh,station))for(int i=1;i<samples.size();i++) {
      var a=LanePoints.lane(mesh,samples.get(i-1).distance(),lane);var b=LanePoints.lane(mesh,samples.get(i).distance(),lane);
      double fa=LaneAdditions.isAdded(mesh,lane)?LaneAdditions.find(mesh,lane).fraction(a.station()):1;
      double fb=LaneAdditions.isAdded(mesh,lane)?LaneAdditions.find(mesh,lane).fraction(b.station()):1;
      if(Math.max(fa,fb)<1e-6)continue;
      V al=samples.get(i-1).left().mul(a.width()*fa/2),bl=samples.get(i).left().mul(b.width()*fb/2);
      var quad=List.of(a.position().sub(al),a.position().add(al),b.position().add(bl),b.position().sub(bl)).stream().map(project).toList();
      // Removed slots remain grey/selectable as reserved replacement targets,
      // including established added slots. An unfinished addition is not a port.
      if(!LaneAdditions.isAdded(mesh,lane)||LaneAdditions.find(mesh,lane).fraction(station)>.999999&&Math.min(fa,fb)>.999999)hit.polygon(quad,lane);
      if(lane==selected)painter.polygon(quad,0xff36566b);else if(!LaneSections.active(mesh,(samples.get(i-1).distance()+samples.get(i).distance())/2,lane))painter.polygon(quad,0xff454841);
    }
    for(var face:surface.markings())painter.marking(face.points().stream().map(project).toList(),0xff000000|face.color());
    // Direction arrows belong to the selected station, including opposite carriageways.
    var markers=new ArrayList<Marker>();
    for(int lane:LaneAdditions.slots(mesh,station)) {
      if(LaneAdditions.isAdded(mesh,lane)&&LaneAdditions.find(mesh,lane).fraction(station)<.999999)continue;
      var lanePoint=LanePoints.lane(mesh,station,lane);V center=project.apply(lanePoint.position()),direction=lanePoint.direction();
      V arrow=lanePoint.position().add(direction.mul(3)),tip=arrow.add(direction.mul(1.4));
      painter.line(project.apply(arrow.sub(direction)),project.apply(tip),0xfff4f1df);
      painter.line(project.apply(tip),project.apply(tip.sub(direction).add(direction.left().mul(.6))),0xfff4f1df);
      painter.line(project.apply(tip),project.apply(tip.sub(direction).sub(direction.left().mul(.6))),0xfff4f1df);
      V end=project.apply(lanePoint.position().add(direction));
      markers.add(new Marker(lane,painter.x(center),painter.y(center),painter.x(end)-painter.x(center),painter.y(end)-painter.y(center)));
    }
    return new Image(width,height,painter.pixels,hit.pixels,List.copyOf(markers));
  }
  private LanePointPreview(){}
}
