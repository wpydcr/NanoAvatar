import {AvatarPlayer} from './player.js';
const $=id=>document.getElementById(id);
let socket=null,player=null,version=0,turn=0,active=false,pending=false,preparing=false,connected=false;
let doneSequence=null,revision=-1,polling=false,uploading=false;
const notice=message=>{$('notice').textContent=message||'';};
const stage=message=>{$('stage').textContent=message;};
function controls(){
  $('send').disabled=$('audio').disabled=!connected||pending||preparing;
  $('stop').disabled=!connected||!active;
  $('save-settings').disabled=!connected||preparing;
  $('upload-video').disabled=preparing;
  $('video-file').disabled=preparing;
}
function send(data){if(socket?.readyState===WebSocket.OPEN){socket.send(JSON.stringify(data));return true;}return false;}
function disconnect(){
  version++;socket?.close();player?.dispose();socket=player=null;
  connected=active=pending=false;turn=0;doneSequence=null;
  $('connection').textContent='未连接';controls();
}
function connect(){
  disconnect();const own=version;
  $('reconnect').hidden=true;
  $('connection').textContent='连接中';
  player=new AvatarPlayer($('avatar'),{
    ack:sequence=>send({type:'ack',sequence}),
    error:message=>{notice(message);socket?.close();},
    buffering:waiting=>{if(active)stage(waiting?'缓冲中':'正在说话');},
    frame:frame=>{
      $('placeholder').hidden=true;
      if(frame.turn===turn&&active)stage('正在说话');
      else if(!frame.turn&&doneSequence!==null&&frame.sequence>doneSequence){
        active=false;doneSequence=null;stage('就绪');controls();
      }
    }
  });
  socket=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws');
  socket.binaryType='arraybuffer';
  socket.onmessage=event=>{
    if(own!==version)return;
    if(typeof event.data!=='string'){player.receive(event.data);return;}
    const data=JSON.parse(event.data);
    if(data.type==='ready'){
      $('avatar').width=data.width;$('avatar').height=data.height;
      document.querySelector('.preview').style.aspectRatio=data.width+'/'+data.height;
      connected=true;$('connection').textContent='已连接';stage('就绪');
      $('key-status').textContent=data.key_configured?'API Key 已配置':'尚未配置 API Key';
      send({type:'language',language:'zh'});controls();
    }else if(data.type==='turn'){
      if(turn)player.cancel(turn);
      turn=data.turn;active=true;pending=false;doneSequence=null;
      $('answer').textContent=data.kind==='audio'?'正在播放上传的音频…':'';
      if(data.kind!=='audio')$('prompt').value='';
      notice();stage('准备语音');controls();
    }else if(data.type==='text'&&data.turn===turn){
      $('answer').textContent+=data.delta;
    }else if(data.type==='metrics'&&data.turn===turn&&Number.isFinite(data.first_frame_ms)){
      $('latency').textContent='首帧推理 '+data.first_frame_ms.toFixed(1)+' ms';
    }else if(data.type==='generation_done'&&data.turn===turn){
      doneSequence=data.last_sequence;
    }else if(data.type==='cancelled'){
      player.cancel(data.turn);
      if(data.turn===turn){active=false;pending=false;stage('已停止');controls();}
    }else if(data.type==='settings'){
      $('api-key').value='';$('key-status').textContent=data.key_configured?'API Key 已配置':'尚未配置 API Key';
      notice('设置已应用。');
    }else if(data.type==='error'){
      pending=false;
      if(data.turn===turn){player.cancel(turn);active=false;}
      notice(data.message);controls();
    }else if(data.type==='fatal'){notice(data.message);socket.close();}
  };
  socket.onclose=()=>{
    if(own!==version)return;
    connected=active=pending=false;player.dispose();
    $('connection').textContent='已断开';$('reconnect').hidden=preparing;controls();
  };
  socket.onerror=()=>{if(own===version)notice('连接失败，请检查服务是否仍在运行。');};
}
async function refresh(){
  if(polling||uploading)return;polling=true;
  const own=version;
  try{
    const response=await fetch('/health',{cache:'no-store'});
    if(!response.ok)throw new Error('无法读取服务状态');
    const data=await response.json();
    if(own!==version)return;
    preparing=data.preparing;
    $('video-hint').textContent='单人、正面、连续镜头；最多 256 MB。取前 '+data.clip_seconds+' 秒，长边最多 '+data.max_side+'。';
    $('progress').value=data.progress;
    $('video-status').textContent=data.message;
    if(data.error)notice(data.error);
    if(data.avatar_ready&&!preparing&&data.revision!==revision){
      revision=data.revision;connect();
    }
    controls();
  }catch(error){if(own===version)notice('服务未连接，请检查启动窗口。');}
  finally{polling=false;}
}
$('video-form').addEventListener('submit',async event=>{
  event.preventDefault();const file=$('video-file').files[0];
  if(!file||preparing)return;
  if(file.size>256*1024*1024){notice('视频不能超过 256 MB。');return;}
  uploading=preparing=true;revision=-1;disconnect();controls();notice();$('reconnect').hidden=true;
  $('video-status').textContent='正在上传视频…';$('progress').value=0;stage('准备人物');
  try{
    const form=new FormData();form.append('video',file);
    const response=await fetch('/video',{method:'POST',body:form});
    const result=await response.json();
    if(!response.ok)throw new Error(result.error||'上传失败');
  }catch(error){notice(error.message);preparing=false;controls();}
  finally{uploading=false;}
  await refresh();
});
$('reconnect').onclick=()=>connect();
$('stop').onclick=()=>{if(turn)player.cancel(turn);send({type:'cancel'});active=false;controls();stage('已停止');};
$('send').onclick=async()=>{
  const text=$('prompt').value.trim();if(!text||!connected||pending)return;
  const own=version;
  try{
    pending=true;controls();await player.unlock();
    if(own!==version)return;
    send({type:'send',text});
  }catch(error){pending=false;notice(error.message);controls();}
};
$('save-settings').onclick=()=>send({type:'settings',key:$('api-key').value.trim(),
  llm_model:$('llm-model').value.trim(),tts_model:$('tts-model').value.trim(),voice:$('voice').value.trim()});
$('audio').onclick=()=>$('audio-file').click();
function makeWav(samples){
  const bytes=new ArrayBuffer(44+samples.length*2),view=new DataView(bytes);
  const string=(offset,value)=>[...value].forEach((c,i)=>view.setUint8(offset+i,c.charCodeAt(0)));
  string(0,'RIFF');view.setUint32(4,bytes.byteLength-8,true);string(8,'WAVE');string(12,'fmt ');
  view.setUint32(16,16,true);view.setUint16(20,1,true);view.setUint16(22,1,true);
  view.setUint32(24,16000,true);view.setUint32(28,32000,true);view.setUint16(32,2,true);
  view.setUint16(34,16,true);string(36,'data');view.setUint32(40,samples.length*2,true);
  for(let i=0;i<samples.length;i++)view.setInt16(44+i*2,Math.round(Math.max(-1,Math.min(32767/32768,samples[i]))*32768),true);
  return bytes;
}
$('audio-file').onchange=async()=>{
  const file=$('audio-file').files[0];$('audio-file').value='';
  if(!file||!connected||pending)return;
  const own=version;
  try{
    if(file.size>32*1024*1024)throw new Error('请选择 32 MB 以内、90 秒以内的音频。');
    pending=true;controls();notice();await player.unlock();
    const decoded=await player.context.decodeAudioData(await file.arrayBuffer());
    if(!decoded.length||decoded.duration>90)throw new Error('音频时长需要在 0～90 秒之间。');
    const offline=new OfflineAudioContext(1,Math.ceil(decoded.duration*16000),16000);
    const node=offline.createBufferSource();node.buffer=decoded;node.connect(offline.destination);node.start();
    const pcm=(await offline.startRendering()).getChannelData(0);
    if(own!==version)return;
    socket.send(makeWav(pcm));
  }catch(error){pending=false;notice(error.message||'音频解码失败，请使用 WAV 或 MP3。');controls();}
};
window.addEventListener('beforeunload',()=>{player?.dispose();socket?.close();});
refresh();setInterval(refresh,1000);

