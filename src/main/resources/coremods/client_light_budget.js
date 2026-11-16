/* Keep the vanilla queue and its task cap. Yield only AFTER an entire task. */
var O=Java.type('org.objectweb.asm.Opcodes');
var ASM=Java.type('net.minecraftforge.coremod.api.ASMAPI');
var List=Java.type('org.objectweb.asm.tree.InsnList');
var Call=Java.type('org.objectweb.asm.tree.MethodInsnNode');
var Var=Java.type('org.objectweb.asm.tree.VarInsnNode');
var Insn=Java.type('org.objectweb.asm.tree.InsnNode');
var Label=Java.type('org.objectweb.asm.tree.LabelNode');
var Jump=Java.type('org.objectweb.asm.tree.JumpInsnNode');
function initializeCoreMod(){return {'sr_light_slice':{
  target:{type:'METHOD',class:'net.minecraft.client.multiplayer.ClientLevel',methodName:ASM.mapMethod('m_194141_'),methodDesc:'()V'},
  transformer:function(method){
    var instructions=method.instructions.toArray(),site=null;
    for(var i=0;i<instructions.length;i++){
      var n=instructions[i];
      if(n.getOpcode()===O.INVOKEINTERFACE&&n.owner==='java/lang/Runnable'&&n.name==='run'&&n.desc==='()V'){
        if(site!==null)throw new Error('SR client light budget: multiple task calls');site=n;
      }
    }
    if(site===null)throw new Error('SR client light budget: task call missing');
    var local=method.maxLocals;method.maxLocals+=2;
    var head=new List();head.add(new Call(O.INVOKESTATIC,'com/sora/splineroads/client/RoadClientLightBudget','deadline','()J',false));head.add(new Var(O.LSTORE,local));method.instructions.insert(head);
    var tail=new List(),keep=new Label();tail.add(new Var(O.LLOAD,local));tail.add(new Call(O.INVOKESTATIC,'java/lang/System','nanoTime','()J',false));tail.add(new Insn(O.LCMP));tail.add(new Jump(O.IFGT,keep));tail.add(new Insn(O.RETURN));tail.add(keep);method.instructions.insert(site,tail);
    return method;
  }
},'sr_light_publication':{
  target:{type:'METHOD',class:'net.minecraft.client.renderer.LevelRenderer',methodName:ASM.mapMethod('m_109599_'),methodDesc:'(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V'},
  transformer:function(method){
    var instructions=method.instructions.toArray(),count=0;
    for(var i=0;i<instructions.length;i++){
      var n=instructions[i];
      if(n.getOpcode()===O.INVOKEVIRTUAL&&n.owner==='net/minecraft/world/level/lighting/LevelLightEngine'&&n.name===ASM.mapMethod('m_9323_')&&n.desc==='()I'){
        method.instructions.set(n,new Call(O.INVOKESTATIC,'com/sora/splineroads/client/RoadClientLightBudget','update','(Lnet/minecraft/world/level/lighting/LevelLightEngine;)I',false));count++;
      }
    }
    if(count!==1)throw new Error('SR client light budget: expected one render-loop update, got '+count);
    return method;
  }
}};}
