package com.sora.splineroads.client;

import java.util.*;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.*;

/** Road blocks and slabs use the road mesh's four-block world UVs and asphalt tint. */
final class RoadAsphaltModel extends BakedModelWrapper<BakedModel> {
  static final ModelProperty<BlockPos> POSITION=new ModelProperty<>();
  RoadAsphaltModel(BakedModel original){super(original);}
  @Override public ModelData getModelData(BlockAndTintGetter level,BlockPos pos,BlockState state,ModelData input){
    return super.getModelData(level,pos,state,input).derive().with(POSITION,pos.immutable()).build();
  }
  @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random,ModelData data,RenderType layer){
    var quads=super.getQuads(state,side,random,data,layer);var pos=data.get(POSITION);
    return pos==null?quads:align(quads,pos);
  }
  static List<BakedQuad> align(List<BakedQuad> quads,BlockPos pos){
    if(quads.isEmpty())return quads;
    var result=new ArrayList<BakedQuad>(quads.size());
    for(var q:quads){int[] vertices=q.getVertices().clone();var sprite=q.getSprite();
      for(int i=0;i<4;i++){int at=i*8;double x=Float.intBitsToFloat(vertices[at]),y=Float.intBitsToFloat(vertices[at+1]),z=Float.intBitsToFloat(vertices[at+2]);
        double u,v;switch(q.getDirection().getAxis()){
          case Y -> {u=(Math.floorMod(pos.getX(),4)+x)/4;v=(Math.floorMod(pos.getZ(),4)+z)/4;}
          case X -> {u=(Math.floorMod(pos.getZ(),4)+z)/4;v=(Math.floorMod(pos.getY(),4)+y)/4;}
          default -> {u=(Math.floorMod(pos.getX(),4)+x)/4;v=(Math.floorMod(pos.getY(),4)+y)/4;}
        }
        vertices[at+3]=0xffdcdcdc;
        vertices[at+4]=Float.floatToRawIntBits(sprite.getU(u*16));vertices[at+5]=Float.floatToRawIntBits(sprite.getV(v*16));
      }
      result.add(new BakedQuad(vertices,q.getTintIndex(),q.getDirection(),sprite,q.isShade()));
    }return List.copyOf(result);
  }
}
