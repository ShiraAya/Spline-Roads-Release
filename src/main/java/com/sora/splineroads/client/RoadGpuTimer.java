package com.sora.splineroads.client;

import com.sora.splineroads.config.RoadClientConfig;
import java.util.Locale;
import org.lwjgl.opengl.*;

/** Optional GPU timestamps: coexist with vanilla's whole-frame elapsed query, never wait. */
final class RoadGpuTimer {
  private static final int[] starts=new int[4],ends=new int[4];
  private static final boolean[] pending=new boolean[4];
  private static int cursor,active=-1;private static double average;private static boolean measured;
  static void begin(){
    if(active>=0||!RoadClientConfig.PERF_OVERLAY.get()||!GL.getCapabilities().OpenGL33)return;
    for(int i=0;i<starts.length;i++)if(pending[i]&&GL15.glGetQueryObjecti(ends[i],GL15.GL_QUERY_RESULT_AVAILABLE)!=0){
      long end=GL33.glGetQueryObjectui64(ends[i],GL15.GL_QUERY_RESULT),start=GL33.glGetQueryObjectui64(starts[i],GL15.GL_QUERY_RESULT);
      double ms=(end-start)/1e6;average=measured?average*.9+ms*.1:ms;measured=true;pending[i]=false;
    }
    for(int n=0;n<starts.length;n++){
      int i=(cursor+n)%starts.length;if(pending[i])continue;
      if(starts[i]==0){starts[i]=GL15.glGenQueries();ends[i]=GL15.glGenQueries();}
      GL33.glQueryCounter(starts[i],GL33.GL_TIMESTAMP);active=i;cursor=(i+1)%starts.length;return;
    }
  }
  static void end(){if(active>=0){GL33.glQueryCounter(ends[active],GL33.GL_TIMESTAMP);pending[active]=true;active=-1;}}
  static String metrics(){return measured?String.format(Locale.ROOT,"SR road GPU: %.2f ms (async, excludes vanilla chunks/sign text)",average):"SR road GPU: waiting / unsupported";}
  static void reset(){end();for(int i=0;i<starts.length;i++){if(starts[i]!=0){GL15.glDeleteQueries(starts[i]);GL15.glDeleteQueries(ends[i]);}starts[i]=0;ends[i]=0;pending[i]=false;}measured=false;average=0;cursor=0;}
  private RoadGpuTimer(){}
}
