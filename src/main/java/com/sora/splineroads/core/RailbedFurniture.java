package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Persisted railway fittings: the renderer never scans terrain or MTR. */
public final class RailbedFurniture {
  public static final String WIRE="sr:catenary_wire";
  public static boolean visualOnly(Part p){return p.model().equals(WIRE);}
  /** A saved neighboring roof closes the seam even when the rest of it is open air. */
  public static boolean continues(Mesh own,Mesh other,List<Part> shell,boolean first){
    if(!other.settings().options().railbed().active()||!other.settings().options().railbed().fittings().catenary()
        ||other.settings().structure()==Structure.TUNNEL)return false;
    var at=first?own.first():own.last();
    if(Math.abs(own.settings().options().railbed().fittings().wireHeight()-other.settings().options().railbed().fittings().wireHeight())>1e-7)return false;
    for(var end:List.of(other.first(),other.last()))if(at.center().distance(end.center())<.01&&Math.abs(at.left().dot(end.left()))>.94
        &&RoadAutoTunnels.regions(shell).stream().noneMatch(r->end.distance()>=r.from()-.5&&end.distance()<=r.to()+.5))return true;
    return false;
  }
  public static List<Part> plan(Mesh mesh,Structure mode,List<Part> shell,List<RailbedPath.Path> paths,
      RailbedOptions options,RoadStructures.Ground ground){
    return plan(mesh,mode,shell,paths,options,ground,RoadFurniture.Phase.DEFAULT);
  }
  public static List<Part> plan(Mesh mesh,Structure mode,List<Part> shell,List<RailbedPath.Path> paths,
      RailbedOptions options,RoadStructures.Ground ground,RoadFurniture.Phase phase){
    var out=new ArrayList<Part>();
    var roofs=mode==Structure.TUNNEL?List.of(new RoadAutoTunnels.Region(0,mesh.length())):RoadAutoTunnels.regions(shell);
    // Continuous roofed runs, not total AB length. Open cuts never count.
    for(var roof:roofs){
      for(double d=roof.from()+6;d<roof.to()-1;d+=16){
        var a=RoadStructures.sample(mesh,d);var b=RoadStructures.sample(mesh,Math.min(d+1.2,roof.to()-.1));
        for(int side:new int[]{-1,1}){
          double lateralA=side*a.halfWidth()*.65,lateralB=side*b.halfWidth()*.65;
          double topA=mode==Structure.TUNNEL?RoadInfrastructure.ceiling(mesh.settings().options().infrastructure(),a.halfWidth(),lateralA):RoadAutoTunnels.HEADROOM;
          double topB=mode==Structure.TUNNEL?RoadInfrastructure.ceiling(mesh.settings().options().infrastructure(),b.halfWidth(),lateralB):RoadAutoTunnels.HEADROOM;
          if(options.tunnel()==RoadInfrastructure.Tunnel.ROUND){topA=RailbedTunnel.ceiling(mesh.settings().options().infrastructure(),a.halfWidth(),lateralA);topB=RailbedTunnel.ceiling(mesh.settings().options().infrastructure(),b.halfWidth(),lateralB);}
          // Recess into the roof: the bottom remains above the 5.985m train envelope.
          add(out,new Part(a.at(lateralA,-topA+.01),b.at(lateralB,-topB+.01),.22,.20,false,Material.LAMP,
              a.left().mul(.11),b.left().mul(.11),"sr:railbed_light"),ground);
        }
      }
    }
    if(!options.catenary()||paths.isEmpty())return List.copyOf(out);
    var trackMeshes=paths.stream().map(p->RailbedGeometry.plan(List.of(p),0,Structure.AUTO).mesh()).toList();
    double firstPole=phase.first(.5,options.poleSpacing());if(phase.direction()<0&&firstPole<1e-7)firstPole+=options.poleSpacing();
    var stations=new TreeSet<Double>();for(double d=firstPole;phase.direction()>0?d<mesh.length()-1e-7:d<=mesh.length()+1e-7;d+=options.poleSpacing())stations.add(Math.min(d,mesh.length()));
    var supported=new TreeSet<Double>();
    for(double d:stations){
      if(roofs.stream().anyMatch(r->d>=r.from()-.5&&d<=r.to()+.5)){continue;}
      var at=RoadStructures.sample(mesh,d);
      if(ground.furnitureClear(at.center()))continue; // Keep the running phase, leave the road crossing clear.
      supported.add(d);
      var orderedMeshes=trackMeshes.stream().sorted(Comparator.comparingDouble(m->RoadQueries.horizontal(m,at.center()).sample().center().sub(at.center()).dot(at.left()))).toList();
      var tracks=orderedMeshes.stream().map(m->RoadQueries.horizontal(m,at.center()).sample().center()).toList();
      var wires=tracks.stream().map(p->p.add(new V(0,options.wireHeight(),0))).toList();
      var poles=new ArrayList<V>();
      if(tracks.size()==1)poles.add(at.at(options.singleSide()*(at.halfWidth()-.16),0));
      else for(int i=0;i+1<tracks.size();i+=2)poles.add(tracks.get(i).add(tracks.get(i+1)).mul(.5));
      // An odd final track shares the last bidirectional pole.
      for(int i=0;i<poles.size();i++){
        V foot=poles.get(i),top=foot.add(new V(0,options.wireHeight()+.25,0));
        add(out,new Part(foot,foot,.18,options.wireHeight()+.55,true,Material.STEEL,null,null,"sr:catenary_pole"),ground);
        int from=tracks.size()==1?0:i*2,to=tracks.size()==1?0:Math.min(tracks.size()-1,i==poles.size()-1?tracks.size()-1:from+1);
        for(int k=from;k<=to;k++){
          V tip=wires.get(k).add(new V(0,.25,0));
          add(out,new Part(top,tip,.12,.12,false,Material.STEEL,null,null,"sr:catenary_arm"),ground);
          add(out,new Part(wires.get(k),wires.get(k),.10,.25,true,Material.DARK_STEEL,null,null,"sr:catenary_drop"),ground);
        }
      }
    }
    // Bound each open-air run by actual supports. Never join supports across a
    // roof, or leave a free conductor past the final pole of an isolated bed.
    var boundaries=new ArrayList<RoadAutoTunnels.Region>();double begin=0;
    for(var roof:roofs){if(roof.from()>begin)boundaries.add(new RoadAutoTunnels.Region(begin,roof.from()));begin=Math.max(begin,roof.to());}
    if(begin<mesh.length())boundaries.add(new RoadAutoTunnels.Region(begin,mesh.length()));
    for(var run:boundaries){
      var poles=supported.subSet(run.from(),true,run.to(),true);
      boolean first=run.from()<1e-7&&ground.wireContinues(true),last=run.to()>mesh.length()-1e-7&&ground.wireContinues(false);
      if(poles.isEmpty()&&!(first&&last))continue;
      double from=first?0:poles.first(),to=last?mesh.length():poles.last();
      for(var track:trackMeshes)for(double d=from;d<to-1e-7;d+=2){
        double end=Math.min(to,d+2);
        V a=RoadQueries.horizontal(track,RoadStructures.sample(mesh,d).center()).sample().center().add(new V(0,options.wireHeight(),0));
        V b=RoadQueries.horizontal(track,RoadStructures.sample(mesh,end).center()).sample().center().add(new V(0,options.wireHeight(),0));
        if(a.distance(b)>1e-7)out.add(new Part(a,b,.035,.035,false,Material.DARK_STEEL,null,null,WIRE));
      }
    }
    return List.copyOf(out);
  }
  private static void add(List<Part> parts,Part p,RoadStructures.Ground ground){
    if(ground.blocked(p))throw new IllegalArgumentException(p.model().startsWith("sr:catenary")?"接触网支柱或悬臂与通行空间冲突；请增大轨间距、提高接触网或关闭接触网":"铁路照明与通行空间冲突");
    parts.add(p);
  }
  private RailbedFurniture(){}
}
