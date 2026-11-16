package com.sora.splineroads.core;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class LatestPlanning202Validation {
 static void ok(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[]a)throws Exception{
  var timed=new LatestPlanningTask<Integer>("202 timeout",.05);var done=new CountDownLatch(1);var error=new AtomicReference<Throwable>();timed.submit(()->{for(;;)RoadPlanningBudget.check();},(v,e)->{error.set(e);done.countDown();});ok(done.await(5,TimeUnit.SECONDS),"timeout completion absent");ok(error.get() instanceof RoadPlanningBudget.Expired,"timeout reported as ordinary geometry failure");
  var worker=new LatestPlanningTask<Integer>("202 error");var afterError=new CountDownLatch(1);worker.submit(()->{throw new AssertionError("intentional worker error");},(v,e)->{error.set(e);afterError.countDown();});ok(afterError.await(5,TimeUnit.SECONDS),"Error swallowed by FutureTask");ok(error.get() instanceof AssertionError,"wrong Error cause");
  var next=new CountDownLatch(1);worker.submit(()->7,(v,e)->{ok(e==null&&v==7,"worker did not recover");next.countDown();});ok(next.await(5,TimeUnit.SECONDS),"next task blocked");
  var entered=new CountDownLatch(1);var canceledCompletion=new AtomicBoolean();worker.submit(()->{entered.countDown();for(;;)RoadPlanningBudget.check();},(v,e)->canceledCompletion.set(true));ok(entered.await(5,TimeUnit.SECONDS),"cancel task never started");worker.cancel();var finalDone=new CountDownLatch(1);worker.submit(()->8,(v,e)->finalDone.countDown());ok(finalDone.await(5,TimeUnit.SECONDS),"cancel did not release worker");ok(!canceledCompletion.get(),"cancel published stale error");
  System.out.println("LATEST202 PASS timeout/Error completion/recovery/cancellation checks=9");
 }
}
