package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Railway-only sections. Nothing in this planner can move the imported alignment. */
public final class RailbedStructures {
  public interface Ground extends RoadStructures.Ground {
    default double top(double x,double z,double y){return floor(new V(x,y,z));}
    default boolean joined(V p){return false;}
    double floor(V point);
    boolean covered(V point);
    boolean blocked(Part part);
  }
  public static final class Planner {
    private final Mesh mesh;private final Structure mode;private final Ground ground;
    private final RailbedOptions options;private final List<RailbedPath.Path> paths;
    private final List<Part> parts=new ArrayList<>();private int section=1;private boolean supportsDone;
    public Planner(Mesh mesh,Structure mode,Ground ground){this(mesh,mode,ground,RailbedOptions.DEFAULT,List.of());}
    public Planner(Mesh mesh,Structure mode,Ground ground,RailbedOptions options,List<RailbedPath.Path> paths){this.mesh=mesh;this.mode=mode;this.ground=ground;this.options=options;this.paths=List.copyOf(paths);}
    public boolean done(){return section>=mesh.samples().size()&&supportsDone;}
    public void step(){
      if(section>=mesh.samples().size()){
        if(mode==Structure.AUTO){
          var automatic=new Mesh(mesh.samples(),mesh.settings().structure(Structure.AUTO),mesh.min(),mesh.max(),mesh.length(),mesh.closed());
          // Share left/right terrain classification and the exact roof profile.
          var shell=RoadAutoTunnels.enclose(automatic,ground,parts).stream().filter(p->p.material()!=Material.LAMP).toList();
          parts.clear();for(var part:shell)add(part);
        }
        if(mode!=Structure.TUNNEL){
          var supportMesh=new Mesh(mesh.samples(),mesh.settings().structure(mode),mesh.min(),mesh.max(),mesh.length(),mesh.closed());
          parts.addAll(RoadStructures.supports(supportMesh,ground,RoadFurniture.Phase.DEFAULT));
        }
        parts.addAll(RailbedFurniture.plan(mesh,mode,parts,paths,options,ground));
        supportsDone=true;return;
      }
      var a=mesh.samples().get(section-1);var b=mesh.samples().get(section++);
      boolean tunnel=mode==Structure.TUNNEL;
      for(int side:new int[]{-1,1}){
        if(tunnel){
          add(new Part(a.at(side*(a.halfWidth()+RailbedGeometry.WALL/2),0),b.at(side*(b.halfWidth()+RailbedGeometry.WALL/2),0),RailbedGeometry.WALL,
              RailbedGeometry.HEADROOM+.35,false,Material.TUNNEL,
              a.left().mul(RailbedGeometry.WALL/2),b.left().mul(RailbedGeometry.WALL/2),"sr:railbed_wall"));
        }
        if(options.rail(side))add(new Part(a.at(side*(a.halfWidth()-RailbedGeometry.WALL/2),0),b.at(side*(b.halfWidth()-RailbedGeometry.WALL/2),0),RailbedGeometry.WALL,
            1.2,false,Material.CONCRETE,a.left().mul(RailbedGeometry.WALL/2),b.left().mul(RailbedGeometry.WALL/2),"sr:railbed_guardrail"));
      }
      if(tunnel){
        // Explicit frames allow wide shells without changing the common floor width.
        add(new Part(a.at(0,-RailbedGeometry.HEADROOM),b.at(0,-RailbedGeometry.HEADROOM),1,.35,false,Material.TUNNEL,
            a.left().mul(a.halfWidth()+RailbedGeometry.WALL),b.left().mul(b.halfWidth()+RailbedGeometry.WALL),"sr:railbed_roof"));
      }
    }
    private void add(Part part){if(ground.blocked(part))throw new IllegalArgumentException("铁路构件侵入既有通行空间："+location(part.a()));parts.add(part);}
    public List<Part> result(){if(!done())throw new IllegalStateException("铁路结构仍在规划");return List.copyOf(parts);}
  }
  public static RoadSolidOverlap.Index clearance(List<RailbedPath.Path> paths){return clearance(paths,5);}
  /** Supported rolling-stock body plus lateral motion allowance, excluding the bed shoulder. */
  public static RoadSolidOverlap.Index catenaryClearance(List<RailbedPath.Path> paths){return clearance(paths,3.4);}
  private static RoadSolidOverlap.Index clearance(List<RailbedPath.Path> paths,double bodyWidth){
    var parts=new ArrayList<Part>();for(var path:paths){var points=path.positions();double width=bodyWidth+2*RailbedGeometry.sweep(points);for(int i=1;i<points.size();i++){
      var a=points.get(i-1);var b=points.get(i);if(a.sub(b).horizontalLength()<1e-8)continue;
      // Use the same station frames as the deck. A chord-normal rectangle
      // protrudes beyond the clearance ribbon at curved/graded segment joins.
      parts.add(new Part(a.add(new V(0,.015,0)),b.add(new V(0,.015,0)),1,5.97,false,Material.DEFAULT,
          RailbedPath.tangent(points,i-1).left().mul(width/2),RailbedPath.tangent(points,i).left().mul(width/2),""));
    }}return new RoadSolidOverlap.Index(parts);
  }
  private static String location(V p){return Math.round(p.x())+", "+Math.round(p.y())+", "+Math.round(p.z());}
  private RailbedStructures(){}
}
