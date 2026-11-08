package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** A gantry keeps its authored station and headroom while its two exterior feet
 * may fit around a roadside boundary. This class never grants collision immunity:
 * it identifies contacts worth trying a different foot position for. */
public final class RoadGantryMounts {
  private static final double VERGE=.75;
  private static final WeakIdentityValueCache<Mesh,Mesh> INTERIOR=new WeakIdentityValueCache<>(256,500_000,m->m.samples().size());
  private static final WeakIdentityValueCache<Mesh,Mesh> MOTOR=new WeakIdentityValueCache<>(256,500_000,m->m.samples().size());
  private static final WeakIdentityValueCache<Mesh,Mesh> WALL=new WeakIdentityValueCache<>(256,500_000,m->m.samples().size());
  private static final WeakIdentityCache<Mesh,RoadSolidOverlap.Index> FOOTINGS=new WeakIdentityCache<>(128,128,i->1);
  private static final Ground EDGE_GROUND=new Ground(){
    public double top(double x,double z,double y){return Double.NaN;}
    public boolean joined(V point){return false;}
    public boolean blocked(Part part){return false;}
  };

  /** Only the column and its compact pedestal can move. A beam or an immutable
   * bridge support crossing another road remains an ordinary hard obstruction. */
  public static boolean foot(Part p){
    return RoadGantry.member(p)&&p.pier()&&p.a().distance(p.b())<1e-8
        &&(p.material()==Material.STEEL&&Math.abs(p.width()-.65)<1e-8
            ||p.material()==Material.CONCRETE&&p.width()>=.95-1e-8&&p.width()<=1.2+1e-8&&p.height()<=.45+1e-8
            ||p.material()==Material.DARK_STEEL&&Math.abs(p.width()-.85)<1e-8&&p.height()<=.12+1e-8);
  }

  /** Noise-wall footings extend 0.01 m beyond the deck after its 0.34 m inset.
   * Reserve that actual outside extent even before another road's rail pass.
   * Ordinary guardrails are wholly inside their deck and require no enlargement. */
  public static Mesh clearanceMesh(Mesh mesh){
    var rail=mesh.settings().options().outerRail();
    if(!rail.sound(-1)&&!rail.sound(1))return mesh;
    return WALL.get(mesh,m->{
      var samples=new ArrayList<Sample>();
      for(var s:m.samples()){
        double lo=RoadStreetscape.raised(m,s,-1)&&rail.sound(-1)?Math.max(0,.35-RoadRailJoin.inset(m,s,-1)):0;
        double hi=RoadStreetscape.raised(m,s,1)&&rail.sound(1)?Math.max(0,.35-RoadRailJoin.inset(m,s,1)):0;
        samples.add(new Sample(s.at((hi-lo)/2,0),s.left(),s.distance(),s.halfWidth()+(lo+hi)/2));
      }
      return ribbon(m,samples);
    });
  }

  private static Mesh ribbon(Mesh mesh,List<Sample> samples){
    return new Mesh(List.copyOf(samples),mesh.settings(),mesh.min(),mesh.max(),mesh.length(),mesh.closed(),
        mesh.controlPoint(),mesh.controls(),LaneSections.reference(mesh));
  }

  private static boolean footingContact(Part part,Mesh mesh){
    if(mesh.settings().structure()==Structure.GROUND||mesh.settings().structure()==Structure.TUNNEL
        ||RoadInfrastructure.customBridge(mesh))return false;
    return FOOTINGS.get(mesh,m->new RoadSolidOverlap.Index(RoadStructures.edgeSlabs(m,EDGE_GROUND))).intersects(part);
  }

  /** Actual full deck plus the exterior solid footing of an elevated parapet.
   * The bearing is checked even in the batch reservation pass, before the
   * neighboring road has published its generated structures. */
  public static boolean blockedByRoad(Part part,Mesh mesh,double headroom){
    return RoadClearance.structureInvades(part,clearanceMesh(mesh),headroom)||footingContact(part,mesh);
  }

  /** A shallow contact with the outside 0.75 m of a road, at the mounting deck's
   * own level. Motor lanes, medians, lower/upper crossings and return loops remain
   * hard obstacles. The final fitted assembly must clear the COMPLETE deck. */
  public static boolean vergeContact(Part part,Mesh mesh,RoadGantry.Station station,double headroom){
    if(!foot(part))return false;
    var envelope=clearanceMesh(mesh);
    if(!blockedByRoad(part,mesh,headroom))return false;
    var inner=INTERIOR.get(mesh,m->ribbon(m,m.samples().stream()
        .map(s->new Sample(s.center(),s.left(),s.distance(),Math.max(.01,s.halfWidth()-VERGE))).toList()));
    if(RoadClearance.structureInvades(part,inner,headroom)
        ||RoadClearance.structureInvades(part,MOTOR.get(mesh,LaneDeck::motorOnly),headroom))return false;
    if(Math.abs(RoadQueries.horizontal(mesh,part.a()).sample().center().y()-station.sample().center().y())>.75)return false;
    // structureContacts.ours is the road surface; other is the member's vertical
    // midpoint, which would misclassify every tall same-level column as a crossing.
    for(var contact:RoadClearance.structureContacts(part,envelope,headroom))if(contact.blocked()
        &&Math.abs(contact.ours().y()-station.sample().center().y())>.75)return false;
    return true;
  }

  /** Candidate immediately outside the host fascia. This exact transverse
   * bound finds narrow diagonal gaps that a coarse 0.2 m search can step over. */
  public static double tightOffset(Mesh mesh,RoadGantry.Station station,int side,double pedestal,V originalFoot){
    var mount=station.sample();V normal=mount.left().mul(side);
    double extent=.5*pedestal*(Math.abs(normal.x())+Math.abs(normal.z()));
    double edge=mount.halfWidth();
    for(double d=Math.max(0,station.distance()-1.1);d<=Math.min(mesh.length(),station.distance()+1.1)+1e-7;d+=.2){
      var at=RoadStructures.sample(mesh,d);
      double fascia=RoadStreetscape.raised(mesh,at,side)&&!RoadInfrastructure.customBridge(mesh)?.35*RoadInfrastructure.endTaper(mesh,at):0;
      edge=Math.max(edge,at.at(side*(at.halfWidth()+fascia),0).sub(mount.center()).dot(normal));
    }
    double shift=edge+extent+.0001-originalFoot.sub(mount.center()).dot(normal);
    return Math.max(-.6,Math.min(3,shift));
  }

  /** Recognize the actual saved outer barrier assembly without reclassifying
   * generic concrete bridge beams, lamp posts, median rails or user furniture.
   * Legacy barrier parts had no model tag, so geometry supplies the boundary. */
  public static boolean boundaryPart(Mesh mesh,Part p){
    if(p.pier()||RoadGantry.member(p)||p.heights()!=null)return false;
    boolean shape=p.material()==Material.NOISE_BARRIER&&Math.abs(p.height()-RoadNoiseModel.HEIGHT)<1e-7
        ||p.material()==Material.CONCRETE&&p.width()<=.71&&p.height()<=.81
        ||p.material()==Material.STEEL&&p.width()<=.31&&p.height()<=.19
        ||p.material()==Material.DARK_STEEL&&Math.abs(p.width()-.16)<1e-7&&p.height()<=1.25;
    if(!shape)return false;
    int side=0;
    for(V v:List.of(p.a(),p.b())){
      var q=RoadQueries.horizontal(mesh,v);var s=q.sample();int here=q.lateral()<0?-1:1;
      if(side!=0&&side!=here)return false;side=here;
      double rail=s.halfWidth()-RoadRailJoin.inset(mesh,s,here);
      if(Math.abs(Math.abs(q.lateral())-rail)>.42||v.y()<s.center().y()-.04||v.y()>s.center().y()+3.1)return false;
    }
    return true;
  }

  private RoadGantryMounts(){}
}
