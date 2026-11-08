package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadProfile.*;

/** Use the independent rail spacing in the shared tunnel entrance cross section. */
final class TunnelMedian {
  static RoadTransitions.Section seam(RoadTransitions.Section section){
    var layout=section.layout(false);var c=layout.catalog();
    if(!c.twoWay()||c.median()!=Median.GREEN)return section;
    layout=RaisedRoadProfile.apply(layout,1,section.streetscape().plantings().railWidth());
    var rail=RoadLanes.carrier(c.type(),RoadLanes.counts(section.settings(false)),Median.RAIL);
    var old=section.port();
    var port=new RoadTransitions.Port(layout.motorMin(),layout.motorMax(),layout.median(),layout.dividers(),
        old==null?layout.curbWidth():old.curbLeft(),old==null?layout.curbWidth():old.curbRight(),layout.medianCenter());
    return new RoadTransitions.Section(rail,section.width(),section.cycle(),section.cycleRail(),section.curb(),
        section.outerRail(),section.sidewalk(),section.cycleAsphalt(),port,section.streetscape(),section.lanes());
  }
  private TunnelMedian(){}
}
