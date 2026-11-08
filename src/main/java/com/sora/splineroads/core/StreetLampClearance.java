package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import java.util.function.Predicate;

/** Matches complete generated lamps, never nearby bridge steel or unrelated furniture. */
public final class StreetLampClearance {
  private static long rounded(double n){return Math.round(n*1_000_000);}
  private static String point(V v){return rounded(v.x())+","+rounded(v.y())+","+rounded(v.z());}
  private static String key(Part p){return p.material()+":"+p.pier()+":"+point(p.a())+":"+point(p.b())+":"+rounded(p.width())+":"+rounded(p.height());}
  public static List<Part> removed(List<Part> parts,Predicate<V> clear){
    var scan=new Removal(parts,List.of(clear));while(!scan.done())scan.advance(1024);return scan.removed();
  }
  /** Resumable exact matching. One unit inspects one part or one sign, so a long
   * road does not turn a bounded maintenance turn into a full furniture scan. */
  public static final class Removal {
    private final List<Part> parts;
    private final List<Predicate<V>> clear;
    private final Set<String> keys=new HashSet<>();
    private final Set<Part> brackets=new HashSet<>();
    private final List<Part> removed=new ArrayList<>();
    private int phase,baseIndex,signIndex,memberIndex,resultIndex;
    private V foot;
    private long visits,signTests,keyBuilds;
    public Removal(List<Part> parts,List<Predicate<V>> clear){this.parts=parts;this.clear=List.copyOf(clear);}
    public boolean done(){return phase==4;}
    public long partVisits(){return visits;}
    public long signTests(){return signTests;}
    public long keyBuilds(){return keyBuilds;}
    public List<Part> removed(){if(!done())throw new IllegalStateException("lamp scan not complete");return List.copyOf(removed);}
    public int advance(int limit){
      int used=0;
      while(used<limit&&!done()){
        if(phase==0){
          if(baseIndex==parts.size()){phase=keys.isEmpty()&&brackets.isEmpty()?4:3;continue;}
          var base=parts.get(baseIndex++);used++;visits++;
          if(base.pier()&&base.material()==Material.LAMP_BLUE&&Math.abs(base.width()-.22)<1e-7&&Math.abs(base.height()-1.8)<1e-7){foot=base.a().sub(new V(0,.16,0));signIndex=0;phase=1;}
        }else if(phase==1){
          if(signIndex==clear.size()){phase=0;continue;}
          used++;signTests++;
          if(clear.get(signIndex++).test(foot)){memberIndex=0;phase=2;}
        }else if(phase==2){
          if(memberIndex==parts.size()){phase=0;continue;}
          var p=parts.get(memberIndex++);used++;visits++;
          if(!p.pier()&&p.material()==Material.STEEL&&Math.abs(p.width()-.1)<1e-7&&Math.abs(p.height()-.1)<1e-7
              &&p.a().sub(foot).horizontalLength()<1e-6&&(Math.abs(p.a().y()-foot.y()-7.3)<1e-6||Math.abs(p.a().y()-foot.y()-5.8)<1e-6)){
            V direction=p.b().sub(p.a()).horizontalUnit();
            for(int style=1;style<=4;style++)for(var lamp:RoadStreetscape.lamp(foot,direction,style)){keys.add(key(lamp));keyBuilds++;}
          }
          if(p.material()==Material.CONCRETE&&(p.pier()&&p.a().distance(foot.sub(new V(0,.12,0)))<1e-6&&Math.abs(p.width()-.38)<1e-7&&Math.abs(p.height()-.12)<1e-7
              ||!p.pier()&&p.b().distance(foot.sub(new V(0,.47,0)))<1e-6&&Math.abs(p.width()-.7)<1e-7&&Math.abs(p.height()-.35)<1e-7))brackets.add(p);
        }else{
          if(resultIndex==parts.size()){phase=4;continue;}
          var p=parts.get(resultIndex++);used++;visits++;
          boolean match=false;if(!keys.isEmpty()){keyBuilds++;match=keys.contains(key(p));}
          if(match||brackets.contains(p))removed.add(p);
        }
      }
      return used;
    }
  }
  private StreetLampClearance(){}
}
