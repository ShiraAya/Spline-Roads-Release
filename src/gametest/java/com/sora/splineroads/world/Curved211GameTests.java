package com.sora.splineroads.world;
import java.util.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraftforge.gametest.*;

@GameTestHolder("splineroads_211_curves") @PrefixGameTestTemplate(false)
public final class Curved211GameTests {
 @GameTest(template="empty",templateNamespace="splineroads_211_feedback",timeoutTicks=16000)
 public static void singleCurvedBuildAndReedit(GameTestHelper h)throws Exception{run(h,true,311000);}
 @GameTest(template="empty",templateNamespace="splineroads_211_feedback",timeoutTicks=16000)
 public static void doubleCurvedBuildAndReedit(GameTestHelper h)throws Exception{run(h,false,315000);}
 static void run(GameTestHelper h,boolean single,int base)throws Exception{
  var level=h.getLevel();var data=RoadData.get(level);level.getChunkAt(new BlockPos(base,0,0));double ground=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,base,0)+.125;var owner=new UUID(0,0);var hosts=new ArrayList<RoadRecord>();var points=new ArrayList<Long>();
  System.out.println("SR211 CURVED FIXTURE ground="+ground+" single="+single);
  for(int axis=0;axis<2;axis++){
   var line=new ArrayList<Sample>();int n=axis==1&&single?1800:3600;
   for(int i=0;i<=n;i++){double at=-900+i*.5,x,z,dx,dz,y=ground+(axis==0?10:0);
    if(axis==0){x=at;z=20*(1-at*at/810000);dx=1;dz=-40*at/810000;}
    else{double lateral=12*(1-at*at/810000),slope=-24*at/810000,angle=Math.toRadians(8);x=lateral*Math.cos(angle)-at*Math.sin(angle);z=lateral*Math.sin(angle)+at*Math.cos(angle);dx=slope*Math.cos(angle)-Math.sin(angle);dz=slope*Math.sin(angle)+Math.cos(angle);}
    line.add(new Sample(new V(base+x,y,z),new V(dx,0,dz).horizontalUnit().left(),0,9));
   }
   var settings=new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90);var mesh=RoadRibbon.mesh(line,settings);Node a=RoadRibbon.start(mesh),b=RoadRibbon.end(mesh);var pa=Curve201GameTests.marker(level,a);var pb=Curve201GameTests.marker(level,b);points.add(pa.asLong());if(axis==0||!single)points.add(pb.asLong());
   hosts.add(new RoadRecord(UUID.randomUUID(),owner,pa,pb,a,b,settings,false,4).alignment(null,mesh));
  }
  data.replaceAssembly(level,null,hosts.stream().map(RoadIndex.Built::new).toList(),Set.of(),Set.of(),List.of());
  var before=new HashSet<>(data.interchanges.keySet());var command=Interchanges.payload(level,null,points.stream().mapToLong(Long::longValue).toArray(),null);
  h.assertTrue(HostAxes.curved(command),"curved source path was discarded");
  var o=new Options(single?Preset.TRUMPET:Preset.DOUBLE_TRUMPET,false,1,96,20,5,2,5,false).trumpet(TrumpetStyle.A);command.put("Options",Interchanges.write(o));
  Interchanges.build(level,null,command);UUID id=data.interchanges.keySet().stream().filter(k->!before.contains(k)).findFirst().orElseThrow();
  for(var style:List.of(TrumpetStyle.B,TrumpetStyle.A)){
   var saved=data.interchanges.get(id);var edit=Interchanges.payload(level,null,saved.getLongArray("Points"),id);h.assertTrue(HostAxes.curved(edit),"saved curve became straight");
   edit.put("Options",Interchanges.write(o.trumpet(style)));Interchanges.build(level,null,edit);
   h.assertTrue(Interchanges.read(data.interchanges.get(id).getCompound("Options")).preset()==o.preset(),"preset silently changed");
   h.assertTrue(Interchanges.read(data.interchanges.get(id).getCompound("Options")).trumpetStyle()==style,"turn style not retained");
  }
  var disk=java.nio.file.Files.createTempFile("sr211-curved-", ".nbt");try{NbtIo.writeCompressed(data.save(new CompoundTag()),disk.toFile());var restored=RoadData.load(NbtIo.readCompressed(disk.toFile()));h.assertTrue(HostAxes.curved(restored.interchanges.get(id)),"disk save lost host curves");}finally{java.nio.file.Files.deleteIfExists(disk);}
  h.assertTrue(RoadWorkChunks.heldCount(level)==0,"curve test leaked chunk tickets");System.out.println("SR211 CURVED WORLD PASS "+o.preset()+" create A / edit B / edit A / disk restore");h.succeed();
 }
}
