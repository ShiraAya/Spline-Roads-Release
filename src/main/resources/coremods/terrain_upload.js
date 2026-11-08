/* Forge 1.20.1 upload boundaries. Optional Embeddium targets are never linked by
 * the Java adapter. A changed target shape fails with an explicit diagnostic;
 * silently claiming a CPU build as an uploaded chunk is not a safe fallback. */
var ASMAPI=Java.type('net.minecraftforge.coremod.api.ASMAPI');
var O=Java.type('org.objectweb.asm.Opcodes');
var InsnList=Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode=Java.type('org.objectweb.asm.tree.InsnNode');
var VarInsnNode=Java.type('org.objectweb.asm.tree.VarInsnNode');
var FieldInsnNode=Java.type('org.objectweb.asm.tree.FieldInsnNode');
var MethodInsnNode=Java.type('org.objectweb.asm.tree.MethodInsnNode');
var MethodNode=Java.type('org.objectweb.asm.tree.MethodNode');
var LabelNode=Java.type('org.objectweb.asm.tree.LabelNode');
var JumpInsnNode=Java.type('org.objectweb.asm.tree.JumpInsnNode');
var TypeInsnNode=Java.type('org.objectweb.asm.tree.TypeInsnNode');
var TryCatchBlockNode=Java.type('org.objectweb.asm.tree.TryCatchBlockNode');
var ROOT='net/minecraft/client/renderer/chunk/ChunkRenderDispatcher';
var CHUNK=ROOT+'$RenderChunk',TASK=CHUNK+'$RebuildTask';
var HOOK='com/sora/splineroads/client/RoadTerrainHandoff';
var EMB='com/sora/splineroads/client/RoadEmbeddiumHandoff';
var SODIUM='me/jellysquid/mods/sodium/client/render/chunk/';
function need(ok,message){if(!ok)throw new Error('SplineRoads terrain upload hook: '+message);}
function aload(i){return new VarInsnNode(O.ALOAD,i);}
function call(owner,name,desc){return new MethodInsnNode(O.INVOKESTATIC,owner,name,desc,false);}
function matches(name,official,srg){return name===official||name===srg||name===ASMAPI.mapMethod(srg);}
function method(node,official,srg,desc){
  var found=null;for(var i=0;i<node.methods.size();i++){var m=node.methods.get(i);if(matches(m.name,official,srg)&&m.desc===desc){need(found===null,'duplicate '+official);found=m;}}
  need(found!==null,node.name+' missing '+official+desc);return found;
}
function field(node,official,srg,desc){
  for(var i=0;i<node.fields.size();i++){var f=node.fields.get(i);if((f.name===official||f.name===srg||f.name===ASMAPI.mapField(srg))&&f.desc===desc)return f.name;}
  throw new Error('SplineRoads terrain upload hook: missing field '+node.name+'.'+official);
}
function accessor(node,iface,name,fieldName,desc){
  node.interfaces.add(iface);var m=new MethodNode(O.ACC_PUBLIC|O.ACC_FINAL,name,'()'+desc,null,null);
  m.instructions.add(aload(0));m.instructions.add(new FieldInsnNode(O.GETFIELD,node.name,fieldName,desc));m.instructions.add(new InsnNode(O.ARETURN));m.maxStack=1;m.maxLocals=1;node.methods.add(m);
}
function target(name,transform){return {target:{type:'CLASS',name:name.replace(/\//g,'.')},transformer:transform};}
function finallyAbort(m,start){
  var end=new LabelNode(),handler=new LabelNode(),local=m.maxLocals;m.maxLocals++;
  m.instructions.add(end);m.instructions.add(handler);m.instructions.add(new VarInsnNode(O.ASTORE,local));m.instructions.add(aload(0));m.instructions.add(call(HOOK,'abort','(Ljava/lang/Object;)V'));m.instructions.add(aload(local));m.instructions.add(new InsnNode(O.ATHROW));
  m.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,'java/lang/Throwable'));
}
function vanillaTask(node){
  var run=method(node,'doTask','m_5869_','(Lnet/minecraft/client/renderer/ChunkBufferBuilderPack;)Ljava/util/concurrent/CompletableFuture;');
  var outer=field(node,'this$1','f_112859_','L'+CHUNK+';');
  var instructions=run.instructions.toArray(),cancel=null;
  for(var i=0;i<instructions.length;i++)if(instructions[i].getOpcode()===O.GETFIELD&&instructions[i].desc==='Ljava/util/concurrent/atomic/AtomicBoolean;'){cancel=instructions[i];break;}
  need(cancel!==null,'task cancellation field');
  var upload=method(node,'lambda$doTask$0','m_234479_','(Ljava/util/List;L'+ROOT+'$CompiledChunk;Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V');
  var ui=upload.instructions.toArray(),dispatcher=null,uploadSite=null;
  for(var i=0;i<ui.length;i++){
    if(ui[i].getOpcode()===O.GETFIELD&&ui[i].desc==='L'+ROOT+';')dispatcher=ui[i];
    if(ui[i].getOpcode()===O.INVOKEVIRTUAL&&ui[i].owner===ROOT&&matches(ui[i].name,'uploadChunkLayer','m_234450_')){need(uploadSite===null,'duplicate upload');uploadSite=ui[i];}
  }
  need(dispatcher!==null&&uploadSite!==null,'vanilla upload allocation boundary');
  var head=new InsnList(),start=new LabelNode();
  head.add(aload(0));head.add(aload(0));head.add(new FieldInsnNode(O.GETFIELD,node.name,outer,'L'+CHUNK+';'));
  head.add(aload(0));head.add(new FieldInsnNode(O.GETFIELD,cancel.owner,cancel.name,cancel.desc));
  head.add(aload(0));head.add(new FieldInsnNode(O.GETFIELD,node.name,outer,'L'+CHUNK+';'));head.add(new FieldInsnNode(O.GETFIELD,dispatcher.owner,dispatcher.name,dispatcher.desc));
  head.add(call(HOOK,'beginVanilla','(Ljava/lang/Object;L'+CHUNK+';Ljava/util/concurrent/atomic/AtomicBoolean;L'+ROOT+';)V'));head.add(start);run.instructions.insert(head);
  var handles=0;
  for(var i=0;i<instructions.length;i++){
    var insn=instructions[i];
    if(insn.getOpcode()===O.ARETURN){var tail=new InsnList();tail.add(aload(0));tail.add(call(HOOK,'endVanilla','(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;'));run.instructions.insertBefore(insn,tail);}
    if(insn.getOpcode()===O.INVOKEVIRTUAL&&insn.owner==='java/util/concurrent/CompletableFuture'&&insn.name==='handle'){
      run.instructions.insertBefore(insn,aload(0));run.instructions.set(insn,call(HOOK,'completeVanilla','(Ljava/util/concurrent/CompletableFuture;Ljava/util/function/BiFunction;Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;'));handles++;
    }
  }
  need(handles===1,'one completion chain');finallyAbort(run,start);
  var args=new InsnList();args.add(aload(0));args.add(aload(3));upload.instructions.insertBefore(uploadSite,args);
  upload.instructions.set(uploadSite,call(HOOK,'uploadVanilla','(L'+ROOT+';Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;Lcom/mojang/blaze3d/vertex/VertexBuffer;Ljava/lang/Object;Lnet/minecraft/client/renderer/RenderType;)Ljava/util/concurrent/CompletableFuture;'));
  var finish=method(node,'lambda$doTask$1','m_234472_','(L'+ROOT+'$CompiledChunk;Ljava/util/List;Ljava/lang/Throwable;)L'+ROOT+'$ChunkTaskResult;');
  var guard=new InsnList();guard.add(aload(0));guard.add(aload(3));guard.add(call(HOOK,'beforeCommit','(Ljava/lang/Object;Ljava/lang/Throwable;)V'));finish.instructions.insert(guard);
  var fi=finish.instructions.toArray(),sets=0;
  for(var i=0;i<fi.length;i++)if(fi[i].getOpcode()===O.INVOKEVIRTUAL&&fi[i].owner==='java/util/concurrent/atomic/AtomicReference'&&fi[i].name==='set'){
    var yes=new LabelNode(),after=new InsnList();after.add(new JumpInsnNode(O.IFNE,yes));after.add(new FieldInsnNode(O.GETSTATIC,ROOT+'$ChunkTaskResult','CANCELLED','L'+ROOT+'$ChunkTaskResult;'));after.add(new InsnNode(O.ARETURN));after.add(yes);
    finish.instructions.insertBefore(fi[i],aload(0));finish.instructions.insert(fi[i],after);finish.instructions.set(fi[i],call(HOOK,'commitVanilla','(Ljava/util/concurrent/atomic/AtomicReference;Ljava/lang/Object;Ljava/lang/Object;)Z'));sets++;
  }
  need(sets===1,'one compiled metadata publication');return node;
}
function vanillaChunk(node){
  accessor(node,'com/sora/splineroads/client/RoadChunkBuffers','splineroads$buffers',field(node,'buffers','f_112790_','Ljava/util/Map;'),'Ljava/util/Map;');
  var reset=method(node,'reset','m_112846_','()V'),head=new InsnList();head.add(aload(0));head.add(call(HOOK,'release','(Ljava/lang/Object;)V'));reset.instructions.insert(head);return node;
}
function embTask(node){
  var desc='(L'+SODIUM+'compile/ChunkBuildContext;Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)L'+SODIUM+'compile/ChunkBuildOutput;';
  var run=method(node,'execute','execute',desc),original=run.instructions.toArray(),start=new LabelNode(),head=new InsnList();
  head.add(aload(0));head.add(aload(0));head.add(new FieldInsnNode(O.GETFIELD,node.name,field(node,'render','render','L'+SODIUM+'RenderSection;'),'L'+SODIUM+'RenderSection;'));
  head.add(aload(2));head.add(call(EMB,'begin','(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V'));head.add(start);run.instructions.insert(head);
  for(var i=0;i<original.length;i++)if(original[i].getOpcode()===O.ARETURN){var tail=new InsnList();tail.add(aload(0));tail.add(call(EMB,'built','(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;'));tail.add(new TypeInsnNode(O.CHECKCAST,SODIUM+'compile/ChunkBuildOutput'));run.instructions.insertBefore(original[i],tail);}
  finallyAbort(run,start);return node;
}
function embFilter(node){
  var m=method(node,'filterChunkBuildResults','filterChunkBuildResults','(Ljava/util/ArrayList;)Ljava/util/List;'),insns=m.instructions.toArray(),count=0;
  for(var i=0;i<insns.length;i++)if(insns[i].getOpcode()===O.ARETURN){var tail=new InsnList();tail.add(aload(0));tail.add(call(EMB,'filter','(Ljava/util/List;Ljava/util/ArrayList;)Ljava/util/List;'));m.instructions.insertBefore(insns[i],tail);count++;}
  need(count===1,'Embeddium result filter');
  var collect=method(node,'collectChunkBuildResults','collectChunkBuildResults','()Ljava/util/ArrayList;'),ci=collect.instructions.toArray();count=0;
  for(var i=0;i<ci.length;i++)if(ci[i].getOpcode()===O.ARETURN){var tail=new InsnList();tail.add(aload(0));tail.add(call(EMB,'collect','(Ljava/util/ArrayList;Ljava/lang/Object;)Ljava/util/ArrayList;'));collect.instructions.insertBefore(ci[i],tail);count++;}
  need(count===1,'Embeddium native result ownership');
  var submit=method(node,'submitRebuildTasks','submitRebuildTasks','(L'+SODIUM+'compile/executor/ChunkJobCollector;L'+SODIUM+'ChunkUpdateType;)V'),si=submit.instructions.toArray();count=0;
  for(var i=0;i<si.length;i++)if(si[i].getOpcode()===O.INVOKESPECIAL&&si[i].owner===SODIUM+'compile/ChunkBuildOutput'&&si[i].name==='<init>'){
    var tag=new InsnList();tag.add(call(EMB,'empty','(Ljava/lang/Object;)Ljava/lang/Object;'));tag.add(new TypeInsnNode(O.CHECKCAST,SODIUM+'compile/ChunkBuildOutput'));submit.instructions.insert(si[i],tag);count++;
  }
  need(count===1,'Embeddium native empty-section result identity');return node;
}
function embUpload(node){
  var disposal=method(node,'delete','delete','(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;)V'),release=new InsnList();
  release.add(aload(0));release.add(call(EMB,'releaseRegions','(Ljava/lang/Object;)V'));disposal.instructions.insert(release);
  var m=method(node,'uploadMeshes','uploadMeshes','(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V'),insns=m.instructions.toArray(),count=0;
  var lock=m.maxLocals++,failure=m.maxLocals++,start=new LabelNode(),end=new LabelNode(),handler=new LabelNode(),head=new InsnList();
  head.add(call(EMB,'uploadLock','()Ljava/lang/Object;'));head.add(new VarInsnNode(O.ASTORE,lock));head.add(aload(lock));head.add(new InsnNode(O.MONITORENTER));head.add(start);head.add(aload(2));head.add(call(EMB,'beforeUpload','(Ljava/util/Collection;)V'));m.instructions.insert(head);
  for(var i=0;i<insns.length;i++)if(insns[i].getOpcode()===O.RETURN){var tail=new InsnList();tail.add(aload(2));tail.add(call(EMB,'uploaded','(Ljava/util/Collection;)V'));tail.add(aload(lock));tail.add(new InsnNode(O.MONITOREXIT));m.instructions.insertBefore(insns[i],tail);count++;}
  m.instructions.add(end);m.instructions.add(handler);m.instructions.add(new VarInsnNode(O.ASTORE,failure));m.instructions.add(aload(lock));m.instructions.add(new InsnNode(O.MONITOREXIT));m.instructions.add(aload(failure));m.instructions.add(new InsnNode(O.ATHROW));m.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,'java/lang/Throwable'));
  need(count===1,'Embeddium GPU region completion');return node;
}
function vanillaDispatcher(node){
  accessor(node,'com/sora/splineroads/client/RoadChunkUploads','splineroads$uploads',field(node,'toUpload','f_112675_','Ljava/util/Queue;'),'Ljava/util/Queue;');
  var begin=method(node,'uploadAllPendingUploads','m_194417_','()V'),head=new InsnList();head.add(aload(0));head.add(call(HOOK,'beginUploads','(L'+ROOT+';)V'));begin.instructions.insert(head);
  var dispose=method(node,'dispose','m_112733_','()V'),tail=new InsnList();tail.add(aload(0));tail.add(call(HOOK,'discardUploads','(L'+ROOT+';)V'));dispose.instructions.insert(tail);return node;
}
function embStorage(node){
  node.interfaces.add('com/sora/splineroads/client/RoadEmbeddiumStorage');
  var m=new MethodNode(O.ACC_PUBLIC|O.ACC_FINAL,'splineroads$allocation','(I)Ljava/lang/Object;',null,null);
  m.instructions.add(aload(0));m.instructions.add(new FieldInsnNode(O.GETFIELD,node.name,'allocations','[Lme/jellysquid/mods/sodium/client/gl/arena/GlBufferSegment;'));m.instructions.add(new VarInsnNode(O.ILOAD,1));m.instructions.add(new InsnNode(O.AALOAD));m.instructions.add(new InsnNode(O.ARETURN));m.maxStack=2;m.maxLocals=2;node.methods.add(m);return node;
}
function embSection(node){var m=method(node,'delete','delete','()V'),head=new InsnList();head.add(aload(0));head.add(call(HOOK,'release','(Ljava/lang/Object;)V'));m.instructions.insert(head);return node;}
function initializeCoreMod(){return {
  'vanilla_task':target(TASK,vanillaTask),
  'vanilla_chunk':target(CHUNK,vanillaChunk),
  'vanilla_dispatcher':target(ROOT,vanillaDispatcher),
  'embeddium_task':target(SODIUM+'compile/tasks/ChunkBuilderMeshingTask',embTask),
  'embeddium_filter':target(SODIUM+'RenderSectionManager',embFilter),
  'embeddium_upload':target(SODIUM+'region/RenderRegionManager',embUpload),
  'embeddium_storage':target(SODIUM+'data/SectionRenderDataStorage',embStorage),
  'embeddium_section':target(SODIUM+'RenderSection',embSection)
};}
