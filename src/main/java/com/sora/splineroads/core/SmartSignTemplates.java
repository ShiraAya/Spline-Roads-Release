package com.sora.splineroads.core;
import com.sora.splineroads.core.SmartSigns.Branch;
import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;

/** The three user-authored approach symbols: direct turn, loop turn, or both.
 * Reachability chooses the arrows and text fields; road curvature never distorts the symbol. */
public final class SmartSignTemplates {
  public static final int VERSION=3;
  public static List<Branch> compile(String preset,List<Branch> confirmed){return compile(preset,confirmed,false);}
  public static List<Branch> compile(String preset,List<Branch> confirmed,boolean leftTraffic){
    int direct=leftTraffic?-1:1;boolean both=confirmed.stream().anyMatch(b->b.side()==direct)&&confirmed.stream().anyMatch(b->b.side()==-direct);
    var out=new ArrayList<Branch>();
    for(var branch:confirmed){
      List<V> motif;
      if(branch.id().equals("through")||branch.side()==0)motif=List.of(new V(0,0,.03),new V(0,0,1));
      else if(branch.side()==direct){
        double low=both?.12:.28,high=both?.27:.46;var p=new ArrayList<V>();
        for(int i=0;i<=32;i++){double t=i/32.;double x=.88*t,y=low+(high-low)*(1-Math.pow(1-t,3));p.add(new V(direct*x,0,y));}
        p.add(new V(direct*.96,0,high));motif=List.copyOf(p);
      }else{
        var p=new ArrayList<V>();p.add(new V(0,0,.03));p.add(new V(0,0,.46));
        // Tangential quarter-ellipse from the upright stem into a clockwise loop.
        for(int i=0;i<=32;i++){double a=Math.PI-i*Math.PI*1.5/32;p.add(new V(direct*(.14+.14*Math.cos(a)),0,.46+.08*Math.sin(a)));}
        p.add(new V(-direct*.88,0,.38));p.add(new V(-direct*.96,0,.38));motif=List.copyOf(p);
      }
      out.add(new Branch(branch.id(),branch.lanes(),branch.side(),branch.dedicated(),motif));
    }
    return List.copyOf(out);
  }
  private SmartSignTemplates(){}
}
