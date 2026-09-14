import {DisplayedMouth, FaceCompositor} from './mouth.js';
const closeFrame=f=>{f.bitmap?.close();f.face?.close();f.mask?.close();};

// Each scheduled audio buffer and layered frame belong to one 640-sample interval.
// A gap pauses both media tracks; arrival time never changes their relative PTS.
export class AvatarPlayer {
  constructor(canvas, callbacks = {}) {
    this.canvas = canvas;
    this.paint = canvas.getContext('2d', {alpha:false});
    this.callbacks = callbacks;
    this.context = null;
    this.queue = [];
    this.scheduled = [];
    this.nodes = new Set();
    this.gains = new Map();
    this.cancelledThrough = 0;
    this.mouth = new DisplayedMouth();
    this.compositor = new FaceCompositor();
    this.lastBase = null;
    this.playing = false;
    this.nextTime = 0;
    this.lastSequence = -1;
    this.lastReceived = -1;
    this.underflows = 0;
    this.disposed = false;
    this.decodeQueue = [];
    this.decoding = 0;
    this.decoded = new Map();
    this.lastQueued = -1;
    this.failed = false;
    this.metrics = {decodedFrames:0, decodeMilliseconds:0, maxDecodeMilliseconds:0, maxBufferedFrames:0};
    this.tick = this.tick.bind(this);
    this.animation = requestAnimationFrame(this.tick);
  }

  async unlock() {
    if (!this.context) {
      this.context = new AudioContext({sampleRate:16000, latencyHint:'interactive'});
      // Only silent idle frames exist before the first user gesture. Keep their
      // order while changing the clock from performance.now() to Web Audio.
      this.queue.unshift(...this.scheduled);
      this.scheduled = [];
      this.playing = false;
    }
    await this.context.resume();
    if (this.context.state !== 'running') throw new Error('Click Send again to enable audio.');
  }

  now() { return this.context ? this.context.currentTime : performance.now() / 1000; }

  audibleNow() {
    if (!this.context) return this.now();
    const stamp = this.context.getOutputTimestamp?.();
    if (stamp?.contextTime > 0 && stamp.performanceTime > 0) {
      return stamp.contextTime + (performance.now() - stamp.performanceTime) / 1000;
    }
    return this.context.currentTime - (this.context.outputLatency || this.context.baseLatency || 0);
  }

  fail(message) {
    if (this.failed || this.disposed) return;
    this.failed = true;
    this.decodeQueue = [];
    for (const frame of this.decoded.values()) closeFrame(frame);
    this.decoded.clear();
    this.callbacks.error?.(message);
  }

  receive(data) {
    if (this.disposed || this.failed) return;
    try {
      if (data.byteLength < 44) throw new Error('Incomplete media packet.');
      const view = new DataView(data);
      if (view.getUint32(0, true) !== 0x3256414e) throw new Error('Media protocol mismatch.');
      const sequence = view.getUint32(4, true), turn = view.getUint32(8, true);
      const phase = view.getUint32(12, true), sourceIndex = view.getUint32(16, true);
      const samples = view.getUint32(20, true), jpegSize = view.getUint32(24, true);
      const generated = Boolean(view.getUint32(28, true));
      const metadataSize=view.getUint32(32,true),faceSize=view.getUint32(36,true),maskSize=view.getUint32(40,true);
      if (sequence !== this.lastReceived + 1 || samples !== 640 || !jpegSize || !maskSize || data.byteLength !== 44 + samples*2 + metadataSize + jpegSize + faceSize + maskSize) {
        throw new Error('Media timeline interrupted. Please reconnect.');
      }
      const meta=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(new Uint8Array(data,1324,metadataSize)));
      if(!['speech','idle','end'].includes(meta.kind)||!Number.isFinite(meta.blend)||meta.blend<0||meta.blend>1||
         !Array.isArray(meta.box)||meta.box.length!==4||!meta.box.every(Number.isInteger)||
         meta.box[0]<0||meta.box[1]<0||meta.box[2]>this.canvas.width||meta.box[3]>this.canvas.height||meta.box[2]<=meta.box[0]||meta.box[3]<=meta.box[1]||
         !Array.isArray(meta.affine)||meta.affine.length!==6||!meta.affine.every(Number.isFinite)||Math.abs(meta.affine[0]*meta.affine[4]-meta.affine[1]*meta.affine[3])<1e-8||
         (meta.source_bounds!==null&&(!Array.isArray(meta.source_bounds)||meta.source_bounds.length!==4||!meta.source_bounds.every(Number.isFinite)))||
         !Number.isInteger(meta.end_turn)||meta.end_turn<0||
         (meta.kind==='speech' ? (!turn||!faceSize||meta.end_turn!==0) : (turn!==0||faceSize!==0))||
         (meta.kind==='end'?meta.end_turn===0:meta.end_turn!==0))throw new Error('Invalid layered frame metadata.');
      const buffered = this.decodeQueue.length + this.decoding + this.decoded.size + this.queue.length + this.scheduled.length;
      if (buffered >= 64) throw new Error('Playback buffer limit reached. Please reconnect.');
      this.metrics.maxBufferedFrames = Math.max(this.metrics.maxBufferedFrames, buffered + 1);
      this.lastReceived = sequence;
      this.decodeQueue.push({data, sequence, turn, phase, sourceIndex, generated,
                             meta,metadataSize,jpegSize,faceSize,maskSize});
      this.decodeMore();
    } catch (error) { this.fail(error.message); }
  }

  decodeMore() {
    // Parallel decoders finish out of order; only contiguous frames enter playback.
    while (!this.disposed && !this.failed && this.decoding < 4 && this.decodeQueue.length) {
      const frame = this.decodeQueue.shift();
      this.decoding++;
      this.decode(frame).catch(error => this.fail(error.message)).finally(() => {
        this.decoding--;
        this.decodeMore();
      });
    }
  }

  async decode(frame) {
    const started = performance.now();
    const data = frame.data;
    delete frame.data;
    let offset=1324+frame.metadataSize;
    const jobs=[];
    for(const [size,type] of [[frame.jpegSize,'image/jpeg'],[frame.faceSize,'image/png'],[frame.maskSize,'image/png']]){
      jobs.push(size?createImageBitmap(new Blob([new Uint8Array(data,offset,size)],{type}),{colorSpaceConversion:'none'}):Promise.resolve(null));offset+=size;
    }
    const results=await Promise.allSettled(jobs),bitmaps=results.map(r=>r.status==='fulfilled'?r.value:null);
    const failed=results.find(r=>r.status==='rejected');
    if(this.disposed||this.failed||failed){bitmaps.forEach(b=>b?.close());if(failed)throw failed.reason;return;}
    const [bitmap,face,mask]=bitmaps,[x0,y0,x1,y1]=frame.meta.box;
    if(bitmap.width!==this.canvas.width||bitmap.height!==this.canvas.height||
       (face&&(face.width!==256||face.height!==256))||mask.width!==x1-x0||mask.height!==y1-y0){
      bitmaps.forEach(b=>b?.close());throw new Error('Layer size does not match the uploaded video.');
    }
    frame.face=face;frame.mask=mask;
    frame.pcm = Float32Array.from(new Int16Array(data, 44, 640), value => value / 32768);
    frame.bitmap=bitmap;
    this.decoded.set(frame.sequence, frame);
    const elapsed = performance.now() - started;
    this.metrics.decodedFrames++;
    this.metrics.decodeMilliseconds += elapsed;
    this.metrics.maxDecodeMilliseconds = Math.max(this.metrics.maxDecodeMilliseconds, elapsed);
    while (this.decoded.has(this.lastQueued + 1)) {
      this.queue.push(this.decoded.get(++this.lastQueued));
      this.decoded.delete(this.lastQueued);
    }
  }

  cancel(turn) {
    if (!turn) return;
    this.cancelledThrough = Math.max(this.cancelledThrough, turn);
    this.mouth.cancel(turn);
    if(this.lastBase&&!this.mouth.face)this.paint.drawImage(this.lastBase,0,0);
    const gain = this.gains.get(turn);
    if (gain && this.context) {
      const now = this.context.currentTime;
      gain.gain.cancelScheduledValues(now);
      gain.gain.setValueAtTime(gain.gain.value, now);
      gain.gain.linearRampToValueAtTime(0, now + .02);
      for (const item of this.nodes) if (item.turn === turn) {
        try { item.node.stop(now + .025); } catch (_) {}
      }
    }
  }

  schedule(frame, when) {
    frame.when = when;
    if (this.context && frame.turn && frame.turn > this.cancelledThrough) {
      const buffer = this.context.createBuffer(1, frame.pcm.length, 16000);
      buffer.copyToChannel(frame.pcm, 0);
      const node = this.context.createBufferSource();
      let gain = this.gains.get(frame.turn);
      if (!gain) {
        gain = this.context.createGain();
        gain.connect(this.context.destination);
        this.gains.set(frame.turn, gain);
      }
      node.buffer = buffer;
      node.connect(gain);
      const item = {node, turn:frame.turn};
      this.nodes.add(item);
      node.onended = () => {
        this.nodes.delete(item);
        node.disconnect();
        if (![...this.nodes].some(other => other.turn === frame.turn)) {
          gain.disconnect();
          if (this.gains.get(frame.turn) === gain) this.gains.delete(frame.turn);
        }
      };
      node.start(when);
    }
    this.scheduled.push(frame);
  }

  tick() {
    if (this.disposed) return;
    const now = this.now();
    if (!this.playing && this.queue.length >= (this.context ? 8 : 3)) {
      this.nextTime = now + .07;
      this.playing = true;
      this.callbacks.buffering?.(false);
    }
    if (this.playing) {
      while (this.queue.length && this.nextTime < now + .32) {
        if (this.nextTime < now + .012) {
          // Keep samples intact after a stall; never let a late start collapse
          // several audio frames onto the same instant.
          this.nextTime = now + .07;
        }
        this.schedule(this.queue.shift(), this.nextTime);
        this.nextTime += .04;
      }
    }
    const audible = this.audibleNow();
    let latest = null;
    while (this.scheduled.length && this.scheduled[0].when <= audible) {
      if (latest) closeFrame(latest);
      latest = this.scheduled.shift();
    }
    if (latest) {
      const discarded = (latest.turn && latest.turn <= this.cancelledThrough)||
                        (latest.meta.end_turn && latest.meta.end_turn <= this.cancelledThrough);
      // The base never contains a generated face, including cancelled old packets.
      this.paint.drawImage(latest.bitmap, 0, 0);
      this.lastBase?.close();
      this.lastBase=latest.bitmap;
      const draw=this.mouth.display(latest,performance.now());
      if(draw)this.compositor.paint(this.paint,latest,draw);
      latest.face?.close();latest.face=null;latest.mask?.close();latest.mask=null;
      this.canvas.classList.add('visible');
      this.lastSequence = latest.sequence;
      if (!discarded) this.callbacks.frame?.(latest);
      this.callbacks.ack?.(latest.sequence);
    }
    if (this.playing && !this.queue.length && !this.scheduled.length && audible >= this.nextTime) {
      this.playing = false;
      this.underflows++;
      this.callbacks.buffering?.(true);
    }
    this.animation = requestAnimationFrame(this.tick);
  }

  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    this.mouth.clear();
    if(this.lastBase)this.paint.drawImage(this.lastBase,0,0);
    this.lastBase?.close();this.lastBase=null;
    cancelAnimationFrame(this.animation);
    for (const item of this.nodes) { try { item.node.stop(); } catch (_) {} }
    this.nodes.clear();
    for (const frame of [...this.queue, ...this.scheduled]) closeFrame(frame);
    this.queue = this.scheduled = [];
    this.decodeQueue = [];
    for (const frame of this.decoded.values()) closeFrame(frame);
    this.decoded.clear();
    for (const gain of this.gains.values()) gain.disconnect();
    this.gains.clear();
    this.context?.close();
  }
}
