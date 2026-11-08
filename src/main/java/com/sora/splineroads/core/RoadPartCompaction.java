package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Lossless storage reduction of continuous paving and tactile strips. */
public final class RoadPartCompaction {
  private record Point(long x,long y,long z){
    static Point of(V v){return new Point(Math.round(v.x()*1e6),Math.round(v.y()*1e6),Math.round(v.z()*1e6));}
  }
  private record Seam(Material material,String model,long height,Point left,Point right){}
  private static boolean eligible(Part p){return !p.pier()&&p.heights()==null&&p.model().isEmpty()
      &&(p.material()==Material.TACTILE||p.material().name().startsWith("WALK_"))&&p.a().distance(p.b())>1e-6;}
  private static Seam seam(Part p,boolean start){var q=p.base();return new Seam(p.material(),p.model(),Math.round(p.height()*1e9),Point.of(q.get(start?0:3)),Point.of(q.get(start?1:2)));}
  private static boolean straight(V a,V middle,V b){
    V span=b.sub(a);double length=span.dot(span);if(length<1e-12)return false;
    double t=middle.sub(a).dot(span)/length;return t>0&&t<1&&a.add(span.mul(t)).distance(middle)<1e-8;
  }
  private static Part join(Part a,Part b){
    if(a.a().distance(b.b())>16||Math.abs(a.height()-b.height())>1e-10)return null;
    var x=a.base();var y=b.base();
    if(x.get(3).distance(y.get(0))>1e-8||x.get(2).distance(y.get(1))>1e-8
        ||!straight(x.get(0),x.get(3),y.get(3))||!straight(x.get(1),x.get(2),y.get(2)))return null;
    return new Part(a.a(),b.b(),Math.max(a.width(),b.width()),a.height(),false,a.material(),x.get(0).sub(a.a()),y.get(3).sub(b.b()),a.model());
  }
  public static List<Part> compact(List<Part> parts){
    if(parts.size()<2)return parts;
    var starts=new HashMap<Seam,List<Integer>>();
    for(int i=0;i<parts.size();i++)if(eligible(parts.get(i)))starts.computeIfAbsent(seam(parts.get(i),true),k->new ArrayList<>()).add(i);
    boolean[] used=new boolean[parts.size()];var out=new ArrayList<Part>();boolean changed=false;
    for(int i=0;i<parts.size();i++)if(!used[i]){
      Part current=parts.get(i);used[i]=true;
      if(eligible(current))while(true){
        Part next=null;int selected=-1;
        for(int candidate:starts.getOrDefault(seam(current,false),List.of()))if(!used[candidate]){
          next=join(current,parts.get(candidate));if(next!=null){selected=candidate;break;}
        }
        if(selected<0)break;used[selected]=true;current=next;changed=true;
      }
      out.add(current);
    }
    return changed?List.copyOf(out):parts;
  }
  public static String budgetMessage(UUID road,List<Part> parts,int limit){
    var counts=new HashMap<String,Integer>();for(var p:parts){String group=p.material()==Material.TACTILE?"盲道":p.material().name().startsWith("WALK_")?"铺装":p.material().name().contains("LEAVES")||p.material().name().endsWith("_LOG")?"树木":p.material().name();counts.merge(group,1,Integer::sum);}
    String main=counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(3).map(e->e.getKey()+" "+e.getValue()).reduce((a,b)->a+"、"+b).orElse("无");
    return "道路 "+road+"：设施 "+parts.size()+"/"+limit+"（"+main+"）；请缩短本段或调整对应设施";
  }
  private RoadPartCompaction(){}
}
