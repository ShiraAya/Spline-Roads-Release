package com.sora.splineroads.world;
import java.io.*;import java.util.*;import net.minecraft.gametest.framework.*;import net.minecraft.nbt.*;import net.minecraftforge.gametest.*;
@GameTestHolder("splineroads_202_compat") @PrefixGameTestTemplate(false)
public final class Legacy202GameTests {
 @GameTest(template="empty",templateNamespace="splineroads_202_compat",timeoutTicks=1200)
 public static void ebiliseReadAndRoundtrip(GameTestHelper h)throws Exception{
  var file=new File(Objects.requireNonNull(System.getProperty("road.ebiliseFixture"), "Explicit isolated legacy fixture required"));
  var root=NbtIo.readCompressed(file).getCompound("data");h.assertTrue(root.getInt("Version")==70,"fixture version");
  var data=RoadData.load(root.copy());h.assertTrue(data.index.roads.size()==8,"all 8 old roads loaded");
  var saved=data.save(new CompoundTag());var again=RoadData.load(saved.copy());
  for(Tag tag:root.getList("Roads",10)){var old=RoadRecord.load((CompoundTag)tag);var next=again.index.roads.get(old.id());h.assertTrue(next!=null,"road missing "+old.id());h.assertTrue(next.record.equals(old),"legacy road altered "+old.id());}
  var logical=new HashMap<UUID,RoadRecord>();for(Tag tag:saved.getList("LogicalStreets",10)){var r=RoadRecord.load((CompoundTag)tag);logical.put(r.id(),r);}h.assertTrue(logical.size()==4,"four logical roads");
  for(Tag tag:root.getList("LogicalStreets",10)){var r=RoadRecord.load((CompoundTag)tag);h.assertTrue(r.equals(logical.get(r.id())),"logical road changed");}
  for(String key:List.of("TerrainOriginalPalette","TerrainFillPalette","SidewalkPalette")){var a=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.block.state.BlockState>();var b=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.block.state.BlockState>();RoadBlockStorage.read(root.getList(key,10),a);RoadBlockStorage.read(saved.getList(key,10),b);h.assertTrue(a.equals(b),"terrain ledger changed "+key);}
  h.assertTrue(new it.unimi.dsi.fastutil.longs.LongOpenHashSet(root.getLongArray("RoadAprons")).equals(new it.unimi.dsi.fastutil.longs.LongOpenHashSet(saved.getLongArray("RoadAprons"))),"aprons changed");
  for(int version:new int[]{69,74}){var bad=root.copy();bad.putInt("Version",version);boolean rejected=false;try{RoadData.load(bad);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"unsupported save accepted");}
  var signs=root.copy();var old=signs.getList("Roads",10).getCompound(0).getCompound("Settings").getCompound("Infrastructure");var list=new ListTag();list.add(new CompoundTag());old.put("Signs",list);boolean rejected=false;try{RoadData.load(signs);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"retired custom sign silently lost");
  System.out.println("SR202 EBILISE PASS 8 roads, 4 logical roads, exact geometry and terrain ledgers, format controls");h.succeed();
 }
 @GameTest(template="empty",templateNamespace="splineroads_202_compat",timeoutTicks=1200)
 public static void unreadableSaveNeverCreatesEmptyDatabase(GameTestHelper h)throws Exception{
  var level=h.getLevel();var previous=RoadData.get(level);var storage=level.getDataStorage();
  var folder=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).resolve("data");
  java.nio.file.Files.createDirectories(folder);var file=folder.resolve("splineroads.dat");byte[] before=java.nio.file.Files.exists(file)?java.nio.file.Files.readAllBytes(file):null;
  var invalid=new CompoundTag();var payload=new CompoundTag();payload.putInt("Version",74);invalid.put("data",payload);
  try{NbtIo.writeCompressed(invalid,file.toFile());byte[] protectedBytes=java.nio.file.Files.readAllBytes(file);storage.set("splineroads",null);
   boolean refused=false;try{RoadData.get(level);}catch(IllegalStateException expected){refused=expected.getMessage().contains("could not be loaded");}h.assertTrue(refused,"unreadable data replaced with empty database");
   h.assertTrue(Arrays.equals(protectedBytes,java.nio.file.Files.readAllBytes(file)),"rejected file changed");
   refused=false;try{RoadData.get(level);}catch(IllegalStateException expected){refused=true;}h.assertTrue(refused,"second request silently creates database");
  }finally{if(before==null)java.nio.file.Files.deleteIfExists(file);else java.nio.file.Files.write(file,before);storage.set("splineroads",previous);}
  h.assertTrue(RoadData.get(level)==previous,"existing state not restored");System.out.println("SR202 REJECTED SAVE GUARD PASS");h.succeed();
 }

 @GameTest(template="empty",templateNamespace="splineroads_202_compat",timeoutTicks=1200)
 public static void restoreTerrainReconcilesFenceConnections(GameTestHelper h){
  var level=h.getLevel();var pos=new net.minecraft.core.BlockPos(250000,80,0);level.getChunkAt(pos);
  for(var d:net.minecraft.core.Direction.values())level.setBlock(pos.relative(d),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
  level.setBlock(pos.below(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
  var fence=net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceBlock.EAST,true);
  var states=new LinkedHashMap<Long,net.minecraft.world.level.block.state.BlockState>();states.put(pos.asLong(),fence);
  RoadData.restoreStreetBlocks(level,states,Map.of());
  h.assertTrue(level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.OAK_FENCE)&&!level.getBlockState(pos).getValue(net.minecraft.world.level.block.FenceBlock.EAST),"restored fence has stale connection");
  // Exact rollback remains a hard refusal for an impossible archived state.
  boolean refused=false;try{RoadData.restorePlannerBlocks(level,new LinkedHashMap<>(Map.of(pos.asLong(),fence)),Map.of());}catch(IllegalStateException expected){refused=true;}h.assertTrue(refused,"rollback silently normalized state");
  System.out.println("SR202 TERRAIN FENCE RESTORE PASS normalized forward, exact rollback");h.succeed();
 }

}
