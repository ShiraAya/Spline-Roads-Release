package com.sora.splineroads.world;
import java.nio.file.*;
import net.minecraft.nbt.*;
public final class FeedbackFix7Inspect {
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");
  System.out.println("ROOT "+root.getAllKeys());
  for(String key:root.getAllKeys())if(!java.util.Set.of("Roads","Terrain","Fills","TerrainOriginal","TerrainFill").contains(key)){
   var value=root.get(key);String text=value.toString();System.out.println("TAG "+key+" "+(text.length()<3000?text:text.substring(0,3000)));
  }
  for(var t:root.getList("Roads",Tag.TAG_COMPOUND)){
   var r=RoadRecord.load((CompoundTag)t);var a=r.start().position();var b=r.end().position();
   if(Math.min(a.x(),b.x())<3700&&Math.max(a.x(),b.x())>2800&&Math.min(a.z(),b.z())<800&&Math.max(a.z(),b.z())>-100)
    System.out.println("NEAR "+r.id()+" a="+r.start()+" b="+r.end()+" style="+r.settings()+" key="+r.settings().style()+" assembly="+r.assembly());
  }
 }
}
