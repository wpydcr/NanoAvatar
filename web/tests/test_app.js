// Run with: node web/tests/test_app.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');

const ready = {preparing:false, avatar_ready:true, revision:1, progress:1, message:'ready'};
const tick = () => new Promise(resolve => setImmediate(resolve));

function page() {
  const elements = new Map(), requests = [], sockets = [];
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      hidden:false, disabled:false, files:[{size:100}], value:'',
      addEventListener(type, handler) { this[type] = handler; }
    });
    return elements.get(id);
  };
  class Socket {
    static OPEN = 1;
    constructor() { this.readyState = 1; sockets.push(this); }
    send() {}
    close() { this.readyState = 3; this.onclose?.(); }
  }
  let poll;
  const context = vm.createContext({
    document:{getElementById:element, querySelector:() => ({style:{}})},
    window:{addEventListener() {}}, location:{protocol:'http:', host:'localhost'},
    WebSocket:Socket, AvatarPlayer:class { dispose() {} }, FormData:class { append() {} },
    fetch:url => new Promise((resolve, reject) => requests.push({url, resolve, reject})),
    setInterval:handler => { poll = handler; }
  });
  const script = fs.readFileSync(path.join(__dirname, '../static/app.js'), 'utf8');
  vm.runInContext(script.trimStart().replace(/^import[^\n]+\n/, ''), context);
  return {
    element, requests, sockets, poll:() => poll(),
    upload:() => element('video-form').submit({preventDefault() {}}),
    take(url) { const request = requests.shift(); assert.equal(request?.url, url); return request; },
    async respond(request, data, ok=true) {
      request.resolve({ok, json:async () => data}); await tick();
    },
    connected() {
      sockets.at(-1).onmessage({data:JSON.stringify({type:'ready', width:100, height:100})});
      assert.equal(element('send').disabled, false);
    }
  };
}

test('first upload connects when ready and keeps polling after connection', async () => {
  const app = page();
  await app.respond(app.take('/health'), {...ready, avatar_ready:false, revision:0});
  assert.equal(app.sockets.length, 0);
  const upload = app.upload();
  await app.respond(app.take('/video'), {status:'preparing'});
  await app.respond(app.take('/health'), {...ready, avatar_ready:false, preparing:true});
  await upload;
  app.poll();
  await app.respond(app.take('/health'), ready);
  app.connected();
  app.poll();
  await app.respond(app.take('/health'), ready);
  assert.equal(app.sockets.length, 1);
});

test('failed replacement reconnects the retained video', async () => {
  const app = page();
  await app.respond(app.take('/health'), ready); app.connected();
  const upload = app.upload();
  await app.respond(app.take('/video'), {status:'preparing'});
  await app.respond(app.take('/health'), {...ready, preparing:true});
  await upload;
  app.poll();
  await app.respond(app.take('/health'), {...ready, error:'No face detected'});
  assert.equal(app.sockets.length, 2); app.connected();
});

test('stale health response cannot reconnect or unlock an upload', async () => {
  const app = page();
  await app.respond(app.take('/health'), ready); app.connected();
  app.poll(); const stale = app.take('/health');
  const upload = app.upload(); const post = app.take('/video');
  await app.respond(stale, ready);
  assert.equal(app.sockets.length, 1, 'old health must not reconnect during upload');
  assert.equal(app.element('upload-video').disabled, true);
  app.poll();
  assert.equal(app.requests.length, 0, 'wait for upload POST before polling');
  await app.respond(post, {status:'preparing'});
  await app.respond(app.take('/health'), {...ready, preparing:true});
  await upload;
  app.poll();
  await app.respond(app.take('/health'), {...ready, error:'No face detected'});
  assert.equal(app.sockets.length, 2); app.connected();
});

test('rejected upload releases polling and restores the retained video', async () => {
  const app = page();
  await app.respond(app.take('/health'), ready); app.connected();
  const upload = app.upload();
  await app.respond(app.take('/video'), {error:'Invalid video'}, false);
  await app.respond(app.take('/health'), ready); await upload;
  assert.equal(app.sockets.length, 2); app.connected();
  app.poll(); await app.respond(app.take('/health'), ready);
});
