package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.TollPlaza.Spec;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/** Bounded labels on the selected immutable preview only; no world/entity scans. */
public final class TollPreview {
  private record Mark(V at,String text,int color){}
  private static List<Mark> marks=List.of();
  static TollPlanner.Plan namedPreview;
  public static void clear(){marks=List.of();namedPreview=null;}
  public static void show(Spec original,TollPlanner.Plan plan){
    namedPreview=plan;var out=new ArrayList<Mark>();var proposed=plan.spec();
    for(boolean a:new boolean[]{true,false}){String name=a?"A":"B";V old=(a?original.a():original.b()).position(),now=(a?proposed.a():proposed.b()).position();double distance=old.distance(now);
      if(distance>.001)out.add(new Mark(old.add(new V(0,3,0)),name+" 原端点 "+coordinates(old),0xFFD1AE));
      out.add(new Mark(now.add(new V(0,3,0)),name+(distance>.001?" 新端点 ":" ")+coordinates(now)+String.format(Locale.ROOT," · 移动 %.1f 格",distance),0xA8FFD2));
    }
    for(var unit:plan.units())out.add(new Mark(unit.center().add(new V(0,plan.spec().config().clearHeight()+2.2,0)),(unit.direction()>0?"A→B":"B→A")+" "+(unit.etc()?"ETC":"人工")+" "+(unit.index()+1),unit.etc()?0xA8FFD2:0xB2DCFF));
    marks=List.copyOf(out);
  }
  private static String coordinates(V p){return String.format(Locale.ROOT,"(%.1f, %.1f, %.1f)",p.x(),p.y(),p.z());}
  public static void render(RenderLevelStageEvent event){
    if(ClientRoads.preview==null||ClientRoads.preview.settings().style()!=com.sora.splineroads.core.RoadGeometry.Style.TOLL)return;
    var mc=Minecraft.getInstance();var camera=event.getCamera().getPosition();var pose=event.getPoseStack();var buffers=mc.renderBuffers().bufferSource();
    for(var mark:marks){V p=mark.at();if(p.distance(new V(camera.x,camera.y,camera.z))>192)continue;
      pose.pushPose();pose.translate(p.x()-camera.x,p.y()-camera.y,p.z()-camera.z);pose.mulPose(event.getCamera().rotation());pose.scale(-.04f,-.04f,.04f);
      mc.font.drawInBatch(mark.text(),-mc.font.width(mark.text())/2f,0,mark.color(),false,pose.last().pose(),buffers,Font.DisplayMode.SEE_THROUGH,0x60000000,LightTexture.FULL_BRIGHT);pose.popPose();
    }
  }
  private TollPreview(){}
}
