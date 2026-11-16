package com.sora.splineroads.client;

import java.util.*;

public final class SectionLightOrderValidation {
  private static int checks;
  private static void require(boolean ok){checks++;if(!ok)throw new AssertionError("Light order check "+checks);}
  private static long position(int x,int y,int z){return ((x&0x3ffffffL)<<38)|((z&0x3ffffffL)<<12)|(y&0xfffL);}
  public static void main(String[] args){
    var random=new Random(2111616);
    for(int i=0;i<1000000;i++){long key=random.nextLong();require(RoadLightReadSet.worldPosition(RoadLightReadSet.orderKey(key))==key);}
    for(int x:new int[]{-33554432,-17,-16,-1,0,15,16,33554431})for(int z:new int[]{-33554432,-17,-16,-1,0,15,16,33554431})for(int y:new int[]{-2048,-17,-16,-1,0,15,16,2047}){
      long key=position(x,y,z);require(RoadLightReadSet.worldPosition(RoadLightReadSet.orderKey(key))==key);
    }
    var expected=new HashMap<Long,Integer>();var builder=new RoadLightReadSet.Builder();
    for(int i=0;i<20000;i++){long key=position(random.nextInt(96)-48,random.nextInt(64)-32,random.nextInt(96)-48);int value=(random.nextInt(16)<<4)|(random.nextInt(16)<<20);expected.putIfAbsent(key,value);}
    expected.forEach(builder::packed);var reads=builder.build();require(reads.size()==expected.size());
    var visited=new HashSet<Long>();var sections=new HashSet<Long>();long[] last={Long.MIN_VALUE};int[] switches={0};
    var cursor=reads.cursor();
    while(!cursor.complete())cursor.advance((key,requested)->{
      require(visited.add(key));require(requested==RoadLightReadSet.READ_PACKED);require(reads.contains(key));
      long section=RoadLightReadSet.orderKey(key)>>>12;
      if(section!=last[0]){switches[0]++;last[0]=section;require(sections.add(section));}
      return expected.get(key);
    },127);
    require(!cursor.changed());require(visited.equals(expected.keySet()));require(switches[0]==sections.size());
    // Changing any one voxel must still be localized to its original world key.
    int tested=0;
    for(long target:expected.keySet()){
      var change=reads.cursor();while(!change.complete())change.advance((key,r)->expected.get(key)^(key==target?16:0),251);
      require(change.changed()&&change.located()&&change.changedPosition()==target);
      if(++tested==100)break;
    }
    var inherited=new RoadLightReadSet.Builder(reads);inherited.keepPreviousSamples();
    require(!inherited.build().changed((key,r)->expected.get(key)));
    var included=new RoadLightReadSet.Builder();included.include(reads);
    require(!included.build().changed((key,r)->expected.get(key)));
    for(long key:expected.keySet())require(new RoadLightReadSet.Builder(reads).packed(key,0,false)==expected.get(key));
    long[] original=expected.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
    int originalSwitches=0;long previous=Long.MIN_VALUE;
    for(long key:original){long section=RoadLightReadSet.orderKey(key)>>>12;if(section!=previous){originalSwitches++;previous=section;}}
    require(switches[0]<originalSwitches);
    System.out.println("SectionLightOrderValidation PASS checks="+checks+" inputs="+expected.size()+" sectionTransitions="+originalSwitches+" -> "+switches[0]);
  }
}
