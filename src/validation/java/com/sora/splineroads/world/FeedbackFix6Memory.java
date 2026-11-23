package com.sora.splineroads.world;

import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;

/** Same saved road workload in separate JVMs before/after; excludes Minecraft and GPU memory. */
public final class FeedbackFix6Memory {
  static List<RoadRecord> records;
  static List<RoadIndex.Built> roads;
  static long used(){return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
  static void measure(String stage)throws Exception{
    for(int i=0;i<3;i++){System.gc();Thread.sleep(180);}
    System.out.println("FIX6_MEMORY "+stage+" bytes="+used());
  }
  static List<RoadRecord> load(String path)throws Exception{
    var root=NbtIo.readCompressed(Path.of(path).toFile()).getCompound("data");
    var result=new ArrayList<RoadRecord>();
    for(var t:root.getList("Roads",Tag.TAG_COMPOUND))result.add(RoadRecord.load((CompoundTag)t));
    for(var t:root.getList("Interchanges",Tag.TAG_COMPOUND)){var tag=(CompoundTag)t;System.out.println("FIX6_GROUP "+tag.getUUID("Id")+" "+tag.getCompound("Options")+" nodes="+tag.get("Nodes"));}
    return result;
  }
  public static void main(String[] args)throws Exception{
    measure("empty");records=load(args[0]);measure("records");
    System.out.println("FIX6_DATA roads="+records.size()+" samples="+records.stream().mapToLong(r->r.alignment().size()).sum()+" parts="+records.stream().mapToLong(r->r.structures().size()).sum());
    roads=records.stream().map(RoadIndex.Built::loading).toList();measure("loaded");
    long begin=System.nanoTime();long checksum=0;
    for(var road:roads){var local=road.prepareCollision();var p=road.mesh.samples().get(road.mesh.samples().size()/2).center();checksum+=local.boxes(new com.sora.splineroads.core.RoadRaster.Cell((int)Math.floor(p.x()),(int)Math.floor(p.y()-.1),(int)Math.floor(p.z()))).size();}
    measure("visited");System.out.println("FIX6_VISIT checksum="+checksum+" ms="+(System.nanoTime()-begin)/1e6);
    long again=0;for(var road:roads){var p=road.mesh.samples().get(road.mesh.samples().size()/2).center();again+=road.prepareCollision().boxes(new com.sora.splineroads.core.RoadRaster.Cell((int)Math.floor(p.x()),(int)Math.floor(p.y()-.1),(int)Math.floor(p.z()))).size();}
    if(again!=checksum||checksum==0)throw new AssertionError("collision mismatch");measure("revisited");
    String jcmd=Path.of(System.getProperty("java.home"),"bin","jcmd.exe").toString();
    new ProcessBuilder(jcmd,Long.toString(ProcessHandle.current().pid()),"GC.class_histogram").redirectOutput(Path.of(args[1]).toFile()).start().waitFor();
    System.out.println("FIX6_MEMORY_PASS");
  }
}
