package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** A nested parallel deck shares the upper bridge's foundations and superstructure. */
public final class MultiLevelBridges {
  public static final String MODEL="sr:stacked_bridge:";
  public static boolean compatible(Mesh lower,Mesh upper){
    if(lower==upper||lower.closed()||upper.closed()||lower.length()<8||upper.length()<8
        ||lower.settings().structure()==Structure.TUNNEL||upper.settings().structure()==Structure.TUNNEL
        ||!elevatedDeck(lower)||!elevatedDeck(upper)||LaneDeck.hasOpenings(lower)||LaneDeck.hasOpenings(upper))return false;
    double clearance=RoadStructureClearance.headroom(lower);
    if(lower.settings().options().railbed().active()&&lower.settings().options().railbed().fittings().catenary())
      clearance=Math.max(clearance,lower.settings().options().railbed().fittings().wireHeight()+.55);
    for(var s:lower.samples()){
      var q=RoadQueries.horizontal(upper,s.center());var t=q.sample();double along=s.center().sub(t.center()).dot(q.tangent().horizontalUnit());
      if(t.distance()<1e-6&&along<-.6||t.distance()>upper.length()-1e-6&&along>.6)return false;
      double gap=t.center().y()-upper.settings().thickness()-RoadInfrastructure.girderDepth(upper)-s.center().y();
      if(gap<clearance-.0000001||gap>RoadStructures.MAX_DROP||Math.abs(t.left().horizontalUnit().dot(s.left().horizontalUnit()))<.985
          ||q.horizontalDistance()>Math.min(s.halfWidth(),t.halfWidth())+.01
          ||RoadStreetscape.underground(lower,s.distance())||RoadStreetscape.underground(upper,t.distance()))return false;
    }return true;
  }
  // Resolved AUTO ground corridors are obstacles, not another supported deck.
  // Their median remains available to the ordinary single-shaft search.
  private static boolean elevatedDeck(Mesh mesh){
    return mesh.settings().structure()==Structure.BRIDGE||mesh.settings().structure()==Structure.AUTO
        &&mesh.samples().stream().anyMatch(s->RoadStreetscape.raised(mesh,s,-1)&&RoadStreetscape.raised(mesh,s,1));
  }
  /** Null means this station has no shared lower deck. All returned parts belong to the upper road. */
  public static List<Part> supports(Mesh upper,List<Mesh> lowers,Sample at,double thickness,double depth,double width,double offset,Ground ground){
    var beneath=new ArrayList<Mesh>();double reach=offset;
    for(var lower:lowers){var q=RoadQueries.horizontal(lower,at.center());double along=at.center().sub(q.sample().center()).dot(q.tangent().horizontalUnit());
      if(q.sample().distance()<1e-6&&along<-.1||q.sample().distance()>lower.length()-1e-6&&along>.1)continue;
      if(!RoadStreetscape.raised(lower,q.sample(),-1)||!RoadStreetscape.raised(lower,q.sample(),1))continue;
      beneath.add(lower);reach=Math.max(reach,q.horizontalDistance()+q.sample().halfWidth()+width/2+.6);
    }
    if(beneath.isEmpty())return null;
    var frame=RoadSupports.portal(at,thickness,depth,reach,width,ground);
    if(frame.isEmpty()){ground.missingSupport(at.distance(),"多层桥共用支撑没有可用基础或净空，已省略");return List.of();}
    var shafts=frame.stream().filter(Part::pier).toList();
    if(shafts.size()<2){ground.missingSupport(at.distance(),"多层桥共用支撑无法成对放置，已省略");return List.of();}
    var min=shafts.stream().min(Comparator.comparingDouble(p->p.a().sub(at.center()).dot(at.left()))).orElseThrow();
    var max=shafts.stream().max(Comparator.comparingDouble(p->p.a().sub(at.center()).dot(at.left()))).orElseThrow();
    var parts=new ArrayList<>(frame);
    for(var lower:beneath){var q=RoadQueries.horizontal(lower,at.center());double top=q.sample().center().y()-lower.settings().thickness();
      double floor=ground.top(q.sample().center().x(),q.sample().center().z(),top);
      if(Double.isFinite(floor)&&floor>=top-.2)continue;
      var beam=new Part(new V(min.a().x(),top-.8,min.a().z()),new V(max.a().x(),top-.8,max.a().z()),Math.max(1.5,width),.8,false,Material.CONCRETE);
      if(ground.blocked(beam)){ground.missingSupport(at.distance(),"多层桥共用横梁缺少净空，已省略整组支撑");return List.of();}
      parts.add(beam);
    }
    String model=MODEL+upper.settings().options().infrastructure().bridge().name();
    return parts.stream().map(p->new Part(p.a(),p.b(),p.width(),p.height(),p.pier(),p.material(),p.frameA(),p.frameB(),model,p.heights())).toList();
  }
  private MultiLevelBridges(){}
}
