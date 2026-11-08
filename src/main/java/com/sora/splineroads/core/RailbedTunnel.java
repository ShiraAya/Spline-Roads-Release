package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Faceted railway lining. Disabled sides remain open up to the train headroom. */
public final class RailbedTunnel {
  public static final double BOX_ROOF_THICKNESS=.85;
  private static double radius(RoadInfrastructure.Config c,double half){
    double x=half+1.55;
    return Math.max(c.headroom()/2+1,(c.headroom()/2+1)/Math.sqrt(1-half*half/(x*x)));
  }
  public static double top(RoadInfrastructure.Config c,double half){return c.tunnel()==RoadInfrastructure.Tunnel.ROUND?c.headroom()/2+radius(c,half):c.headroom();}
  public static double ceiling(RoadInfrastructure.Config c,double half,double lateral){
    double x=half+1.55,at=Math.max(-x,Math.min(x,lateral)),r=radius(c,half),center=c.headroom()/2;
    for(int i=0;i<16;i++){
      double a=-Math.PI/2+Math.PI*i/16,b=-Math.PI/2+Math.PI*(i+1)/16;
      double xa=x*Math.sin(a),xb=x*Math.sin(b);
      if(at<=xb+1e-8)return center+r*(Math.cos(a)+(Math.cos(b)-Math.cos(a))*(at-xa)/(xb-xa));
    }return center;
  }
  public static List<Part> shell(Mesh mesh){
    var out=new ArrayList<Part>();for(double d=0;d<mesh.length()-1e-7;d+=2)
      out.addAll(section(mesh,RoadStructures.sample(mesh,d),RoadStructures.sample(mesh,Math.min(mesh.length(),d+2)),"sr:railbed_lining"));
    return List.copyOf(out);
  }
  public static List<Part> section(Mesh mesh,Sample a,Sample b,String tag){
    var out=new ArrayList<Part>();var c=mesh.settings().options().infrastructure();var options=mesh.settings().options().railbed().fittings();
    boolean railway=mesh.settings().style()==Style.RAILBED;
    if(c.tunnel()!=RoadInfrastructure.Tunnel.ROUND){
      for(int side:new int[]{-1,1})if(!railway||options.rail(side))out.add(new Part(a.at(side*(a.halfWidth()+.75),mesh.settings().thickness()),b.at(side*(b.halfWidth()+.75),mesh.settings().thickness()),1.6,c.headroom()+mesh.settings().thickness(),false,Material.TUNNEL,a.left().mul(.8),b.left().mul(.8),tag));
      int bands=Math.max(1,(int)Math.ceil((Math.max(a.halfWidth(),b.halfWidth())*2+3.1)/6));
      for(int i=0;i<bands;i++){double u=-1+2.0*i/bands,v=-1+2.0*(i+1)/bands;
        out.add(new Part(a.at((a.halfWidth()+1.55)*(u+v)/2,-c.headroom()),b.at((b.halfWidth()+1.55)*(u+v)/2,-c.headroom()),1,BOX_ROOF_THICKNESS,false,Material.TUNNEL,a.left().mul((a.halfWidth()+1.55)*(v-u)/2),b.left().mul((b.halfWidth()+1.55)*(v-u)/2),tag));
      }return List.copyOf(out);
    }
    double ra=radius(c,a.halfWidth()),rb=radius(c,b.halfWidth()),center=c.headroom()/2;
    // Rounded crown on a continuous lower sidewall: no unexcavated grass shelf
    // between the imported bed footprint and the outward spring line.
    for(int side:new int[]{-1,1})if(!railway||options.rail(side))
      out.add(new Part(a.at(side*(a.halfWidth()+.75),mesh.settings().thickness()),b.at(side*(b.halfWidth()+.75),mesh.settings().thickness()),1.6,center+.65+mesh.settings().thickness(),false,Material.TUNNEL,a.left().mul(.8),b.left().mul(.8),tag));
    double bottomA=Math.PI/2,bottomB=Math.PI/2;
    var angles=new TreeSet<Double>();for(int i=-8;i<=8;i++)angles.add(i*Math.PI/16);
    angles.add(Math.acos((c.headroom()-center)/ra));angles.add(-Math.acos((c.headroom()-center)/ra));
    angles.add(Math.acos((c.headroom()-center)/rb));angles.add(-Math.acos((c.headroom()-center)/rb));
    var ordered=new ArrayList<>(angles);
    for(int i=1;i<ordered.size();i++){
      double lo=ordered.get(i-1),hi=ordered.get(i);int side=(lo+hi)>0?1:-1;
      double aa=Math.max(-bottomA,Math.min(bottomA,lo)),ab=Math.max(-bottomA,Math.min(bottomA,hi));
      double ba=Math.max(-bottomB,Math.min(bottomB,lo)),bb=Math.max(-bottomB,Math.min(bottomB,hi));
      if(ab-aa<1e-8&&bb-ba<1e-8)continue;
      double ya=center+ra*Math.cos((aa+ab)/2),yb=center+rb*Math.cos((ba+bb)/2);
      if(railway&&!options.rail(side)&&Math.min(ya,yb)<c.headroom()-1e-7)continue;
      V pa=a.at((a.halfWidth()+1.55)*(Math.sin(aa)+Math.sin(ab))/2,-center-ra*(Math.cos(aa)+Math.cos(ab))/2);
      V pb=b.at((b.halfWidth()+1.55)*(Math.sin(ba)+Math.sin(bb))/2,-center-rb*(Math.cos(ba)+Math.cos(bb))/2);
      V fa=a.left().mul((a.halfWidth()+1.55)*(Math.sin(ab)-Math.sin(aa))/2).add(new V(0,ra*(Math.cos(ab)-Math.cos(aa))/2,0));
      V fb=b.left().mul((b.halfWidth()+1.55)*(Math.sin(bb)-Math.sin(ba))/2).add(new V(0,rb*(Math.cos(bb)-Math.cos(ba))/2,0));
      out.add(new Part(pa,pb,.1,.65,false,Material.TUNNEL,fa,fb,tag));
    }
    // Close the shoulder recess between the vertical lining and the crown.
    // Follow the same faceted ceiling, keeping the full train envelope clear.
    for(int side:new int[]{-1,1})if(!railway||options.rail(side)){
      var bands=new TreeSet<Double>();bands.add(0.0);bands.add(1.0);
      for(int i=0;i<=8;i++){
        double f=Math.sin(i*Math.PI/16);
        for(double half:new double[]{a.halfWidth(),b.halfWidth()}){
          double u=((half+1.55)*f-(half-.05))/1.6;if(u>0&&u<1)bands.add(u);
        }
      }
      var cuts=new ArrayList<>(bands);
      for(int i=1;i<cuts.size();i++){
        double u=cuts.get(i-1),v=cuts.get(i);
        double ax=a.halfWidth()-.05+1.6*u,ay=a.halfWidth()-.05+1.6*v;
        double bx=b.halfWidth()-.05+1.6*u,by=b.halfWidth()-.05+1.6*v;
        double h0=Math.max(0,ceiling(c,a.halfWidth(),ay)-center),h1=Math.max(0,ceiling(c,a.halfWidth(),ax)-center);
        double h2=Math.max(0,ceiling(c,b.halfWidth(),bx)-center),h3=Math.max(0,ceiling(c,b.halfWidth(),by)-center);
        double height=Math.max(Math.max(h0,h1),Math.max(h2,h3));if(height<1e-8)continue;
        out.add(new Part(a.at(side*(ax+ay)/2,-center),b.at(side*(bx+by)/2,-center),ay-ax,height,false,Material.TUNNEL,
            a.left().mul(side*(ay-ax)/2),b.left().mul(side*(by-bx)/2),tag,new Heights(h0,h1,h2,h3)));
      }
    }
    return List.copyOf(out);
  }
  public static final String PORTAL="sr:railbed_portal";
  /** A short solid collar closes the voxel-sized gap around a curved opening. */
  public static List<Part> portals(Mesh mesh,List<RoadAutoTunnels.Region> roofs,Ground ground){
    var config=mesh.settings().options().infrastructure();
    if(!mesh.settings().options().railbed().active()||config.tunnel()!=RoadInfrastructure.Tunnel.ROUND)return List.of();
    var out=new ArrayList<Part>();var options=mesh.settings().options().railbed().fittings();
    for(var roof:roofs)for(boolean first:new boolean[]{true,false}){
      double station=first?roof.from():roof.to(),reach=.8;boolean buried=false;
      // Roof intervals are rounded to metres and can include a short canopy.
      // Search through that approach instead of probing before the first soil voxel.
      double limit=Math.min(RoadAutoTunnels.CANOPY+1,(roof.to()-roof.from())/2);
      for(double offset=Math.min(.4,limit);offset<=limit+1e-7;offset=Math.min(limit,offset+.5)){
        var probe=RoadStructures.sample(mesh,station+(first?1:-1)*offset);
        for(double side:new double[]{-1,0,1}){var p=probe.at(side*(probe.halfWidth()+1.55),0);double surface=ground.surface(p.x(),p.z(),p.y());buried|=Double.isFinite(surface)&&surface>=p.y()+config.headroom();}
        if(buried){reach=Math.max(.8,offset+.5);break;}if(offset>=limit)break;
      }
      if(!buried)continue;
      var a=RoadStructures.sample(mesh,Math.max(0,station-(first?1.02:reach)));
      var b=RoadStructures.sample(mesh,Math.min(mesh.length(),station+(first?reach:1.02)));
      double topA=top(config,a.halfWidth())+1.5,topB=top(config,b.halfWidth())+1.5;
      for(int i=0;i<16;i++){
        double u=Math.sin(-Math.PI/2+Math.PI*i/16),v=Math.sin(-Math.PI/2+Math.PI*(i+1)/16);int side=u+v<0?-1:1;
        double al=(a.halfWidth()+1.55)*u,ah=(a.halfWidth()+1.55)*v,bl=(b.halfWidth()+1.55)*u,bh=(b.halfWidth()+1.55)*v;
        double h0=ceiling(config,a.halfWidth(),ah),h1=ceiling(config,a.halfWidth(),al),h2=ceiling(config,b.halfWidth(),bl),h3=ceiling(config,b.halfWidth(),bh);
        if(!options.rail(side)){h0=Math.max(h0,config.headroom());h1=Math.max(h1,config.headroom());h2=Math.max(h2,config.headroom());h3=Math.max(h3,config.headroom());}
        var heights=new Heights(topA-h0,topA-h1,topB-h2,topB-h3);double height=0;for(int j=0;j<4;j++)height=Math.max(height,heights.get(j));
        out.add(new Part(a.at((al+ah)/2,-(h0+h1)/2),b.at((bl+bh)/2,-(h2+h3)/2),1,height,false,Material.TUNNEL,
            a.left().mul((ah-al)/2).add(new V(0,(h0-h1)/2,0)),b.left().mul((bh-bl)/2).add(new V(0,(h3-h2)/2,0)),PORTAL,heights));
      }
      for(int side:new int[]{-1,1}){
        double base=options.rail(side)?-mesh.settings().thickness():config.headroom();
        double height=Math.max(topA,topB)-base;var heights=new Heights(topA-base,topA-base,topB-base,topB-base);
        out.add(new Part(a.at(side*(a.halfWidth()+2.3),-base),b.at(side*(b.halfWidth()+2.3),-base),1.5,height,false,Material.TUNNEL,a.left().mul(.75),b.left().mul(.75),PORTAL,heights));
      }
    }return List.copyOf(out);
  }
  private RailbedTunnel(){}
}
