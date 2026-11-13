package com.sora.splineroads.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** One running and at most one latest queued preview. Cancellation is cooperative,
 * and never publishes a cancelled result as a geometry error. */
public final class LatestPlanningTask<T> {
  private static final class Request {
    final AtomicBoolean cancelled = new AtomicBoolean();
    FutureTask<Void> task;
  }
  private final ThreadPoolExecutor executor;
  private final String phase;
  private final double seconds;
  private Request current;

  public LatestPlanningTask(String name) {this(name,Double.POSITIVE_INFINITY);}

  public LatestPlanningTask(String name,double seconds) {
    if(!(seconds>0))throw new IllegalArgumentException("Planning budget must be positive");
    phase = name;
    this.seconds = seconds;
    executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), task -> {
          Thread thread = new Thread(task, name);
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
  }

  public synchronized void cancel() {
    Request previous = current;
    current = null;
    if (previous != null) {
      previous.cancelled.set(true);
      previous.task.cancel(true);
      executor.purge();
    }
  }

  public synchronized void submit(Callable<T> computation, BiConsumer<T, Throwable> completion) {
    cancel();
    Request request = new Request();
    current = request;
    request.task = new FutureTask<>(() -> {
      T result = null;
      Throwable failure = null;
      try (var scope = RoadPlanningBudget.open(phase, seconds, request.cancelled::get)) {
        RoadPlanningBudget.check();
        result = computation.call();
        RoadPlanningBudget.check();
      } catch (Throwable error) {
        failure = error;
      } finally {
        RoadQueries.clearThreadCache();
      }
      try {
        if (!request.cancelled.get()) completion.accept(result, failure);
      } finally {
        synchronized (LatestPlanningTask.this) {if (current == request) current = null;}
      }
      // FutureTask otherwise stores an Error without invoking completion, leaving
      // the editor permanently busy. Report it to the UI and the log, then retain
      // the exceptional FutureTask outcome rather than silently swallowing it.
      if(failure instanceof Error error){
        System.getLogger("SplineRoads/planning").log(System.Logger.Level.ERROR,phase,error);
        throw error;
      }
      return null;
    });
    executor.execute(request.task);
  }

  public int queued() { return executor.getQueue().size(); }
}
