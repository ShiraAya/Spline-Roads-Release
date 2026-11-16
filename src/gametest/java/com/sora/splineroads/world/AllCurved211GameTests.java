package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.core.InterchangePlanner.*;import net.minecraft.core.BlockPos;import net.minecraft.nbt.*;import net.minecraft.gametest.framework.*;
@net.minecraftforge.gametest.GameTestHolder("splineroads_211_allcurves") @net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class AllCurved211GameTests {
 @GameTest(template="empty",templateNamespace="splineroads_211_allcurves",timeoutTicks=30000) public static void directional_t(GameTestHelper h)throws Exception{run(h,Preset.DIRECTIONAL_T,330000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_allcurves",timeoutTicks=30000) public static void y(GameTestHelper h)throws Exception{run(h,Preset.Y,334000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_allcurves",timeoutTicks=30000) public static void turbine(GameTestHelper h)throws Exception{run(h,Preset.TURBINE,338000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_allcurves",timeoutTicks=30000) public static void diamond(GameTestHelper h)throws Exception{run(h,Preset.DIAMOND,342000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_allcurves",timeoutTicks=30000) public static void roundabout(GameTestHelper h)throws Exception{run(h,Preset.ROUNDABOUT,346000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_allcurves",timeoutTicks=30000) public static void spui(GameTestHelper h)throws Exception{run(h,Preset.SPUI,350000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_multicurves",timeoutTicks=30000) public static void directional_five(GameTestHelper h)throws Exception{run(h,Preset.DIRECTIONAL_FIVE,374000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_multicurves",timeoutTicks=30000) public static void directional_six(GameTestHelper h)throws Exception{run(h,Preset.DIRECTIONAL_SIX,378000);}
 static void run(GameTestHelper h,Preset preset,int base)throws Exception{
  var level=h.getLevel();var data=RoadData.get(level);level.getChunkAt(new BlockPos(base,0,0));double ground=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,base,0)+.125;
  int count=preset==Preset.DIRECTIONAL_FIVE?5:preset==Preset.DIRECTIONAL_SIX?6:preset.three?3:4;
  var settings=new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90);var options=new Options(preset,false,1,96,20,5,2,5,false);
  double gap=count<5?InterchangePlanner.minimumDifference(settings,settings,CurvedRoadPlans.planningOptions(options)):16;
  var hosts=new ArrayList<RoadIndex.Built>();var points=new ArrayList<Long>();
  for(int a=0;a<(count+1)/2;a++){
   var pts=new ArrayList<Sample>();double angle=count<5?a*Math.PI/2:a*Math.PI/3;V u=new V(Math.cos(angle),0,Math.sin(angle));int n=a*2+1==count?1800:3600;
   for(int i=0;i<=n;i++){double at=-900+i*.5;V q=u.mul(at).add(u.left().mul(at*at*.00001));V tangent=u.add(u.left().mul(at*.00002)).horizontalUnit();pts.add(new Sample(new V(base+q.x(),ground+((count+1)/2-1-a)*gap,q.z()),tangent.left(),0,9));}
   var mesh=RoadRibbon.mesh(pts,settings);Node start=RoadRibbon.start(mesh),end=RoadRibbon.end(mesh);var pa=Curve201GameTests.marker(level,start);var pb=Curve201GameTests.marker(level,end);points.add(pa.asLong());if(a*2+1<count)points.add(pb.asLong());hosts.add(new RoadIndex.Built(new RoadRecord(UUID.randomUUID(),new UUID(0,0),pa,pb,start,end,settings,false,4).alignment(null,mesh)));
  }
  data.replaceAssembly(level,null,hosts,Set.of(),Set.of(),List.of());var before=new HashSet<>(data.interchanges.keySet());var command=Interchanges.payload(level,null,points.stream().mapToLong(Long::longValue).toArray(),null);h.assertTrue(HostAxes.curved(command),"curve lost in payload");command.put("Options",Interchanges.write(options));
  long time=System.nanoTime();System.out.println("CURVED WORLD CREATE "+preset);try{Interchanges.build(level,null,command);}catch(Exception e){e.printStackTrace();throw e;}System.out.println("CURVED WORLD CREATED "+preset);var id=data.interchanges.keySet().stream().filter(k->!before.contains(k)).findFirst().orElseThrow();
  var saved=data.interchanges.get(id);var edit=Interchanges.payload(level,null,saved.getLongArray("Points"),id);h.assertTrue(HostAxes.curved(edit),"curve lost on reopen");System.out.println("CURVED WORLD REEDIT "+preset);try{Interchanges.build(level,null,edit);}catch(Exception e){e.printStackTrace();throw e;}
  var disk=java.nio.file.Files.createTempFile("sr211-all-curves", ".nbt");try{NbtIo.writeCompressed(data.save(new CompoundTag()),disk.toFile());var restored=RoadData.load(NbtIo.readCompressed(disk.toFile()));h.assertTrue(HostAxes.curved(restored.interchanges.get(id)),"curve lost on disk");h.assertTrue(Interchanges.read(restored.interchanges.get(id).getCompound("Options")).preset()==preset,"preset changed");}finally{java.nio.file.Files.deleteIfExists(disk);}
  h.assertTrue(RoadWorkChunks.heldCount(level)==0,"chunk ticket leak");System.out.println("ALL CURVED WORLD PASS "+preset+" create/reedit/disk ms="+(System.nanoTime()-time)/1e6);h.succeed();
 }
}
