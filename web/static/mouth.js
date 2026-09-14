// Simple native Canvas compositing; no mobile mouth geometry or pixel loops.
export class DisplayedMouth {
  constructor() { this.cancelledThrough=0; this.clear(); }
  clear() { this.face?.close(); this.face=null; this.turn=0; this.blend=0; this.releaseStart=null; }
  cancel(turn) { this.cancelledThrough=Math.max(this.cancelledThrough,turn); if(this.turn<=this.cancelledThrough)this.clear(); }
  display(frame,now) {
    if((frame.turn&&frame.turn<=this.cancelledThrough)||(frame.meta.end_turn&&frame.meta.end_turn<=this.cancelledThrough))return null;
    if(frame.turn) {
      if(frame.turn!==this.turn)this.clear();
      this.face?.close(); this.face=frame.face; frame.face=null;
      this.turn=frame.turn; this.blend=Math.min(1,this.blend+.2); this.releaseStart=null;
    } else {
      if(!this.face || (frame.meta.end_turn&&frame.meta.end_turn!==this.turn))return null;
      if(this.releaseStart===null)this.releaseStart=now;
      if(now-this.releaseStart>=200){this.clear();return null;}
    }
    return this.face ? {face:this.face,blend:this.blend*(this.releaseStart===null?1:Math.max(0,1-(now-this.releaseStart)/200))} : null;
  }
}

export class FaceCompositor {
  constructor(){this.canvas=new OffscreenCanvas(1,1);this.context=this.canvas.getContext('2d');}
  paint(context,frame,draw) {
    const [x0,y0,x1,y1]=frame.meta.box,w=x1-x0,h=y1-y0;
    if(this.canvas.width!==w)this.canvas.width=w;
    if(this.canvas.height!==h)this.canvas.height=h;
    const c=this.context,[a,b,tx,d,e,ty]=frame.meta.affine,det=a*e-b*d;
    c.setTransform(1,0,0,1,0,0);c.globalCompositeOperation='source-over';c.clearRect(0,0,w,h);
    // Inverse local-video -> trained 210x280 crop, including the 256x256 model resize.
    c.setTransform(e/det*210/256,-d/det*210/256,-b/det*280/256,a/det*280/256,
                   (b*ty-e*tx)/det,(d*tx-a*ty)/det);
    c.drawImage(draw.face,0,0);
    c.setTransform(1,0,0,1,0,0);c.globalCompositeOperation='destination-in';
    c.drawImage(frame.mask,0,0);c.globalCompositeOperation='source-over';
    context.save();context.globalAlpha=draw.blend;context.drawImage(this.canvas,x0,y0);context.restore();
  }
}

