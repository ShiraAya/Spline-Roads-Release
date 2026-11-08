package com.sora.splineroads.client;

import java.lang.reflect.Method;

/** Optional Iris/Oculus API bridge. Installed != selected != successfully rendering a pack.
 * No optional classes appear in descriptors, so vanilla/dedicated-server loading is unaffected.
 */
final class ShaderPackState {
  record State(boolean active, boolean shadowPass, String provider) {}
  private static final String[] APIS = {
      "net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"
  };
  private static final class Holder {
    static final Probe PROBE = discover(ShaderPackState.class.getClassLoader(), APIS);
  }
  static final class Probe {
    private final Object instance;
    private final Method active, shadow;
    private final String provider;
    private boolean warned;
    Probe(Object instance, Method active, Method shadow, String provider) {
      this.instance=instance;this.active=active;this.shadow=shadow;this.provider=provider;
    }
    State read() {
      if(active==null)return new State(false,false,provider);
      try {
        boolean inUse=Boolean.TRUE.equals(active.invoke(instance));
        return new State(inUse,inUse&&shadow!=null&&Boolean.TRUE.equals(shadow.invoke(instance)),provider);
      } catch(ReflectiveOperationException|RuntimeException|LinkageError failure) {
        if(!warned){warned=true;System.getLogger("SplineRoads").log(System.Logger.Level.WARNING,
            "Cannot query shader render state; AUTO safely falls back to VBO",failure);}
        return new State(false,false,provider+" unavailable");
      }
    }
  }
  /** Kept package-visible to exercise both API namespaces and a genuinely absent API in tests. */
  static Probe discover(ClassLoader loader,String... names) {
    for(String name:names)try {
      Class<?> api=Class.forName(name,false,loader);
      Object instance=api.getMethod("getInstance").invoke(null);
      Method active=api.getMethod("isShaderPackInUse"),shadow=api.getMethod("isRenderingShadowPass");
      return new Probe(instance,active,shadow,name);
    }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored) {
      // Older Oculus has the coderbot namespace. Missing optional APIs are not startup failures.
    }
    return new Probe(null,null,null,"no Iris/Oculus API");
  }
  private static final class EncodingHolder {
    static final EncodingProbe PROBE=discoverEncoding(ShaderPackState.class.getClassLoader(),
        "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings","net.coderbot.iris.block_rendering.WorldRenderingSettings");
  }
  static final class EncodingProbe {
    final Object instance;final Method method;
    EncodingProbe(Object instance,Method method){this.instance=instance;this.method=method;}
    boolean read(boolean fallback){if(method==null)return fallback;try{return Boolean.TRUE.equals(method.invoke(instance));}catch(ReflectiveOperationException|RuntimeException|LinkageError e){return fallback;}}
  }
  static EncodingProbe discoverEncoding(ClassLoader loader,String... names){
    for(String name:names)try{var type=Class.forName(name,false,loader);return new EncodingProbe(type.getField("INSTANCE").get(null),type.getMethod("shouldUseExtendedVertexFormat"));}
    catch(ReflectiveOperationException|RuntimeException|LinkageError e){}return new EncodingProbe(null,null);
  }
  static boolean extendedVertices(State state){return EncodingHolder.PROBE.read(state.active());}
  static State current(){return Holder.PROBE.read();}
  private ShaderPackState(){}
}
