package com.sora.splineroads.client;

import java.util.HashMap;
import java.util.Random;

public final class FrameSamplesValidation {
  private static int checks;
  private static void require(boolean ok){checks++;if(!ok)throw new AssertionError("Cache check "+checks);}
  public static void main(String[] args)throws Exception{
    var cache=new RoadFrameSamples(1);
    cache.put(0,123);require(cache.get(0)==123);
    cache.put(Long.MIN_VALUE,456);require(cache.get(0)==0);require(cache.get(Long.MIN_VALUE)==456);
    cache.clear();require(cache.get(Long.MIN_VALUE)==0);
    var generation=RoadFrameSamples.class.getDeclaredField("generation");generation.setAccessible(true);
    generation.setInt(cache,-1);cache.put(17,999);cache.clear();require(cache.get(17)==0);
    cache.put(17,888);require(cache.get(17)==888);
    cache=new RoadFrameSamples(8192);var random=new Random(2111116);var expected=new HashMap<Long,Integer>();
    for(int frame=0;frame<200;frame++){
      cache.clear();expected.clear();
      for(int i=0;i<20000;i++){
        long key=random.nextInt(16000)-8000;
        int value=cache.get(key);require(value==0||value==expected.getOrDefault(key,0));
        int next=random.nextInt()|1;cache.put(key,next);expected.put(key,next);require(cache.get(key)==next);
      }
    }
    System.out.println("FrameSamplesValidation PASS checks="+checks);
  }
}
