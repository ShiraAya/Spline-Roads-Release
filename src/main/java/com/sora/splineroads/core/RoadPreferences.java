package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.List;

/** A new road remembers authored dimensions, never an old port or terrain classification. */
public final class RoadPreferences {
  public static Settings fresh(Settings saved){
    // Width controls use half-block steps. Old preferences may already contain
    // a width reverse-computed from a snapped port (for example 25 / 6).
    double lane=RoadProfile.modern(saved.style())?Math.round(RoadProfile.layout(saved,saved.width()).laneWidth()*2)/2.0:0;
    var o=saved.options();var decor=o.streetscape();
    o=o.ends(RoadTransitions.Ends.NONE).lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY)
        .laneLines(List.of()).outsets(0,0).streetscape(decor.raisedSpans(List.of())
        .sideStructures(new RoadStreetscape.SideStructures(List.of(),List.of(),List.of(),List.of()))
        .tunnels(List.of()).crossings(List.of()));
    var result=saved.options(o).taper(saved.width(),saved.width());
    return RoadProfile.modern(saved.style())?RoadLanes.width(result,lane):result;
  }
  private RoadPreferences(){}
}
