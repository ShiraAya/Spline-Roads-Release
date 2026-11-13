package com.sora.splineroads.client;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

/** Optional Fabric renderer bridge. No Fabric types link on a Forge-only client. */
final class RoadFabricModels {
  private static final Api API=Api.find();
  static BakedModel wrap(BakedModel base,BakedModel terrain,Function<ModelData,List<BakedQuad>> roads,
      BiFunction<List<BakedQuad>,ModelData,List<BakedQuad>> clip,Predicate<ModelData> covered){
    if(API==null||!API.model.isInstance(base))return terrain;
    var handler=new Model(base,terrain,roads,clip,covered);
    return (BakedModel)Proxy.newProxyInstance(RoadFabricModels.class.getClassLoader(),new Class<?>[]{BakedModel.class,API.model},handler);
  }
  static BakedModel unwrap(BakedModel model){
    if(Proxy.isProxyClass(model.getClass())&&Proxy.getInvocationHandler(model) instanceof Model handler)return handler.base;
    return model;
  }
  private record Clipped(BakedQuad quad,Object material,Direction cull,int tag){}
  private record Model(BakedModel base,BakedModel terrain,Function<ModelData,List<BakedQuad>> roads,
      BiFunction<List<BakedQuad>,ModelData,List<BakedQuad>> clip,Predicate<ModelData> covered) implements InvocationHandler {
    public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
      if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
        case "equals" -> proxy==args[0];case "hashCode" -> System.identityHashCode(proxy);case "toString" -> "SR Fabric terrain bridge["+base+"]";default -> call(method,terrain,args);
      };
      return switch(method.getName()){
        case "isVanillaAdapter" -> false;
        case "emitBlockQuads" -> {emit(args);yield null;}
        case "emitItemQuads" -> call(method,base,args);
        default -> call(method,terrain,args);
      };
    }
    @SuppressWarnings("unchecked") private void emit(Object[] args)throws Throwable{
      Object context=args[4];var state=(BlockState)args[1];
      var data=terrain.getModelData((BlockAndTintGetter)args[0],(BlockPos)args[2],state,(ModelData)call(API.modelData,context));
      var clipped=new ArrayList<Clipped>();
      Object transform=null;
      if(covered.test(data)){
        Object finder=call(API.spriteFinder,null,Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS));
        transform=Proxy.newProxyInstance(RoadFabricModels.class.getClassLoader(),new Class<?>[]{API.transform},(p,m,a)->{
          if(!m.getName().equals("transform"))return m.getName().equals("hashCode")?System.identityHashCode(p):m.getName().equals("equals")?p==a[0]:"SR road coverage";
          Object quad=a[0]; // Contact extrusion also transforms side and bottom faces.
          var sprite=(TextureAtlasSprite)call(API.findSprite,finder,quad);
          var original=(BakedQuad)call(API.bakedQuad,quad,sprite);var pieces=clip.apply(List.of(original),data);
          if(pieces.size()==1&&pieces.get(0)==original)return true;
          Object material=call(API.material,quad);var cull=(Direction)call(API.cullFace,quad);int tag=(int)call(API.tag,quad);
          for(var piece:pieces)clipped.add(new Clipped(piece,material,cull,tag));return false;
        });
        call(API.push,context,transform);
      }
      try{call(API.emitBlock,base,args);}finally{if(transform!=null)call(API.pop,context);}
      Object emitter=call(API.emitter,context);
      for(var piece:clipped){call(API.fromVanilla,emitter,piece.quad(),piece.material(),piece.cull());call(API.setTag,emitter,piece.tag());call(API.emit,emitter);}
      var layer=(RenderType)call(API.layer,context);var quads=roads.apply(data);
      if(quads==null||quads.isEmpty()||layer!=null&&layer!=RenderType.solid())return;
      // Pass an ordinary Forge carrier through the renderer's encoder. It is
      // roads-only, so CTM base quads are never emitted a second time.
      var overlay=new BakedModelWrapper<BakedModel>(terrain){
        @Override public List<BakedQuad> getQuads(BlockState s,Direction face,RandomSource random){return face==null?quads:List.of();}
        @Override public List<BakedQuad> getQuads(BlockState s,Direction face,RandomSource random,ModelData d,RenderType type){return face==null&&(type==null||type==RenderType.solid())?quads:List.of();}
        @Override public ChunkRenderTypeSet getRenderTypes(BlockState s,RandomSource random,ModelData d){return ChunkRenderTypeSet.of(RenderType.solid());}
        @Override public boolean useAmbientOcclusion(){return false;}
      };
      ((Consumer<BakedModel>)call(API.fallback,context)).accept(overlay);
    }
  }
  private static Object call(Method m,Object target,Object... args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
  private static final class Api {
    final Class<?> model,transform;
    final Method emitBlock,modelData,layer,push,pop,emitter,fallback,spriteFinder,findSprite,bakedQuad,lightFace,material,cullFace,tag,fromVanilla,setTag,emit;
    Api()throws ReflectiveOperationException{
      var loader=RoadFabricModels.class.getClassLoader();model=Class.forName("net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel",false,loader);
      var context=Class.forName("net.fabricmc.fabric.api.renderer.v1.render.RenderContext",false,loader);
      transform=Class.forName("net.fabricmc.fabric.api.renderer.v1.render.RenderContext$QuadTransform",false,loader);
      var quad=Class.forName("net.fabricmc.fabric.api.renderer.v1.mesh.QuadView",false,loader);var out=Class.forName("net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter",false,loader);
      var mat=Class.forName("net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial",false,loader);var finder=Class.forName("net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder",false,loader);
      emitBlock=model.getMethod("emitBlockQuads",BlockAndTintGetter.class,BlockState.class,BlockPos.class,Supplier.class,context);
      modelData=context.getMethod("getModelData");layer=context.getMethod("getRenderLayer");push=context.getMethod("pushTransform",transform);pop=context.getMethod("popTransform");emitter=context.getMethod("getEmitter");fallback=context.getMethod("fallbackConsumer");
      spriteFinder=finder.getMethod("get",TextureAtlas.class);findSprite=finder.getMethod("find",quad);bakedQuad=quad.getMethod("toBakedQuad",TextureAtlasSprite.class);lightFace=quad.getMethod("lightFace");material=quad.getMethod("material");cullFace=quad.getMethod("cullFace");tag=quad.getMethod("tag");
      fromVanilla=out.getMethod("fromVanilla",BakedQuad.class,mat,Direction.class);setTag=out.getMethod("tag",int.class);emit=out.getMethod("emit");
    }
    static Api find(){try{return new Api();}catch(ClassNotFoundException absent){return null;}catch(ReflectiveOperationException invalid){throw new IllegalStateException("Unsupported Fabric renderer model API",invalid);}}
  }
}
