package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;

/** Material data only: every infill is baked in its chunk, with no entity renderer. */
public final class RoadFillEntity extends BlockEntity {
  public static final net.minecraftforge.client.model.data.ModelProperty<BlockState> MATERIAL=new net.minecraftforge.client.model.data.ModelProperty<>();
  public static final net.minecraftforge.client.model.data.ModelProperty<Double> CAP=new net.minecraftforge.client.model.data.ModelProperty<>();
  public static final net.minecraftforge.client.model.data.ModelProperty<java.util.List<com.sora.splineroads.core.RoadRaster.Box>> EDGE=new net.minecraftforge.client.model.data.ModelProperty<>();
  public static final net.minecraftforge.client.model.data.ModelProperty<java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>>> OUTLINE=new net.minecraftforge.client.model.data.ModelProperty<>();
  private record Fill(BlockState material,double cap,java.util.List<com.sora.splineroads.core.RoadRaster.Box> edge,java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>> outline) {}
  private record Collision(Fill fill,VoxelShape raw,VoxelShape road,VoxelShape shape) {}
  private volatile Fill contents=new Fill(Blocks.AIR.defaultBlockState(),1,java.util.List.of(),java.util.List.of());
  private volatile Collision cached;
  public java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>> outline(){return contents.outline();}
  public double cap(){return contents.cap();}
  public java.util.List<com.sora.splineroads.core.RoadRaster.Box> edge(){return contents.edge();}
  @Override public net.minecraftforge.client.model.data.ModelData getModelData(){var fill=contents;return net.minecraftforge.client.model.data.ModelData.builder().with(MATERIAL,fill.material()).with(CAP,fill.cap()).with(EDGE,fill.edge()).with(OUTLINE,fill.outline()).build();}
  public RoadFillEntity(BlockPos pos,BlockState state){super(SplineRoads.FILL_ENTITY.get(),pos,state);}
  public BlockState fill(){return contents.material();}
  /** Stable cheap fallback even if another renderer queries the entity. Never ask collision for bounds. */
  @Override public AABB getRenderBoundingBox(){return new AABB(worldPosition).inflate(.002);}
  public VoxelShape collision(BlockGetter world,BlockPos pos,CollisionContext context,VoxelShape road){
    // Chunk mesh/light workers and the main thread can query/update the same entity.
    // Read each immutable snapshot once; never expose partially cleared shape fields.
    var fill=contents;var previous=cached;
    VoxelShape raw=fill.material().getCollisionShape(world,pos,context);
    if(previous!=null&&previous.fill()==fill&&previous.raw()==raw&&previous.road()==road)return previous.shape();
    VoxelShape clipped=fill.cap()>=1||raw.isEmpty()?raw:Shapes.joinUnoptimized(raw,Shapes.box(0,0,0,1,fill.cap(),1),BooleanOp.AND);
    if(!fill.edge().isEmpty()||!fill.outline().isEmpty()){VoxelShape mask=Shapes.empty();for(var box:fill.edge())mask=Shapes.or(mask,Shapes.box(box.x0(),box.y0(),box.z0(),box.x1(),box.y1(),box.z1()));clipped=Shapes.joinUnoptimized(clipped,mask,BooleanOp.AND);}
    VoxelShape shape=Block.isShapeFullBlock(clipped)?Shapes.block():clipped.isEmpty()?road:road.isEmpty()?clipped:Shapes.joinUnoptimized(road,clipped,BooleanOp.OR);
    // A concurrent update may make this result old, but its complete key prevents
    // it being reused for another material, cap or caller's road collision.
    if(contents==fill)cached=new Collision(fill,raw,road,shape);
    return shape;
  }
  private void refresh(){
    cached=null;
    requestModelDataUpdate();
    if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
  }
  public void fill(BlockState state){fill(state,1);}
  public void fill(BlockState state,double height){
    var index=level==null?null:RoadBlocks.index(level);var nearby=index==null?java.util.List.<RoadIndex.Built>of():index.inChunk(new net.minecraft.world.level.ChunkPos(worldPosition).toLong()).stream().map(index.roads::get).filter(java.util.Objects::nonNull).toList();
    var edge=RailbedTerrainEdge.data(worldPosition,state,nearby);contents=new Fill(state,Math.max(.01,Math.min(1,height)),edge.boxes(),edge.polygons());refresh();setChanged();
  }
  @Override protected void saveAdditional(CompoundTag t){super.saveAdditional(t);var fill=contents;t.put("Fill",NbtUtils.writeBlockState(fill.material()));t.putDouble("TerrainCap",fill.cap());var boxes=new ListTag();for(var box:fill.edge()){var values=new ListTag();for(double v:new double[]{box.x0(),box.y0(),box.z0(),box.x1(),box.y1(),box.z1()})values.add(DoubleTag.valueOf(v));boxes.add(values);}if(!boxes.isEmpty())t.put("TerrainEdge",boxes);var outline=new ListTag();for(var poly:fill.outline()){var points=new ListTag();for(var point:poly){points.add(DoubleTag.valueOf(point.x()));points.add(DoubleTag.valueOf(point.z()));}outline.add(points);}if(!outline.isEmpty())t.put("TerrainOutline",outline);}
  @Override public void load(CompoundTag t){super.load(t);var boxes=new java.util.ArrayList<com.sora.splineroads.core.RoadRaster.Box>();var list=t.getList("TerrainEdge",Tag.TAG_LIST);if(list.size()<=256)for(var item:list){var v=(ListTag)item;if(v.size()!=6)continue;double a=v.getDouble(0),b=v.getDouble(1),c=v.getDouble(2),d=v.getDouble(3),e=v.getDouble(4),f=v.getDouble(5);if(a>=0&&b>=0&&c>=0&&d<=1&&e<=1&&f<=1&&a<d&&b<e&&c<f)boxes.add(new com.sora.splineroads.core.RoadRaster.Box(a,b,c,d,e,f));}var outlines=new java.util.ArrayList<java.util.List<com.sora.splineroads.core.RoadGeometry.V>>();var polygons=t.getList("TerrainOutline",Tag.TAG_LIST);if(polygons.size()<=128)for(var item:polygons){var v=(ListTag)item;if(v.size()<6||v.size()>64||v.size()%2!=0)continue;var points=new java.util.ArrayList<com.sora.splineroads.core.RoadGeometry.V>();for(int i=0;i<v.size();i+=2){double x=v.getDouble(i),z=v.getDouble(i+1);if(!Double.isFinite(x)||!Double.isFinite(z)||x< -1e-7||x>1.0000001||z< -1e-7||z>1.0000001){points.clear();break;}points.add(new com.sora.splineroads.core.RoadGeometry.V(x,0,z));}if(points.size()>2)outlines.add(java.util.List.copyOf(points));}contents=new Fill(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),t.getCompound("Fill")),t.contains("TerrainCap")?Math.max(.01,Math.min(1,t.getDouble("TerrainCap"))):1,java.util.List.copyOf(boxes),java.util.List.copyOf(outlines));refresh();}
  @Override public CompoundTag getUpdateTag(){return saveWithoutMetadata();}
  @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
