package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
/** Geometric tolerances are metres: no endpoint-only or cached 'same road' decisions. */
public final class RoadContinuity {
  public static final double POSITION_EPS=.001;
  public static boolean eligible(Mesh m){
    var s=m.settings();var kind=RoadProfile.catalog(s.style()).type();if(kind!=RoadProfile.Type.ORDINARY&&kind!=RoadProfile.Type.HIGHWAY)return false;
    if(m.closed()||s.structure()==Structure.TUNNEL)return false;
    if(s.structure()==Structure.BRIDGE&&s.options().infrastructure().bridge()!=RoadInfrastructure.Bridge.STANDARD&&s.options().infrastructure().bridge()!=RoadInfrastructure.Bridge.BEAM&&s.options().infrastructure().bridge()!=RoadInfrastructure.Bridge.OVERPASS)return false;
    V a=m.first().center(),v=m.last().center().sub(a);if(v.horizontalLength()<2)return false;V dir=v.horizontalUnit();
    return m.samples().stream().allMatch(p->Math.abs(p.center().y()-a.y())<=POSITION_EPS&&Math.abs(p.center().sub(a).dot(dir.left()))<=POSITION_EPS);
  }
  public static boolean compatible(Mesh a,Mesh b,V shared){
    if(!eligible(a)||!eligible(b))return false;
    var ac=RoadProfile.catalog(a.settings());var bc=RoadProfile.catalog(b.settings());
    if(ac.lanes()!=bc.lanes()||ac.twoWay()!=bc.twoWay())return false;
    V ad=a.last().center().sub(a.first().center()).horizontalUnit(),bd=b.last().center().sub(b.first().center()).horizontalUnit();
    if(!RoadLanes.counts(a.settings()).equals(ad.dot(bd)<0?RoadLanes.counts(b.settings()).mirrored():RoadLanes.counts(b.settings())))return false;
    if(Math.abs(ad.dot(bd))<1-1e-8||Math.abs(a.first().center().y()-b.first().center().y())>POSITION_EPS)return false;
    if(!ac.twoWay()&&ad.dot(bd)<0)return false;
    boolean af=a.first().center().distance(shared)<POSITION_EPS,bf=b.first().center().distance(shared)<POSITION_EPS;
    if(!af&&a.last().center().distance(shared)>=POSITION_EPS||!bf&&b.last().center().distance(shared)>=POSITION_EPS)return false;
    return ad.mul(af?1:-1).dot(bd.mul(bf?1:-1))< -1+1e-8;
  }
  private RoadContinuity(){}
}
