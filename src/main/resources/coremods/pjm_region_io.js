/* Optional PJM compatibility: its scalar DataInputStream reads otherwise issue
 * a native file read per byte. Wrap only the two region stores' owned file
 * streams; keep their codec, paths, cache, executor and close/error behavior. */
var O=Java.type('org.objectweb.asm.Opcodes');
var List=Java.type('org.objectweb.asm.tree.InsnList');
var Type=Java.type('org.objectweb.asm.tree.TypeInsnNode');
var Insn=Java.type('org.objectweb.asm.tree.InsnNode');
var Ldc=Java.type('org.objectweb.asm.tree.LdcInsnNode');
var Call=Java.type('org.objectweb.asm.tree.MethodInsnNode');
function bufferedRegions(node){
  var sites=[],reads=0,writes=0,already=false;
  for(var m=0;m<node.methods.size();m++){
    var method=node.methods.get(m),read=method.name==='readRegion',write=method.name==='writeRegion';
    if(!read&&!write)continue;
    var instructions=method.instructions.toArray();
    for(var i=0;i<instructions.length;i++){
      var n=instructions[i];
      if(n.getOpcode()===O.NEW&&(n.desc==='java/io/BufferedInputStream'||n.desc==='java/io/BufferedOutputStream'))already=true;
      if(n.getOpcode()!==O.INVOKESTATIC||n.owner!=='java/nio/file/Files')continue;
      if(read&&n.name==='newInputStream'&&n.desc==='(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;'){
        reads++;sites.push({method:method,call:n,read:true});
      }else if(write&&n.name==='newOutputStream'&&n.desc==='(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/OutputStream;'){
        writes++;sites.push({method:method,call:n,read:false});
      }
    }
  }
  // A future codec/third-party patch is left untouched. Inspect every site
  // before mutating anything so a partial match cannot apply half the change.
  if(already||reads!==1||writes!==1)return node;
  for(var i=0;i<sites.length;i++){
    var site=sites[i],type=site.read?'java/io/BufferedInputStream':'java/io/BufferedOutputStream';
    var added=new List();added.add(new Type(O.NEW,type));added.add(new Insn(O.DUP_X1));added.add(new Insn(O.SWAP));added.add(new Ldc(65536));
    added.add(new Call(O.INVOKESPECIAL,type,'<init>',site.read?'(Ljava/io/InputStream;I)V':'(Ljava/io/OutputStream;I)V',false));
    site.method.instructions.insert(site.call,added);site.method.maxStack+=3;
  }
  return node;
}
function initializeCoreMod(){return {
  'sr_pjm_block_region_io':{target:{type:'CLASS',name:'com.sora.pjm.map.blockmap.PJMBlockRegionStore'},transformer:bufferedRegions},
  'sr_pjm_road_region_io':{target:{type:'CLASS',name:'com.sora.pjm.map.road.PJMRoadRegionStore'},transformer:bufferedRegions}
};}
