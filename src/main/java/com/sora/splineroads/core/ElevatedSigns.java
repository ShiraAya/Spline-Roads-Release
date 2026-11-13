package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.SmartSigns.*;
import java.util.*;

/** The pedestal is saved with the host, and the editable board follows that pedestal. */
public final class ElevatedSigns {
    private static final String SEAT="sr:elevated_access_sign_seat";
    public static List<Branch> destinations(Mesh host,ElevatedAccess.Reservation access,int flow){
        int outer=host.settings().options().leftTraffic()?-1:1;
        boolean paired=access.additions().stream().anyMatch(a->a.sign()==flow);
        var elevated=new Branch("elevated",List.of(),paired?-outer:0,true,List.of());
        return paired?List.of(elevated,new Branch("surface",List.of(),outer,true,List.of())):List.of(elevated);
    }
    private static Support placement(Mesh host,LaneSections.Cut cut,double lead,boolean paired){
        int flow=cut.sign();double station=cut.begin()-flow*lead;
        if(station<1||station>host.length()-1)return null;
        var at=RoadStructures.sample(host,station);var layout=RoadProfile.layout(host,at);
        int side=LaneAdditions.side(layout,flow);
        double offset=layout.cycleWidth()>.1&&host.settings().options().streetscape().separator()==RoadStreetscape.Separator.GREEN
                ? layout.outer(side)+side*RoadStreetscape.separatorWidth(host.settings().options())*.5
                : side*(at.halfWidth()+(host.settings().options().sidewalk().enabled()?.60:-.22));
        V right=at.left().mul(flow);
        double inner=layout.catalog().twoWay()?layout.medianCenter():layout.outer(-side)-side*.55;
        var lane=LaneSections.live(host,station).lanes().stream().filter(l->l.sign()==flow)
            .min(Comparator.comparingDouble(l->side*l.position().sub(at.center()).dot(at.left()))).orElseThrow();
        double boardCenter=lane.position().sub(at.at(offset,0)).dot(right);
        return new Support(at.at(offset,-.42),right,right.left(),paired,-side*flow,(inner-offset)*flow,paired?null:boardCenter);
    }
    public static Support saved(Mesh host,ElevatedAccess.Reservation access,List<Part> structures){
        var exit=access.cuts().stream().filter(c->!c.arrival()).findFirst().orElse(null);
        if(exit==null)return null;
        var seats=structures.stream().filter(p->p.model().equals(SEAT)).toList();
        for(int lead=18;lead<=54;lead+=3){
            var mount=placement(host,exit,lead,destinations(host,access,exit.sign()).size()==2);
            if(mount==null)continue;
            if(hasSeat(seats,seat(mount,mount.gantrySpan()))&&hasSeat(seats,seat(mount,0)))return mount;
            // Old two-post seats stood on the carriageway side of a rail median.
            var oldGantry=new Support(mount.foot(),mount.right(),mount.front(),mount.paired(),mount.cantileverSide(),
                mount.gantrySpan()-mount.cantileverSide()*.58,mount.boardCenter());
            if(hasSeat(seats,seat(oldGantry,oldGantry.gantrySpan()))&&hasSeat(seats,seat(oldGantry,0)))return oldGantry;
            var legacy=new Support(mount.foot(),mount.right(),mount.front(),mount.paired(),mount.cantileverSide());
            if(hasSeat(seats,seat(legacy,0)))return legacy;
        }
        return null;
    }
    private static boolean hasSeat(List<Part> seats,Part target){
        return seats.stream().anyMatch(p->p.a().add(new V(0,p.height(),0)).distance(target.a().add(new V(0,target.height(),0)))<.01);
    }
    private static Part seat(Support mount,double offset){
        var foot=mount.foot().add(mount.right().mul(offset)).add(mount.front().mul(mount.gantrySpan()==0?-.08:-.22)).add(new V(0,-.42,0));
        return new Part(foot,foot,.62,.42,true,Material.CONCRETE,null,null,SEAT);
    }
    public static void reserve(List<Part> parts,Mesh host){
        for(var access:host.settings().options().lanePoints().access()){
            var exit=access.cuts().stream().filter(c->!c.arrival()).findFirst().orElse(null);
            if(exit==null)continue;
            // Across a seam only the section containing the nominal post station
            // owns the gantry. Keep the original lead search on single hosts.
            if((exit.begin()<0||exit.begin()>host.length())
                &&placement(host,exit,18,false)==null)continue;
            var branches=destinations(host,access,exit.sign());
            var key=new Key(access.connection(),0,1);

            for(int lead=18;lead<=54;lead+=3){
                var support=placement(host,exit,lead,branches.size()==2);
                if(support==null)continue;
                var board=State.empty(key).layout(SmartSigns.Mode.ROADSIDE,access.connection(),branches,"").supported(support);
                var outer=seat(support,0);
                // The separator has its own concrete planter. Seat the plinth
                // on its finished top, preserving that continuous foundation.
                double top=outer.a().y();
                for(var part:parts)if(part.material()==Material.CONCRETE&&part.model().equals("sr:cycle_planter")&&RoadSolidOverlap.intersects(outer,part))
                    top=Math.max(top,part.top().stream().mapToDouble(V::y).max().orElse(top));
                double height=support.foot().y()-top;
                if(height<=0)continue;
                var base=new V(outer.a().x(),top,outer.a().z());
                var bases=List.of(new Part(base,base,outer.width(),height,true,outer.material(),null,null,SEAT),seat(support,support.gantrySpan()));
                var members=new ArrayList<>(SmartSigns.mountParts(host,board));
                members.addAll(bases);
                var inner=bases.get(1);
                var insert=new Part(inner.a(),inner.a(),inner.width(),1.6,true,Material.CONCRETE);
                var medianRails=parts.stream().filter(p->medianRail(p,insert,host)).toList();
                var solids=new RoadSolidOverlap.Index(parts.stream().filter(p->p.material()!=Material.GREEN&&p.material()!=Material.SOIL&&!medianRails.contains(p)).toList());
                if(members.stream().anyMatch(solids::intersects))continue;
                // The post replaces only the short median rail section at its foot.
                // End the two rail runs flush with the post, preserving both approaches.
                var mast=members.get(1);
                for(var rail:medianRails){
                    parts.remove(rail);
                    RoadStructures.subtractFootprint(parts,rail,List.of(rail.a().y()>=inner.a().y()+inner.height()?mast:inner));
                }
                RoadStructures.trimPlanting(parts,bases,true);
                parts.addAll(bases);break;
            }
        }
    }
    private static boolean medianRail(Part p,Part insert,Mesh host){
        if(p.pier()||!p.model().isEmpty()||p.heights()!=null||!RoadSolidOverlap.intersects(p,insert))return false;
        if(!(ForkSignPlacement.isBase(p)||p.material()==Material.CONCRETE&&p.width()==.36&&p.height()==.35
            ||p.material()==Material.STEEL&&(p.width()==.1||p.width()==.12||p.width()==.3)&&p.height()<=.18
            ||p.material()==Material.DARK_STEEL&&p.width()==.16&&p.height()<=1.22))return false;
        var q=RoadQueries.horizontal(host,p.a().add(p.b()).mul(.5));var l=RoadProfile.layout(host,q.sample());
        return l.catalog().twoWay()&&Math.abs(q.lateral()-l.medianCenter())<=l.median()/2+.01;
    }
    private ElevatedSigns(){}
}
