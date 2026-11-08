/* Optional, read-only MTR snapshot boundary. No simulator exists when MTR is
 * absent. The Java boundary checks the supported version before loading MTR types. */
function initializeCoreMod() {
  var O=Java.type('org.objectweb.asm.Opcodes');
  var List=Java.type('org.objectweb.asm.tree.InsnList');
  var Var=Java.type('org.objectweb.asm.tree.VarInsnNode');
  var Call=Java.type('org.objectweb.asm.tree.MethodInsnNode');
  return {sr_mtr_railbed_marker:{target:{type:'METHOD',class:'org.mtr.mod.render.RenderRails',methodName:'lambda$isHoldingRailRelated$12',methodDesc:'(Lorg/mtr/mapping/holder/Item;)Z'},transformer:function(method){
    var Insn=Java.type('org.objectweb.asm.tree.InsnNode');var instructions=method.instructions.toArray();
    for(var i=0;i<instructions.length;i++)if(instructions[i].getOpcode()===O.IRETURN){var hook=new List();hook.add(new Var(O.ALOAD,0));hook.add(new Call(O.INVOKESTATIC,'com/sora/splineroads/compat/mtr/MtrRailAccess','railbedItem','(Ljava/lang/Object;)Z',false));hook.add(new Insn(O.IOR));method.instructions.insertBefore(instructions[i],hook);}return method;
  }},sr_mtr_node_dynamic:{target:{type:'METHOD',class:'org.mtr.mod.block.BlockNode',methodName:'<init>',methodDesc:'(Lorg/mtr/core/data/TransportMode;)V'},transformer:function(method){
    var instructions=method.instructions.toArray();
    for(var i=0;i<instructions.length;i++)if(instructions[i].owner==='org/mtr/mapping/holder/BlockSettings'&&instructions[i].name==='nonOpaque')method.instructions.insert(instructions[i],new Call(O.INVOKEVIRTUAL,'org/mtr/mapping/holder/BlockSettings','dynamicBounds','()Lorg/mtr/mapping/holder/BlockSettings;',false));
    return method;
  }},sr_mtr_node_collision:{target:{type:'METHOD',class:'org.mtr.mod.block.BlockNode',methodName:'getCollisionShape2',methodDesc:'(Lorg/mtr/mapping/holder/BlockState;Lorg/mtr/mapping/holder/BlockView;Lorg/mtr/mapping/holder/BlockPos;Lorg/mtr/mapping/holder/ShapeContext;)Lorg/mtr/mapping/holder/VoxelShape;'},transformer:function(method){
    var Cast=Java.type('org.objectweb.asm.tree.TypeInsnNode');var instructions=method.instructions.toArray();
    for(var i=0;i<instructions.length;i++)if(instructions[i].getOpcode()===O.ARETURN){var hook=new List();hook.add(new Var(O.ALOAD,2));hook.add(new Var(O.ALOAD,3));hook.add(new Call(O.INVOKESTATIC,'com/sora/splineroads/compat/mtr/MtrRailAccess','nodeShape','(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;',false));hook.add(new Cast(O.CHECKCAST,'org/mtr/mapping/holder/VoxelShape'));method.instructions.insertBefore(instructions[i],hook);}return method;
  }},sr_mtr_snapshot:{target:{type:'METHOD',class:'org.mtr.core.simulation.Simulator',methodName:'tick',methodDesc:'()V'},transformer:function(method){
    var instructions=method.instructions.toArray();
    for(var i=0;i<instructions.length;i++)if(instructions[i].getOpcode()===O.RETURN){
      var hook=new List();hook.add(new Var(O.ALOAD,0));
      hook.add(new Call(O.INVOKESTATIC,'com/sora/splineroads/compat/mtr/MtrRailAccess','tick','(Ljava/lang/Object;)V',false));
      method.instructions.insertBefore(instructions[i],hook);
    }
    return method;
  }},sr_mtr_sync:{target:{type:'METHOD',class:'org.mtr.core.data.Data',methodName:'sync',methodDesc:'()V'},transformer:function(method){
    var instructions=method.instructions.toArray();
    for(var i=0;i<instructions.length;i++)if(instructions[i].getOpcode()===O.RETURN){
      var hook=new List();hook.add(new Var(O.ALOAD,0));hook.add(new Call(O.INVOKESTATIC,'com/sora/splineroads/compat/mtr/MtrRailAccess','changed','(Ljava/lang/Object;)V',false));method.instructions.insertBefore(instructions[i],hook);
    }return method;
  }}};
}
