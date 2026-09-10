import {AvatarPlayer} from './player.js';

const $ = id => document.getElementById(id);
const copy = {
  zh: {settings:'设置',first:'首帧延迟',audioSync:'音频时钟同步',metricNote:'首帧延迟：从音频窗口齐备后开始提取特征，到首张人脸完成；不含等待音频。',eyebrow:'一段音频，开启一段对话',heading:'让每一句话，都有回应。',intro:'写下问题，或者选择一段音频，看人物自然开口。',conversation:'对话',clear:'清空记录',empty:'从一句「你好」开始。',message:'输入消息',audio:'播放 WAV',stop:'停止',send:'发送',story:'讲一个小故事',hello:'认识一下',reconnect:'重新连接',privacy:'对话仅保留在当前页面。密钥只保留在本次服务进程内存中。',footer:'可替换的 LLM 与 TTS · 16 kHz 流式音频',settingsNote:'通义千问和 CosyVoice 仅用于演示。播放 WAV 不需要云端密钥。',llm:'大模型',voice:'音色',removeKey:'移除密钥',save:'应用',connected:'已连接',connecting:'连接中',offline:'已断开',idle:'等待你开口',waiting:'正在准备回答',speaking:'正在说话',buffering:'缓冲中',stopped:'已停止',prompt:'写下你想说的话…',you:'你',assistant:'助手',keyYes:'已配置，留空保留当前密钥。',keyNo:'尚未配置，可输入密钥或直接播放 WAV。',saved:'设置已应用。',wav:'本地 WAV 音频',wavError:'请选择不超过 90 秒的 16 kHz、单声道、PCM16 WAV。',network:'连接已断开。重新连接会开始新的对话上下文。',storyPrompt:'请讲一个温暖的小故事，关于一只在雨夜寻找回家路的小猫，四句话讲完。',helloPrompt:'你好，请用两句话介绍一下你自己。'},
  en: {settings:'Settings',first:'First-frame latency',audioSync:'Audio-clock sync',metricNote:'First-frame latency: from feature extraction on a complete audio window to the first corrected face; excludes audio waiting.',eyebrow:'ONE AUDIO STREAM. A LIVING CONVERSATION.',heading:'Give every word a presence.',intro:'Ask a question or choose an audio file, and watch your avatar respond.',conversation:'Conversation',clear:'Clear history',empty:'Start with a simple hello.',message:'Your message',audio:'Play WAV',stop:'Stop',send:'Send',story:'Tell a short story',hello:'Say hello',reconnect:'Reconnect',privacy:'Chat stays in this page. Your key stays only in the current server process memory.',footer:'Replaceable LLM & TTS · 16 kHz streaming audio',settingsNote:'Qwen and CosyVoice are demo adapters. WAV playback needs no cloud key.',llm:'Language model',voice:'Voice',removeKey:'Remove key',save:'Apply',connected:'Connected',connecting:'Connecting',offline:'Disconnected',idle:'Ready when you are',waiting:'Preparing a reply',speaking:'Speaking',buffering:'Buffering',stopped:'Stopped',prompt:'Write what is on your mind…',you:'You',assistant:'Assistant',keyYes:'Configured. Leave blank to keep the current key.',keyNo:'No key configured. Enter one or play a WAV file.',saved:'Settings applied.',wav:'Local WAV audio',wavError:'Choose a 16 kHz mono PCM16 WAV, up to 90 seconds.',network:'Connection lost. Reconnecting starts a new conversation context.',storyPrompt:'Tell a warm four-sentence story about a kitten finding its way home on a rainy night.',helloPrompt:'Hello, introduce yourself in two sentences.'}
};
copy.zh.inference = '推理后端';
copy.en.inference = 'Inference';
copy.zh.notStarted = '未开始'; copy.en.notStarted = 'Not started';
copy.zh.waitingAudio = '等待音频'; copy.en.waitingAudio = 'Waiting for audio';
let language = 'zh';
try { language = localStorage.getItem('nanoavatar.language') === 'en' ? 'en' : 'zh'; } catch (_) {}
const t = key => copy[language][key] || key;
let socket, player, connected = false, version = 0, currentTurn = 0;
let active = false, pending = null, answer = null, currentPrompt = '', doneSequence = null;
let keyConfigured = false, settingBusy = false, connection = 'connecting', stage = 'connecting';
let lastLatency = null;

function send(data) {
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(data)); return true;
}
function notice(value = '') { $('notice').textContent = value; $('notice').hidden = !value; }
function controls() {
  $('send').disabled = !connected || Boolean(pending);
  $('audio').disabled = !connected || Boolean(pending);
  $('stop').disabled = !active && !pending;
  $('clear').disabled = !connected || Boolean(pending?.clearing);
  $('save-settings').disabled = !connected || settingBusy;
  $('remove-key').disabled = !connected || settingBusy;
}
function translate() {
  document.documentElement.lang = language === 'zh' ? 'zh-CN' : 'en';
  document.querySelectorAll('[data-i18n]').forEach(node => node.textContent = t(node.dataset.i18n));
  document.querySelectorAll('[data-role]').forEach(node => node.textContent = t(node.dataset.role));
  $('language').textContent = language === 'zh' ? 'EN' : '中文';
  $('prompt').placeholder = t('prompt');
  $('connection').textContent = t(connection);
  $('stage-state').textContent = t(stage);
  $('key-state').textContent = t(keyConfigured ? 'keyYes' : 'keyNo');
  if (lastLatency === null) $('latency').textContent = t(active ? 'waitingAudio' : 'notStarted');
}
function setStage(value) { stage = value; $('stage-state').textContent = t(value); }
function message(role, text) {
  document.querySelector('.empty')?.remove();
  const article = document.createElement('article'); article.className = `message ${role}`;
  const label = document.createElement('div'); label.className = 'role';
  label.dataset.role = role === 'user' ? 'you' : 'assistant'; label.textContent = t(label.dataset.role);
  const body = document.createElement('p'); body.textContent = text;
  article.append(label, body); $('history').append(article);
  $('history').scrollTop = $('history').scrollHeight;
  return body;
}
function stop() {
  pending = null;
  if (currentTurn) player?.cancel(currentTurn);
  send({type:'cancel'}); active = false; answer?.parentElement.classList.remove('pending');
  setStage('stopped'); controls();
}
function applyConfig(data) {
  keyConfigured = data.key_configured;
  $('llm-model').value = data.llm_model; $('tts-model').value = data.tts_model; $('voice').value = data.voice;
  translate();
}
function showRuntime(runtime) {
  $('runtime-device').textContent = runtime?.device || '--';
  $('runtime-provider').textContent = runtime?.device === 'GPU' && runtime?.automatic_fallback === false ? `NVIDIA CUDA${runtime.precision ? ' / ' + runtime.precision : ''}` : '';
}
function resetConversation() {
  currentTurn = 0; active = false; answer = null; pending = null; doneSequence = null;
  currentPrompt = ''; lastLatency = null;
  $('history').replaceChildren();
  const empty = document.createElement('p');
  empty.className = 'empty'; empty.dataset.i18n = 'empty'; empty.textContent = t('empty');
  $('history').append(empty);
  $('latency').textContent = t('notStarted');
}
function connect() {
  const ownVersion = ++version;
  socket?.close(); player?.dispose();
  connected = false; settingBusy = false; resetConversation();
  connection = 'connecting'; setStage('connecting'); controls(); translate();
  showRuntime(null);
  $('reconnect').hidden = true;
  player = new AvatarPlayer($('avatar'), {
    ack: sequence => send({type:'ack', sequence}),
    error: error => { notice(error); socket.close(); },
    buffering: waiting => { if (active) setStage(waiting ? 'buffering' : 'speaking'); },
    frame: frame => {
      if (frame.turn === currentTurn && active) {
        setStage('speaking');
      } else if (!frame.turn) {
        if (doneSequence !== null && frame.sequence > doneSequence) {
          active = false; doneSequence = null; controls();
        }
        if (!active) setStage('idle');
      }
    }
  });
  socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`);
  socket.binaryType = 'arraybuffer';
  socket.onmessage = event => {
    if (ownVersion !== version) return;
    if (typeof event.data !== 'string') { player.receive(event.data); return; }
    const data = JSON.parse(event.data);
    if (data.type === 'ready') {
      $('avatar').width = data.width; $('avatar').height = data.height;
      document.querySelector('.stage').style.aspectRatio = `${data.width}/${data.height}`;
      $('resolution').textContent = `${data.width} × ${data.height}`;
      showRuntime(data.runtime);
      connected = true; connection = 'connected'; applyConfig(data); controls(); setStage('idle');
      send({type:'language', language});
    } else if (data.type === 'runtime') {
      showRuntime(data.runtime);
    } else if (data.type === 'turn') {
      if (currentTurn) player.cancel(currentTurn);
      answer?.parentElement.classList.remove('pending');
      currentTurn = data.turn; currentPrompt = data.prompt; active = true; doneSequence = null;
      lastLatency = null; $('latency').textContent = t('waitingAudio');
      message('user', data.kind === 'audio' ? t('wav') : data.prompt);
      answer = message('assistant', data.kind === 'audio' ? t('wav') : '');
      answer.parentElement.classList.add('pending');
      if (pending?.draft === $('prompt').value) $('prompt').value = '';
      pending = null; notice(); setStage('waiting'); controls();
    } else if (data.type === 'text' && data.turn === currentTurn && active) {
      const log = $('history'), follow = log.scrollHeight - log.scrollTop - log.clientHeight < 64;
      answer.append(document.createTextNode(data.delta));
      if (follow) log.scrollTop = log.scrollHeight;
    } else if (data.type === 'metrics' && data.turn === currentTurn && active) {
      if (data.first_frame_definition !== 'feature_extraction_start_to_first_generated_rgb' ||
          !Number.isFinite(data.first_frame_ms) || data.first_frame_ms < 0) return;
      lastLatency = data.first_frame_ms;
      $('latency').textContent = `${lastLatency.toFixed(1)} ms`;
    } else if (data.type === 'generation_done' && data.turn === currentTurn && active) {
      doneSequence = data.last_sequence; answer?.parentElement.classList.remove('pending');
    } else if (data.type === 'cancelled') {
      player.cancel(data.turn);
      if (data.turn === currentTurn) { active = false; answer?.parentElement.classList.remove('pending'); controls(); }
    } else if (data.type === 'cleared') {
      resetConversation(); notice(); controls(); setStage('idle');
    } else if (data.type === 'settings') {
      settingBusy = false; applyConfig(data); $('api-key').value = ''; $('settings-dialog').close();
      controls(); notice(t('saved'));
    } else if (data.type === 'error') {
      if (settingBusy && !data.turn) {
        settingBusy = false; $('settings-error').textContent = data.message; $('settings-error').hidden = false;
      } else if (!data.turn || data.turn === currentTurn) {
        notice(data.message);
        if (!data.turn) pending = null;
        if (data.turn === currentTurn) {
          player.cancel(currentTurn); active = false; answer?.parentElement.classList.remove('pending');
          if (!$('prompt').value && currentPrompt !== 'WAV') $('prompt').value = currentPrompt;
        }
      }
      controls();
    } else if (data.type === 'fatal') { notice(data.message); socket.close(); }
  };
  socket.onclose = () => {
    if (ownVersion !== version) return;
    connected = active = false; pending = null; settingBusy = false;
    connection = 'offline'; setStage('offline'); translate(); controls(); player.dispose();
    showRuntime(null);
    answer?.parentElement.classList.remove('pending');
    $('api-key').value = ''; $('reconnect').hidden = false;
    if (!$('notice').textContent) notice(t('network'));
  };
}

$('chat-form').addEventListener('submit', async event => {
  event.preventDefault();
  const draft = $('prompt').value;
  if (!draft.trim() || !connected || pending) return;
  const ownVersion = version;
  const submission = {draft};
  try {
    pending = submission; controls(); await player.unlock();
    if (pending !== submission || ownVersion !== version || !connected) return;
    if (!send({type:'send', text:draft.trim()})) { pending = null; controls(); }
  } catch (error) { if (pending === submission) { pending = null; controls(); notice(error.message); } }
});
$('prompt').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault(); $('chat-form').requestSubmit();
  }
});
$('stop').addEventListener('click', stop);
$('clear').addEventListener('click', () => {
  if (currentTurn) player.cancel(currentTurn);
  pending = {clearing:true}; controls(); send({type:'clear'});
});
$('reconnect').addEventListener('click', () => { notice(); connect(); });
$('language').addEventListener('click', () => {
  language = language === 'zh' ? 'en' : 'zh';
  try { localStorage.setItem('nanoavatar.language', language); } catch (_) {}
  translate(); send({type:'language', language});
});
$('settings').addEventListener('click', () => {
  $('api-key').value = ''; $('settings-error').hidden = true; $('settings-dialog').showModal();
});
$('close-settings').addEventListener('click', () => $('settings-dialog').close());
$('settings-dialog').addEventListener('close', () => { $('api-key').value = ''; });
function settings(removeKey = false) {
  if (!connected || settingBusy) return;
  const data = {type:'settings', llm_model:$('llm-model').value.trim(), tts_model:$('tts-model').value.trim(), voice:$('voice').value.trim()};
  if (removeKey) data.remove_key = true;
  else if ($('api-key').value.trim()) data.key = $('api-key').value.trim();
  settingBusy = true; $('settings-error').hidden = true; controls(); send(data);
}
$('settings-form').addEventListener('submit', event => { event.preventDefault(); settings(); });
$('remove-key').addEventListener('click', () => settings(true));
$('story').addEventListener('click', () => { $('prompt').value = t('storyPrompt'); $('prompt').focus(); });
$('hello').addEventListener('click', () => { $('prompt').value = t('helloPrompt'); $('prompt').focus(); });
$('audio').addEventListener('click', () => $('audio-file').click());
$('audio-file').addEventListener('change', async () => {
  const file = $('audio-file').files[0]; $('audio-file').value = '';
  if (!file || !connected || pending) return;
  if (file.size > 2945536) { notice(t('wavError')); return; }
  const ownVersion = version;
  const submission = {};
  try {
    pending = submission; controls(); await player.unlock();
    if (pending !== submission || ownVersion !== version || !connected) return;
    const bytes = await file.arrayBuffer();
    if (pending !== submission || ownVersion !== version || !connected) return;
    socket.send(bytes);
  } catch (error) { if (pending === submission) { pending = null; controls(); notice(error.message); } }
});
window.addEventListener('beforeunload', () => { player?.dispose(); socket?.close(); });
translate(); connect();
