package com.sora.splineroads.client;

/** Spread queued light packets across frames; every task remains in vanilla FIFO order. */
public final class RoadClientLightBudget {
  private static net.minecraft.world.level.lighting.LevelLightEngine previous;
  private static long nextUpdate;
  public static long deadline(){
    return ClientRoads.INDEX.roads.isEmpty()?Long.MAX_VALUE:System.nanoTime()+500_000L;
  }
  /** Block light changes need not clone both published light maps at display FPS.
   * This gate applies only to the client's render-loop call, never server ticks.
   * Streaming publications follow the 50 ms game-tick cadence. An engine that
   * has become idle can publish a new isolated update immediately. */
  public static int update(net.minecraft.world.level.lighting.LevelLightEngine engine){
    if(ClientRoads.INDEX.roads.isEmpty())return engine.runLightUpdates();
    if(!engine.hasLightWork()){nextUpdate=0;return 0;}
    long now=System.nanoTime();
    if(engine==previous&&now<nextUpdate)return 0;
    previous=engine;nextUpdate=now+50_000_000L;
    return engine.runLightUpdates();
  }
  static void reset(){previous=null;nextUpdate=0;}
  private RoadClientLightBudget(){}
}
