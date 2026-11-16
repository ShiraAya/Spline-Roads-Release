/* Reuse only pack()'s temporary unpack array; retain its complete original codec. */
var O=Java.type('org.objectweb.asm.Opcodes');
var ASM=Java.type('net.minecraftforge.coremod.api.ASMAPI');
var List=Java.type('org.objectweb.asm.tree.InsnList');
var Call=Java.type('org.objectweb.asm.tree.MethodInsnNode');
var Var=Java.type('org.objectweb.asm.tree.VarInsnNode');
var Insn=Java.type('org.objectweb.asm.tree.InsnNode');
var helper='com/sora/splineroads/lighting/RoadPaletteScratch';
function initializeCoreMod(){return {'sr_palette_scratch':{
  target:{type:'METHOD',class:'net.minecraft.world.level.chunk.PalettedContainer',methodName:ASM.mapMethod('m_188064_'),methodDesc:'(Lnet/minecraft/core/IdMap;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;)Lnet/minecraft/world/level/chunk/PalettedContainerRO$PackedData;'},
  transformer:function(method){
    var local=method.maxLocals++;var instructions=method.instructions.toArray(),arrays=0,returns=0,throws=0;
    var head=new List();head.add(new Insn(O.ACONST_NULL));head.add(new Var(O.ASTORE,local));method.instructions.insert(head);
    for(var i=0;i<instructions.length;i++){
      var n=instructions[i];
      if(n.getOpcode()===O.NEWARRAY&&n.operand===O.T_INT){
        var call=new Call(O.INVOKESTATIC,helper,'borrow','(I)[I',false);method.instructions.set(n,call);
        var save=new List();save.add(new Insn(O.DUP));save.add(new Var(O.ASTORE,local));method.instructions.insert(call,save);arrays++;
      }
      if(n.getOpcode()===O.ARETURN||n.getOpcode()===O.ATHROW){
        var tail=new List();tail.add(new Var(O.ALOAD,local));tail.add(new Call(O.INVOKESTATIC,helper,'recycle','([I)V',false));method.instructions.insertBefore(n,tail);
        if(n.getOpcode()===O.ARETURN)returns++;else throws++;
      }
    }
    if(arrays!==1||returns!==1||throws!==1)throw new Error('SR palette scratch: unexpected pack shape '+arrays+'/'+returns+'/'+throws);
    return method;
  }
}};}
