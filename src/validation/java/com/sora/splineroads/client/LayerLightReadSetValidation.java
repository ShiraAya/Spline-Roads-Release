package com.sora.splineroads.client;

/** Independent light changes must remain observable when only one layer is rebuilt. */
public final class LayerLightReadSetValidation {
  private static int checks;
  private static void require(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}
  private static RoadLightReadSet layer(long position,int value){var b=new RoadLightReadSet.Builder();b.packed(position,value);return b.build();}
  private static RoadLightReadSet union(RoadLightReadSet a,RoadLightReadSet b){return RoadLightReadSet.union(a,b);}
  public static void main(String[] args)throws Exception{
    var surface=layer(10,0x00f00000);var detail=layer(20,0x00f00000);
    var complete=union(surface,detail);
    var changed=complete.cursor();changed.advance((position,requested)->position==20?0x00f000e0:0x00f00000,1);
    require(!changed.complete(),"bounded poll must retain progress");
    changed.advance((position,requested)->position==20?0x00f000e0:0x00f00000,1);
    require(changed.changed()&&changed.located()&&changed.changedPosition()==20,"locate changed detail light");
    require(!surface.contains(changed.changedPosition())&&detail.contains(changed.changedPosition()),"surface stays reusable");
    var updated=union(surface,layer(20,0x00f000e0));
    require(!updated.changed((position,requested)->position==20?0x00f000e0:0x00f00000),"partial publication records the new light");
    require(updated.changed((position,requested)->position==10?0x00e00000:0x00f000e0),"later surface change stays observable");
    var both=complete.cursor();both.advance((position,requested)->0x00e00000,100);
    require(both.changedPosition()==10,"first of two simultaneous changes");
    var second=union(layer(10,0x00e00000),detail).cursor();second.advance((position,requested)->0x00e00000,100);
    require(second.changedPosition()==20,"unrebuilt layer cannot be forgotten");
    var shared=layer(10,0x00f00000);require(shared.contains(10)&&surface.contains(10),"shared dependency invalidates both layers");
    var conflict=union(surface,layer(10,0x00e00000)).cursor();
    require(conflict.changed()&&!conflict.located(),"conflicting snapshots require a full refresh");
    var opaque=new RoadLightReadSet.Builder();opaque.opaque(30,true);opaque.packed(30,0x00f00000);
    var opaqueSet=opaque.build();
    require(!opaqueSet.changed((p,r)->RoadLightReadSet.UNAVAILABLE_OPAQUE|RoadLightReadSet.UNAVAILABLE_PACKED),"unloaded probes do not invalidate");
    require(opaqueSet.changed((p,r)->RoadLightReadSet.UNAVAILABLE_PACKED),"available opacity still invalidates");
    var fallback=new RoadLightReadSet.Builder(detail);require(fallback.packed(20,0,false)==0x00f00000,"layer fallback keeps its last visible sample");
    var retained=union(surface,fallback.build());require(!retained.changed((p,r)->0x00f00000),"fallback is retained in union");
    var random=new java.util.Random(2111414);
    var positions=RoadLightReadSet.class.getDeclaredField("positions");positions.setAccessible(true);
    var values=RoadLightReadSet.class.getDeclaredField("values");values.setAccessible(true);
    var inconsistent=RoadLightReadSet.class.getDeclaredField("inconsistent");inconsistent.setAccessible(true);
    for(int trial=0;trial<2000;trial++){
      var a=new RoadLightReadSet.Builder();var b=new RoadLightReadSet.Builder();
      for(var builder:java.util.List.of(a,b))for(int i=0,n=random.nextInt(200);i<n;i++){
        long position=random.nextInt(100)-50;
        if(random.nextBoolean())builder.opaque(position,random.nextBoolean());
        else builder.packed(position,(random.nextInt(16)<<4)|(random.nextInt(16)<<20));
      }
      var sa=a.build();var sb=b.build();var merged=union(sa,sb);
      var reference=new RoadLightReadSet.Builder();reference.include(sa);reference.include(sb);var expected=reference.build();
      require(java.util.Arrays.equals((long[])positions.get(merged),(long[])positions.get(expected)),"sorted union positions");
      require(java.util.Arrays.equals((int[])values.get(merged),(int[])values.get(expected)),"union values and request masks");
      require(inconsistent.getBoolean(merged)==inconsistent.getBoolean(expected),"union conflict propagation");
    }
    System.out.println("PASS layer lighting dependency checks="+checks);
  }
}
