package com.sora.splineroads.core;
import java.util.function.BooleanSupplier;
/** Cooperative deadline for PURE/pre-write work. Cancellation must never be caught as a bad route.
 * No thread killing, no future.get on the server, no deadline after the first world mutation. */
public final class RoadPlanningBudget implements AutoCloseable {
  public static final class Aborted extends RuntimeException {public Aborted(String s){super(s);}}
  private static final ThreadLocal<RoadPlanningBudget> CURRENT=new ThreadLocal<>();
  private final RoadPlanningBudget previous;private final long deadline;private final BooleanSupplier cancelled;
  private String phase;private boolean disabled;
  private RoadPlanningBudget(String phase,double seconds,BooleanSupplier cancelled){
    previous=CURRENT.get();this.phase=phase;this.cancelled=cancelled;
    long own=Double.isInfinite(seconds)?Long.MAX_VALUE:System.nanoTime()+(long)(seconds*1e9);deadline=previous==null?own:Math.min(own,previous.deadline);CURRENT.set(this);
  }
  public static RoadPlanningBudget open(String phase,double seconds){return open(phase,seconds,()->false);}
  public static RoadPlanningBudget open(String phase,double seconds,BooleanSupplier cancelled){return new RoadPlanningBudget(phase,seconds,cancelled);}
  /** Interactive planning finishes its finite candidate search; elapsed time is
   * not evidence that geometry is impossible. Keep user cancellation checkpoints. */
  public static RoadPlanningBudget cancellable(String phase){return cancellable(phase,()->false);}
  public static RoadPlanningBudget cancellable(String phase,BooleanSupplier cancelled){return open(phase,Double.POSITIVE_INFINITY,cancelled);}
  public static void phase(String name){var b=CURRENT.get();if(b!=null)b.phase=name;check();}
  public static void check(){
    var b=CURRENT.get();if(b==null||b.disabled)return;
    if(Thread.currentThread().isInterrupted())throw new Aborted("匝道计算已取消，未写入道路");
    for(var scope=b;scope!=null;scope=scope.previous)if(scope.cancelled.getAsBoolean())throw new Aborted("匝道计算已取消，未写入道路");
    if(b.deadline!=Long.MAX_VALUE&&System.nanoTime()-b.deadline>=0)throw new Aborted("道路计算达到本次时间预算，已停止而非继续卡住；未写入道路。阶段："+b.phase+"。这不代表几何无解，可调整落点/路线后重试。");
  }
  /** Call only at the transaction's first mutation boundary; never abort a half-written road. */
  public static void committing(){for(var b=CURRENT.get();b!=null;b=b.previous)b.disabled=true;}
  @Override public void close(){if(CURRENT.get()==this){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}}
}
