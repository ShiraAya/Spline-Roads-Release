package com.sora.splineroads.core;

import java.util.*;

/** Diagnostic phase clocks, not a performance claim. Slow operations log even without a profiler. */
public final class RoadTimings implements AutoCloseable {
  private static final System.Logger LOG=System.getLogger("SplineRoads/performance");
  private final long started=System.nanoTime();private long previous=started;
  private final String operation;private final int roads,edited;
  private final Map<String,Double> phases=new LinkedHashMap<>();
  private RoadTimings(String operation,int roads,int edited){this.operation=operation;this.roads=roads;this.edited=edited;}
  public static RoadTimings start(String operation,int roads,int edited){return new RoadTimings(operation,roads,edited);}
  public void stage(String phase){long now=System.nanoTime();double ms=(now-previous)/1e6;phases.merge(phase,ms,Double::sum);previous=now;
    if(ms>=1000||Boolean.getBoolean("sr.profile"))LOG.log(System.Logger.Level.INFO,"SR stage {0}/{1}: {2} ms, elapsed={3} ms",operation,phase,Math.round(ms),Math.round((now-started)/1e6));
  }
  @Override public void close(){
    double total=(System.nanoTime()-started)/1e6;
    if(total>=250||Boolean.getBoolean("sr.profile"))LOG.log(System.Logger.Level.INFO,
        "SR {0}: {1} ms, roads={2}, requested={3}, phases(ms)={4}",operation,Math.round(total),roads,edited,phases);
  }
  public static void reportLoad(long started,int roads){
    double total=(System.nanoTime()-started)/1e6;
    if(total>=100||Boolean.getBoolean("sr.profile"))LOG.log(System.Logger.Level.INFO,"SR load: {0} ms, roads={1}",Math.round(total),roads);
  }
}
