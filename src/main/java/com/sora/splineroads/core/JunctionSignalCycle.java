package com.sora.splineroads.core;

import java.util.*;
import com.sora.splineroads.core.JunctionSpec.*;

/** Immutable arithmetic clock; independent lane heads require no ticking entities or packets. */
public final class JunctionSignalCycle {
  private final int[] phaseSlots,pedestrianArms;
  private final int[][] leftSlots;
  private final boolean[] crossings,splitArms;
  private final int duration,green,yellow,offset,period;
  private final boolean enabled;
  public JunctionSignalCycle(JunctionSpec s,List<Integer> pedestrianArms) {
    int n=s.arms().size();phaseSlots=new int[n];leftSlots=new int[n][];
    crossings=new boolean[n];splitArms=new boolean[n];this.pedestrianArms=new int[n];
    var phases=new TreeSet<Integer>();
    for(int i=0;i<n;i++){
      Arm a=s.arms().get(i);
      if(a.incoming()>0)phases.add(a.phase());
    }
    var groups=new ArrayList<>(phases);
    for(int i=0;i<n;i++){
      Arm a=s.arms().get(i);phaseSlots[i]=normalActive(s,i)?groups.indexOf(a.phase()):-1;
      leftSlots[i]=new int[a.incoming()];Arrays.fill(leftSlots[i],-1);
      for(int j=0;j<a.incoming();j++){
        Lane lane=a.lanes().get(j);
        if(lane.splitLeft()&&(JunctionPlanner.mask(s,i,j)&(JunctionSpec.LEFT|JunctionSpec.UTURN))!=0){leftSlots[i][j]=groups.indexOf(a.phase());splitArms[i]=true;}
      }
      crossings[i]=a.crosswalk();this.pedestrianArms[i]=pedestrianArms.isEmpty()?i:pedestrianArms.get(i);
    }
    green=s.greenSeconds();yellow=s.yellowSeconds();duration=green+yellow+s.allRedSeconds();
    period=duration*groups.size();offset=s.timeOffset();enabled=s.control()==Control.SIGNALS;
  }
  private static boolean normalActive(JunctionSpec s,int arm){
    return JunctionSignals.normalMask(s,arm,-1)!=0||JunctionSignals.rightControlled(s,arm,-1);
  }
  private long tick(int slot,long time){
    if(!enabled||period==0||slot<0)return -1;
    long at=Math.floorMod(Math.floorMod(time,period*20L)+offset*20L,period*20L);
    return at/(duration*20L)==slot?at%(duration*20L):-1;
  }
  private int state(int slot,long time){
    long t=tick(slot,time);return t<0?0:t<green*20L?1:t<(green+yellow)*20L?2:0;
  }
  public int vehicle(int arm,long time) {
    if(arm<0||arm>=phaseSlots.length)return 0;
    if(!splitArms[arm])return state(phaseSlots[arm],time);
    long t=tick(phaseSlots[arm],time);
    return t>=0&&t<green*10L?1:0;
  }
  public int vehicle(int arm,int lane,boolean left,long time){
    if(!left)return vehicle(arm,time);
    if(arm<0||arm>=leftSlots.length||lane<0||lane>=leftSlots[arm].length)return 0;
    long t=tick(leftSlots[arm][lane],time);
    return t<green*10L?0:t<green*20L?1:t<(green+yellow)*20L?2:0;
  }
  public int pedestrian(int arm,long time) {
    if(!enabled||arm<0||arm>=crossings.length||!crossings[arm])return 0;
    int controller=pedestrianArms[arm];
    if(vehicle(controller,time)!=0)return 0;
    for(int lane=0;lane<leftSlots[controller].length;lane++)if(vehicle(controller,lane,true,time)!=0)return 0;
    return 1;
  }
  public int pedestrianVehicleArm(int arm){return pedestrianArms[arm];}
  public int periodSeconds(){return period;}
  public int resolutionTicks(){if((green&1)!=0)for(boolean split:splitArms)if(split)return 10;return 20;}
}
