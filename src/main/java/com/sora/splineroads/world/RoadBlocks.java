package com.sora.splineroads.world;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.*;

public final class RoadBlocks {
  public static boolean isCollider(BlockState state){return state.getBlock() instanceof Road;}
  public static boolean canInfill(Level level,BlockPos p,BlockState fill) {
    BlockState existing=level.getBlockState(p);RoadIndex i=index(level);
    if(!isCollider(existing)||existing.is(com.sora.splineroads.SplineRoads.FILLED_COLLIDER.get())
        ||existing.getValue(Road.FILL)!=Fill.NONE||i==null||fill.hasBlockEntity()||TunnelAir.isAirSpace(fill)
        ||!Block.isShapeFullBlock(fill.getCollisionShape(level,p)))return false;
    if(gantryCell(i,p))return Shapes.joinIsNotEmpty(Shapes.block(),i.shape(p),BooleanOp.ONLY_FIRST);
    var nearby=i.inChunk(new net.minecraft.world.level.ChunkPos(p).toLong()).stream().map(i.roads::get).filter(java.util.Objects::nonNull).toList();
    if(GroundTerrainJoin.usable(GroundTerrainJoin.cap(p,fill,nearby)))return true;
    if(TunnelTerrainSpace.safe(p,nearby)||TunnelTerrainSpace.exteriorInfill(p,nearby))return Shapes.joinIsNotEmpty(Shapes.block(),i.shape(p),BooleanOp.ONLY_FIRST);
    boolean deck=false;
    for(var id:i.at(p.asLong())){
      var road=i.roads.get(id);var c=road.column(p);
      if(c!=null){deck=true;if(p.getY()+1>c.minTop()+1e-7)return false;}
      else if(level.isClientSide){
        // Client indices rasterize lazily and do not maintain server column maps.
        var boxes=new com.sora.splineroads.core.RoadRaster.Local(road.mesh,java.util.List.of()).boxes(new com.sora.splineroads.core.RoadRaster.Cell(p.getX(),p.getY(),p.getZ()));
        for(var box:boxes){deck=true;if(1>box.y0()+road.record.settings().thickness()+1e-7)return false;}
      }
    }
    boolean walkway=false;
    if(!deck)for(var id:i.at(p.asLong())){
      var road=i.roads.get(id);
      for(var part:road.record.structures())if(part.material().name().startsWith("WALK_")){
        var boxes=com.sora.splineroads.core.RoadRaster.structures(java.util.List.of(part),new com.sora.splineroads.core.RoadRaster.Cell(p.getX(),p.getY(),p.getZ()));
        if(!boxes.isEmpty()&&boxes.values().stream().flatMap(java.util.List::stream).anyMatch(b->b.y1()>=1-1e-7))walkway=true;
      }
    }
    return (deck||walkway)&&Shapes.joinIsNotEmpty(Shapes.block(),i.shape(p),BooleanOp.ONLY_FIRST);
  }
  static boolean gantryCell(RoadIndex index,BlockPos p){
    var cell=new com.sora.splineroads.core.RoadRaster.Cell(p.getX(),p.getY(),p.getZ());
    for(var id:index.at(p.asLong()))if(gantryCell(index.roads.get(id),cell))return true;
    return false;
  }
  static boolean gantryCell(RoadIndex.Built road,com.sora.splineroads.core.RoadRaster.Cell cell){
    var parts=road.record.structures().stream().filter(part->part.material()==com.sora.splineroads.core.RoadStructures.Material.GANTRY_FRAME).toList();
    return !parts.isEmpty()&&!com.sora.splineroads.core.RoadRaster.structures(parts,cell).isEmpty();
  }
  public static Function<Level, RoadIndex> clientIndex = l -> null;

  public static RoadIndex index(BlockGetter world) {
    if (world instanceof net.minecraft.server.level.ServerLevel server)
      return RoadData.get(server).index;
    return world instanceof Level level ? clientIndex.apply(level) : null;
  }

  public enum Fill implements net.minecraft.util.StringRepresentable {
    NONE,
    GRASS_BLOCK,
    DIRT,
    STONE,
    SAND,
    GRAVEL,
    DEEPSLATE,
    COARSE_DIRT,
    PODZOL,
    ANDESITE,
    DIORITE,
    GRANITE,
    STONE_SLAB,
    SMOOTH_STONE_SLAB,
    STONE_BRICK_SLAB,
    COBBLESTONE_SLAB,
    BRICK_SLAB,
    QUARTZ_SLAB,
    OAK_SLAB,
    SPRUCE_SLAB,
    STONE_BRICKS,
    BRICKS;

    public double top() {
      return name().endsWith("_SLAB") ? .5 : 1;
    }

    public String getSerializedName() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    public BlockState state() {
      return this == NONE
          ? Blocks.AIR.defaultBlockState()
          : net.minecraft.core.registries.BuiltInRegistries.BLOCK
              .get(
                  net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                      "minecraft", getSerializedName()))
              .defaultBlockState();
    }

    public static Fill of(BlockState state) {
      for (var f : values()) if (f != NONE && state.equals(f.state())) return f;
      return NONE;
    }
  }

  public static class Road extends Block implements net.minecraft.world.level.block.LiquidBlockContainer {
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty WATERLOGGED =
        net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty PERMEABLE =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("water_permeable");
    @Override public boolean canPlaceLiquid(BlockGetter w,BlockPos p,BlockState s,net.minecraft.world.level.material.Fluid f){
      // PERMEABLE is set only for exterior/open structure cells. SEALED controls
      // shell light blocking; using it as a blanket water ban drained the exterior.
      return s.getValue(PERMEABLE)&&!s.getValue(WATERLOGGED)&&f==net.minecraft.world.level.material.Fluids.WATER;
    }
    @Override public boolean placeLiquid(net.minecraft.world.level.LevelAccessor w,BlockPos p,BlockState s,net.minecraft.world.level.material.FluidState f){
      if(!canPlaceLiquid(w,p,s,f.getType()))return false;
      if(!w.isClientSide()){w.setBlock(p,s.setValue(WATERLOGGED,true),3);w.scheduleTick(p,net.minecraft.world.level.material.Fluids.WATER,net.minecraft.world.level.material.Fluids.WATER.getTickDelay(w));}
      return true;
    }
    @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockState s){
      return s.getValue(WATERLOGGED)?net.minecraft.world.level.material.Fluids.WATER.getSource(false):super.getFluidState(s);
    }
    @Override public BlockState updateShape(BlockState s,net.minecraft.core.Direction direction,BlockState neighbour,net.minecraft.world.level.LevelAccessor w,BlockPos p,BlockPos other){
      if(s.getValue(WATERLOGGED))w.scheduleTick(p,net.minecraft.world.level.material.Fluids.WATER,net.minecraft.world.level.material.Fluids.WATER.getTickDelay(w));
      return super.updateShape(s,direction,neighbour,w,p,other);
    }
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty SEALED =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("sealed");
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty LIT =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("lit");
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Fill> FILL =
        net.minecraft.world.level.block.state.properties.EnumProperty.create("fill", Fill.class);

    public Road() {
      super(
          Properties.of()
              .strength(-1, 3600000)
              .dynamicShape()
              .forceSolidOn() // Dynamic shapes have no cached solid flag; rain heightmaps still need one.
              .noOcclusion()
              .lightLevel(state -> state.getValue(LIT) ? 15 : 0)
              .isViewBlocking((s, w, p) -> false)
              .isSuffocating((s, w, p) -> false)
              .pushReaction(PushReaction.BLOCK));
      registerDefaultState(stateDefinition.any().setValue(FILL, Fill.NONE).setValue(LIT, false).setValue(SEALED,false).setValue(WATERLOGGED,false).setValue(PERMEABLE,false));
    }

    @Override
    protected void createBlockStateDefinition(
        net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> b) {
      b.add(FILL, LIT, SEALED, WATERLOGGED, PERMEABLE);
    }

    @Override
    public float getDestroyProgress(BlockState s, Player player, BlockGetter w, BlockPos p) {
      return s.getValue(FILL) == Fill.NONE
          ? 0
          : s.getValue(FILL).state().getDestroyProgress(player, w, p);
    }

    @Override
    public RenderShape getRenderShape(BlockState s) {
      return RenderShape.MODEL; // Empty unless the client terrain model supplies this cell.
    }

    @Override
    public VoxelShape getCollisionShape(
        BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
      // Preserved full terrain already determines the complete collision cell.
      if (s.getValue(FILL) != Fill.NONE) {
        VoxelShape fill = s.getValue(FILL).state().getCollisionShape(w,p,c);
        if (Block.isShapeFullBlock(fill)) return Shapes.block();
      }
      RoadIndex i = index(w);
      VoxelShape road = i == null ? Shapes.block() : SidewalkBlockContact.collision(w,p,i,i.shape(p));
      // A retained proxy with no current geometry is empty, not an invisible cube.
      if (s.getValue(FILL) == Fill.NONE) return road;
      VoxelShape fill = s.getValue(FILL).state().getCollisionShape(w, p, c);
      return Block.isShapeFullBlock(fill) ? Shapes.block() : Shapes.joinUnoptimized(fill, road,BooleanOp.OR);
    }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
      return getCollisionShape(s, w, p, c);
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState s, BlockGetter w, BlockPos p) {
      return Shapes.empty();
    }

    @Override
    public VoxelShape getVisualShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
      return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState s, BlockGetter w, BlockPos p) {
      return !s.getValue(SEALED);
    }

    @Override public int getLightBlock(BlockState s,BlockGetter w,BlockPos p){return s.getValue(SEALED)?15:0;}
  }

  public static class FilledRoad extends Road implements EntityBlock {
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new RoadFillEntity(p,s);}
    @Override public RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    @Override public VoxelShape getCollisionShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext c){
      VoxelShape road=super.getCollisionShape(s,w,p,c);
      return w.getBlockEntity(p) instanceof RoadFillEntity entity?entity.collision(w,p,c,road):road;
    }
    @Override public float getDestroyProgress(BlockState s,Player player,BlockGetter w,BlockPos p){
      return w.getBlockEntity(p) instanceof RoadFillEntity fill?fill.fill().getDestroyProgress(player,w,p):0;
    }
  }

  public static class Node extends BaseEntityBlock {
    private static final VoxelShape MARKER = Block.box(3, 0, 3, 13, 2, 13);

    public Node() {
      super(
          Properties.of()
              .strength(1.5f)
              .dynamicShape()
              .noOcclusion()
              .isSuffocating((s, w, p) -> false)
              .pushReaction(PushReaction.BLOCK));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
      return new NodeEntity(p, s);
    }

    @Override
    public RenderShape getRenderShape(BlockState s) {
      return RenderShape.MODEL; // Marker visibility remains controlled by NodeRenderer.
    }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
      boolean holding =
          c instanceof EntityCollisionContext entity
              && entity.getEntity() instanceof LivingEntity who
              && (RoadTool.showsNodes(who.getMainHandItem())
                  || RoadTool.showsNodes(who.getOffhandItem()));
      return holding ? Shapes.or(MARKER, getCollisionShape(s, w, p, c)) : Shapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(
        BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
      RoadIndex i = index(w);
      return i == null ? Shapes.empty() : i.shape(p);
    }

    @Override
    public void setPlacedBy(Level w, BlockPos p, BlockState s, LivingEntity who, ItemStack stack) {
      if (w.getBlockEntity(p) instanceof NodeEntity n) {
        n.owner = who == null ? null : who.getUUID();
        n.yaw = who == null ? 0 : who.getYRot();
        n.setChanged();
        w.sendBlockUpdated(p, s, s, 3);
      }
    }

    @Override
    public float getDestroyProgress(BlockState s, Player player, BlockGetter w, BlockPos p) {
      RoadIndex i = index(w);
      if (i != null
          && i.roads.values().stream()
              .anyMatch(r -> r.record.a().equals(p) || r.record.b().equals(p))) return 0;
      return super.getDestroyProgress(s, player, w, p);
    }
  }

  private RoadBlocks() {}
}
