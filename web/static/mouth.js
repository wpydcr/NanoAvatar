// MouthMotion and sampling follow Android MouthMotion / AvatarView.
const clamp = (v, low, high) => Math.max(low, Math.min(high, v));
const smooth = (low, high, v) => { const t=clamp((v-low)/(high-low),0,1); return t*t*(3-2*t); };
const identity = () => [1,1,0,0];
const valid = a => a?.length === 4 && a.every(Number.isFinite) && a[1]>a[0] && a[3]>a[2];
const linear = Array.from({length:256},(_,i)=>i/255<=.04045?i/255/12.92:((i/255+.055)/1.055)**2.4);
const curve = Array.from({length:16385},(_,i)=>i/16384>.008856?Math.cbrt(i/16384):7.787*i/16384+16/116);
const labCurve = v => { const p=clamp(v*16384,0,16384),i=Math.min(16383,Math.floor(p));return curve[i]+(curve[i+1]-curve[i])*(p-i); };
const evenRound = v => { const floor=Math.floor(v); return v-floor===.5 ? floor+(floor%2!==0?1:0) : Math.round(v); };
export function mouthBounds(rgba) {
  const hist=new Uint32Array(256),chroma=[];
  for(let y=132;y<186;y++)for(let x=82;x<174;x++){
    const p=(y*256+x)*4,r=linear[rgba[p]],g=linear[rgba[p+1]],b=linear[rgba[p+2]];
    const a=clamp(evenRound(500*(labCurve((.412453*r+.357580*g+.180423*b)/.950456)-labCurve(.212671*r+.715160*g+.072169*b)))+128,0,255);
    chroma.push(a);hist[a]++;
  }
  let median=0,total=0;while(median<255&&(total+=hist[median])<chroma.length/2)median++;
  const cols=new Float64Array(92),rows=new Float64Array(54);let mass=0;
  for(let y=0;y<54;y++)for(let x=0;x<92;x++){const w=Math.max(0,chroma[y*92+x]-median-8);cols[x]+=w;rows[y]+=w;mass+=w;}
  if(mass<500)return null;
  const quantile=(values,fraction,origin)=>{let before=0;const rank=mass*fraction;for(let i=0;i<values.length;i++){if(values[i]>0&&before+values[i]>=rank)return origin+i-.5+(rank-before)/values[i];before+=values[i];}return NaN;};
  return [quantile(cols,.05,82),quantile(cols,.95,82),quantile(rows,.05,132),quantile(rows,.95,132)];
}

export class MouthMotion {
  reset(){this.current=null;this.residual=null;this.bias=0;}
  update(rgba,source,blend){
    if(blend<.99)this.reset();
    const current=mouthBounds(rgba);
    if(!valid(current)||!valid(source)){this.reset();return identity();}
    const residual=current.map((v,i)=>v-source[i]),wanted=clamp(.65*residual[2],-1.5,1.5);
    if(!this.residual){this.residual=residual;this.bias=wanted;}
    else {
      this.residual=residual.map((v,i)=>{const d=v-this.residual[i],old=(i<2?.4:.25)*(1-smooth(1.5,4,Math.abs(d)));return v-clamp(old*d,-.75,.75);});
      this.bias=.85*this.bias+.15*wanted;
    }
    this.current=current;
    this.narrowing=clamp(.25*(current[1]-current[0]-(source[1]-source[0])),0,1)*.5;
    return this.forSource(source,blend);
  }
  forSource(source,blend){
    if(!this.current||!valid(source))return identity();
    const amount=clamp(blend,0,1),r=this.residual;
    const target=[source[0]+amount*(r[0]+this.narrowing),source[1]+amount*(r[1]-this.narrowing),source[2]+amount*(r[2]-this.bias),source[3]+amount*(r[3]-this.bias)];
    const [l,right,t,b]=this.current,w=target[1]-target[0],h=target[3]-target[2];
    if(w<1||h<1)return identity();
    const sx=(right-l)/w,sy=(b-t)/h;
    return [sx,sy,(l+right)/2-sx*(target[0]+target[1])/2,(t+b)/2-sy*(target[2]+target[3])/2];
  }
}

// Called only for the frame that will actually be painted, never decoded/dropped frames.
export class DisplayedMouth {
  constructor(){this.motion=new MouthMotion();this.cancelledThrough=0;this.clear();}
  clear(){this.face=null;this.blend=0;this.warp=identity();this.turn=0;this.releaseStart=null;this.measured=null;this.motion.reset();}
  cancel(turn){this.cancelledThrough=Math.max(this.cancelledThrough,turn);if(this.turn<=this.cancelledThrough)this.clear();}
  display(frame,now){
    const m=frame.meta,source=m.source_bounds;
    if((frame.turn&&frame.turn<=this.cancelledThrough)||(m.end_turn&&m.end_turn<=this.cancelledThrough)){
      if(this.turn<=this.cancelledThrough)this.clear();
      return null;
    }
    if(m.kind==='end'){
      if(!this.face||m.end_turn!==this.turn)return null;
      const elapsed=this.releaseStart===null?0:Math.max(0,now-this.releaseStart);
      const blend=this.blend*clamp((400-elapsed)/200,0,1);
      if(blend<=0){this.clear();return null;}
      return {face:this.face,blend,warp:this.warp,shape:clamp(elapsed/200,0,1),generated:this.motion.current,source};
    }
    this.releaseStart=null;
    if(m.kind==='speech'&&frame.turn!==this.turn)this.clear();
    const speaking=m.kind==='speech';
    const face=speaking?frame.face:this.face;
    const blend=Math.min(m.blend,clamp(this.blend+(speaking?.2:-.2),0,1));
    if(!face||blend<=0){this.clear();return null;}
    const changed=!this.measured||face.length!==this.measured.length||face.some((v,i)=>v!==this.measured[i]);
    this.warp=changed?this.motion.update(face,source,blend):this.motion.forSource(source,blend);
    this.measured=face;this.face=face;this.blend=blend;if(speaking)this.turn=frame.turn;
    return {face,blend,warp:this.warp,shape:0,generated:null,source};
  }
  committed(kind,now){if(kind==='end'&&this.face&&this.releaseStart===null)this.releaseStart=now;}
}

export function releaseAxis(p,low,high,generatedLow,generatedHigh){
  if(p<low)return p*generatedLow/low;
  if(p>high)return generatedHigh+(p-high)*(255-generatedHigh)/(255-high);
  return generatedLow+(p-low)*(generatedHigh-generatedLow)/(high-low);
}

export function samplePosition(x,y,draw){
  const [sx,sy,tx,ty]=draw.warp;
  const weight=y>=186?0:1-smooth(.85,1,Math.hypot((x-128)/46,(y-159)/27));
  let px=x+weight*(sx*x+tx-x),py=y+weight*(Math.min(sy*y+ty,185)-y);
  if(draw.shape>0&&valid(draw.generated)&&valid(draw.source)){
    const g=draw.generated,s=draw.source;
    px+=(releaseAxis(x,s[0],s[1],g[0],g[1])-px)*draw.shape;
    py+=(releaseAxis(y,s[2],s[3],g[2],g[3])-py)*draw.shape;
  }
  return [px,py];
}

export function compositeFace(context,frame,draw){
  const [x0,y0,x1,y1]=frame.meta.box,w=x1-x0,h=y1-y0,a=frame.meta.affine;
  const image=context.getImageData(x0,y0,w,h),pixels=image.data,face=draw.face,mask=frame.mask;
  for(let y=0;y<h;y++)for(let x=0;x<w;x++){
    const at=(y*w+x)*4,alpha=mask[at]/255*draw.blend;if(!alpha)continue;
    const ax=((a[0]*x+a[1]*y+a[2]+.5)/210)*256-.5;
    const ay=((a[3]*x+a[4]*y+a[5]+.5)/280)*256-.5;
    let [px,py]=samplePosition(ax,ay,draw);
    if(px<-.5||py<-.5||px>255.5||py>255.5)continue;
    px=clamp(px,0,255);py=clamp(py,0,255);
    const left=Math.floor(px),top=Math.floor(py),right=Math.min(left+1,255),bottom=Math.min(top+1,255),dx=px-left,dy=py-top;
    for(let c=0;c<3;c++){
      const value=(face[(top*256+left)*4+c]*(1-dx)+face[(top*256+right)*4+c]*dx)*(1-dy)+(face[(bottom*256+left)*4+c]*(1-dx)+face[(bottom*256+right)*4+c]*dx)*dy;
      pixels[at+c]=pixels[at+c]*(1-alpha)+value*alpha;
    }
  }
  context.putImageData(image,x0,y0);
}
