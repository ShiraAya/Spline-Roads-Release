package com.sora.splineroads.client;
import java.util.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
public final class SidewalkBlockQuads {
  /** Lower only the base vertices; the vanilla cube top, UVs and metadata stay unchanged. */
  public static List<BakedQuad> extend(List<BakedQuad> quads,Double bottom){
    if(bottom==null||bottom>=0||quads.isEmpty())return quads;
    var out=new ArrayList<BakedQuad>(quads.size());
    for(var q:quads){int[] packed=null;var original=q.getVertices();
      for(int i=0;i<4;i++)if(Math.abs(Float.intBitsToFloat(original[i*8+1]))<1e-6){
        if(packed==null)packed=original.clone();packed[i*8+1]=Float.floatToRawIntBits(bottom.floatValue());
      }
      out.add(packed==null?q:new BakedQuad(packed,q.getTintIndex(),q.getDirection(),q.getSprite(),q.isShade(),q.hasAmbientOcclusion()));
    }
    return List.copyOf(out);
  }
}
