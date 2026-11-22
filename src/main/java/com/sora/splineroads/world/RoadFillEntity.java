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
  public static final net.minecraftforge.client.model.data.ModelProperty<Boolean> CUTOUT=new net.minecraftforge.client.model.data.ModelProperty<>();
  public static final net.minecraftforge.client.model.data.ModelProperty<java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>>> SURFACES=new net.minecraftforge.client.model.data.ModelProperty<>();
  record Fill(BlockState material,double cap,boolean cutout,java.util.List<com.sora.splineroads.core.RoadRaster.Box> edge,java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>> outline,java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>> surfaces) {
    Fill(BlockState material,double cap,boolean cutout,java.util.List<com.sora.splineroads.core.RoadRaster.Box> edge,java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>> outline){this(material,cap,cutout,edge,outline,java.util.List.of());}
  }
  private record Collision(Fill fill,VoxelShape raw,VoxelShape road,VoxelShape shape) {}
  private volatile Fill contents=new Fill(Blocks.AIR.defaultBlockState(),1,false,java.util.List.of(),java.util.List.of());
  private volatile Collision cached;
  Fill snapshot(){return contents;}
  public java.util.List<java.util.List<com.sora.splineroads.core.RoadGeometry.V>> outline(){return contents.outline();}
  public double cap(){return contents.cap();}
  public java.util.List<com.sora.splineroads.core.RoadRaster.Box> edge(){return contents.edge();}
  @Override public net.minecraftforge.client.model.data.ModelData getModelData(){var fill=contents;return net.minecraftforge.client.model.data.ModelData.builder().with(MATERIAL,fill.material()).with(CAP,fill.cap()).with(EDGE,fill.edge()).with(OUTLINE,fill.outline()).with(CUTOUT,fill.cutout()).with(SURFACES,fill.surfaces()).build();}
  public RoadFillEntity(BlockPos pos,BlockState state){super(SplineRoads.FILL_ENTITY.get(),pos,state);}
  /** Material/mesh data has no comparator output. Dirty the chunk without
   * asking every adjacent dynamic road for a complete collision union. */
  @Override public void setChanged(){if(level!=null)level.blockEntityChanged(worldPosition);}
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
    if(fill.cutout()||!fill.edge().isEmpty()||!fill.outline().isEmpty()){
      var mask=RoadCollisionShape.build(fill.edge().stream().map(b->new com.sora.splineroads.core.RoadRaster.Box(b.x0(),b.y0()*fill.cap(),b.z0(),b.x1(),b.y1()*fill.cap(),b.z1())).toList());
      clipped=Block.isShapeFullBlock(raw)?mask:Shapes.joinUnoptimized(clipped,mask,BooleanOp.AND);
    }
    VoxelShape shape=Block.isShapeFullBlock(clipped)?Shapes.block():clipped.isEmpty()?road:road.isEmpty()?clipped:Shapes.joinUnoptimized(road,clipped,BooleanOp.OR);
    // A concurrent update may make this result old, but its complete key prevents
    // it being reused for another material, cap or caller's road collision.
    if(contents==fill)cached=new Collision(fill,raw,road,shape);
    return shape;
  }
  private void refresh(){
    cached=null;
    requestModelDataUpdate();
    // This is an entity-data update with the same state on both sides. Vanilla
    // sendBlockUpdated computes two identical dynamic collision unions solely
    // to decide whether to invalidate mob paths. Queue the same client update
    // directly; collision remains lazy and observes the new immutable Fill.
    if(level instanceof net.minecraft.server.level.ServerLevel server)server.getChunkSource().blockChanged(worldPosition);
    else if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
  }
  public void fill(BlockState state){fill(state,1);}
  public boolean matches(BlockState state,double height,java.util.List<RoadIndex.Built> roads){
    return contents.equals(resolve(worldPosition,state,height,roads));
  }
  boolean matches(Fill prepared){return contents.equals(prepared);}
  void fill(Fill prepared){contents=prepared;refresh();setChanged();}
  static Fill resolve(BlockPos pos,BlockState state,double height,java.util.List<RoadIndex.Built> roads){
    var edge=RailbedTerrainEdge.data(pos,state,roads);double cap=Math.max(.01,Math.min(1,height));
    var mask=RoadInfillMask.cut(pos,cap,roads,edge.boxes());
    var cell=new com.sora.splineroads.core.RoadRaster.Cell(pos.getX(),pos.getY(),pos.getZ());
    var parts=mask.clipped()?roads.stream().flatMap(r->r.prepareCollision().structureParts(cell,p->p.material()==com.sora.splineroads.core.RoadStructures.Material.TUNNEL||com.sora.splineroads.core.RoadSidewalks.smoothPart(p)).stream()).toList():java.util.List.<com.sora.splineroads.core.RoadStructures.Part>of();
    var surfaces=mask.clipped()?com.sora.splineroads.core.TerrainSolidGeometry.retain(new com.sora.splineroads.core.RoadGeometry.V(pos.getX(),pos.getY(),pos.getZ()),cap,edge.polygons(),parts):java.util.List.<java.util.List<com.sora.splineroads.core.RoadGeometry.V>>of();
    return new Fill(state,cap,mask.clipped(),mask.boxes(),mask.clipped()?java.util.List.of():edge.polygons(),surfaces);
  }
  public void fill(BlockState state,double height){
    var index=level==null?null:RoadBlocks.index(level);var nearby=index==null?java.util.List.<RoadIndex.Built>of():index.inChunk(new net.minecraft.world.level.ChunkPos(worldPosition).toLong()).stream().map(index.roads::get).filter(java.util.Objects::nonNull).toList();
    fill(resolve(worldPosition,state,height,nearby));
  }
  @Override protected void saveAdditional(CompoundTag t){super.saveAdditional(t);var fill=contents;t.put("Fill",NbtUtils.writeBlockState(fill.material()));t.putDouble("TerrainCap",fill.cap());t.putBoolean("TerrainCutout",fill.cutout());var boxes=new ListTag();for(var box:fill.edge()){var values=new ListTag();for(double v:new double[]{box.x0(),box.y0(),box.z0(),box.x1(),box.y1(),box.z1()})values.add(DoubleTag.valueOf(v));boxes.add(values);}if(!boxes.isEmpty())t.put("TerrainEdge",boxes);var outline=new ListTag();for(var poly:fill.outline()){var points=new ListTag();for(var point:poly){points.add(DoubleTag.valueOf(point.x()));points.add(DoubleTag.valueOf(point.z()));}outline.add(points);}if(!outline.isEmpty())t.put("TerrainOutline",outline);
    var surfaces=new ListTag();for(var face:fill.surfaces()){var values=new ListTag();for(var v:face)for(double n:new double[]{v.x(),v.y(),v.z()})values.add(DoubleTag.valueOf(n));surfaces.add(values);}if(!surfaces.isEmpty())t.put("TerrainSurfaces",surfaces);
  }
  @Override public void load(CompoundTag t){super.load(t);var boxes=new java.util.ArrayList<com.sora.splineroads.core.RoadRaster.Box>();var list=t.getList("TerrainEdge",Tag.TAG_LIST);if(list.size()<=256)for(var item:list){var v=(ListTag)item;if(v.size()!=6)continue;double a=v.getDouble(0),b=v.getDouble(1),c=v.getDouble(2),d=v.getDouble(3),e=v.getDouble(4),f=v.getDouble(5);if(a>=0&&b>=0&&c>=0&&d<=1&&e<=1&&f<=1&&a<d&&b<e&&c<f)boxes.add(new com.sora.splineroads.core.RoadRaster.Box(a,b,c,d,e,f));}var outlines=new java.util.ArrayList<java.util.List<com.sora.splineroads.core.RoadGeometry.V>>();var polygons=t.getList("TerrainOutline",Tag.TAG_LIST);if(polygons.size()<=128)for(var item:polygons){var v=(ListTag)item;if(v.size()<6||v.size()>64||v.size()%2!=0)continue;var points=new java.util.ArrayList<com.sora.splineroads.core.RoadGeometry.V>();for(int i=0;i<v.size();i+=2){double x=v.getDouble(i),z=v.getDouble(i+1);if(!Double.isFinite(x)||!Double.isFinite(z)||x< -1e-7||x>1.0000001||z< -1e-7||z>1.0000001){points.clear();break;}points.add(new com.sora.splineroads.core.RoadGeometry.V(x,0,z));}if(points.size()>2)outlines.add(java.util.List.copyOf(points));}contents=new Fill(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),t.getCompound("Fill")),t.contains("TerrainCap")?Math.max(.01,Math.min(1,t.getDouble("TerrainCap"))):1,t.getBoolean("TerrainCutout"),java.util.List.copyOf(boxes),java.util.List.copyOf(outlines));
    var surfaces=new java.util.ArrayList<java.util.List<com.sora.splineroads.core.RoadGeometry.V>>();var surfaceTag=t.getList("TerrainSurfaces",Tag.TAG_LIST);
    if(surfaceTag.size()<=2048)for(var item:surfaceTag){var v=(ListTag)item;if(v.size()<9||v.size()>192||v.size()%3!=0)continue;var face=new java.util.ArrayList<com.sora.splineroads.core.RoadGeometry.V>();
      for(int i=0;i<v.size();i+=3){double x=v.getDouble(i),y=v.getDouble(i+1),z=v.getDouble(i+2);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||x<0||y<0||z<0||x>1||y>1||z>1){face.clear();break;}face.add(new com.sora.splineroads.core.RoadGeometry.V(x,y,z));}if(face.size()>=3)surfaces.add(java.util.List.copyOf(face));}
    contents=new Fill(contents.material(),contents.cap(),contents.cutout(),contents.edge(),contents.outline(),java.util.List.copyOf(surfaces));refresh();
  }
  @Override public CompoundTag getUpdateTag(){return saveWithoutMetadata();}
  @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
