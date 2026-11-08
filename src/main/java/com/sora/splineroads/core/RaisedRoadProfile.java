package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadProfile.Catalog;
import com.sora.splineroads.core.RoadProfile.Layout;
import com.sora.splineroads.core.RoadProfile.Median;
import java.util.ArrayList;
import java.util.List;

/** Adapts a raised two-way profile without discarding the transition's stripe identities. */
final class RaisedRoadProfile {
  static Layout apply(Layout base) {
    return apply(base,1);
  }
  static Layout apply(Layout base,double weight) {return apply(base,weight,1);}
  static Layout apply(Layout base,double weight,double railWidth) {
    if(weight<=0)return base;
    final double median = base.median()+(railWidth-base.median())*weight;
    var c = base.catalog();
    if (!c.twoWay() || c.median() == Median.RAIL && Math.abs(base.median() - median) < 1e-9)
      return base;

    double width = base.motorMax() - base.motorMin();
    double laneScale = (width - median) / Math.max(1e-9, width - base.median());
    // Resize both carriageways proportionally; an asymmetric median must move with
    // their actual lane counts, otherwise equal-width lane axes land on paint.
    double center=base.motorCenter()+(base.medianCenter()-base.motorCenter())*laneScale;
    var dividers = new ArrayList<Double>();
    for (double divider : base.dividers()) {
      int side = divider < base.medianCenter() ? -1 : 1;
      double outer = side * (base.outer(side) - base.medianCenter());
      double oldSpan = outer - base.median() / 2;
      double fraction = (Math.abs(divider - base.medianCenter()) - base.median() / 2) / Math.max(1e-9, oldSpan);
      // A disappearing divider converges on ITS outside edge. List order and size are
      // stable across the 50% catalog switch; negative and positive carriageways never pair.
      dividers.add(center + side * (median / 2 + Math.max(0, Math.min(1, fraction)) * ((outer - base.median() / 2)*laneScale)));
    }
    return new Layout(new Catalog(c.type(), c.lanes(), true, weight>=1?Median.RAIL:c.median(), c.shoulder()),
        base.laneWidth() * laneScale, median, base.motorMin(), base.motorMax(),
        base.cycleWidth(), base.curbWidth(), base.shoulderWidth(), base.outside(), List.copyOf(dividers), center);
  }

  private RaisedRoadProfile() {}
}
