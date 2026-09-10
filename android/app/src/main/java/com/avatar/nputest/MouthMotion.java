package com.avatar.nputest;

/** Derives a small current-frame mouth warp while retaining only geometric history. */
final class MouthMotion {
    private static final int WIDTH=256, X0=82, X1=174, Y0=132, Y1=186;
    private static final float[] IDENTITY={1,1,0,0};
    private final float[] source;
    private boolean ready;
    private double left,right,top,bottom,bias;
    private double centerX,centerY,width,height,narrowing;

    MouthMotion(byte[] sourceFaces,int frameCount){
        if(sourceFaces.length!=frameCount*WIDTH*WIDTH*3)throw new IllegalArgumentException("Face data length");
        source=new float[frameCount*4];
        for(int i=0;i<frameCount;i++){
            double[] value=bounds(sourceFaces,i*WIDTH*WIDTH*3);
            for(int edge=0;edge<4;edge++)source[i*4+edge]=(float)value[edge];
        }
    }

    float[] update(byte[] rgb,int sourceIndex,float blend){
        if(rgb.length!=WIDTH*WIDTH*3||sourceIndex<0||sourceIndex*4+3>=source.length)
            throw new IllegalArgumentException("Mouth frame");
        if(blend<.99f)reset();
        double[] current=bounds(rgb,0);
        int at=sourceIndex*4;
        if(!finite(current)||!finite(source,at)){reset();return IDENTITY.clone();}
        double cl=current[0],cr=current[1],ct=current[2],cb=current[3];
        double sl=source[at],sr=source[at+1],st=source[at+2],sb=source[at+3];
        double rl=cl-sl,rr=cr-sr,rt=ct-st,rb=cb-sb;
        double wanted=clamp(.65*(ct-st),-1.5,1.5);
        if(!ready){left=rl;right=rr;top=rt;bottom=rb;bias=wanted;ready=true;}
        else {
            left=filtered(rl,left,.4);right=filtered(rr,right,.4);
            // Vertical aperture carries articulation: damp it less than corner motion.
            top=filtered(rt,top,.25);bottom=filtered(rb,bottom,.25);
            bias=.85*bias+.15*wanted;
        }
        centerX=(cl+cr)*.5;centerY=(ct+cb)*.5;width=cr-cl;height=cb-ct;
        narrowing=clamp(.25*(width-(sr-sl)),0,1)*.5;
        return forSource(sourceIndex,blend);
    }

    /** Reused RGB still needs the reference pose of every displayed source-video frame. */
    float[] forSource(int sourceIndex){
        return forSource(sourceIndex,1);
    }

    /** Enter and leave speech at the current source mouth, including reused tail RGB. */
    float[] forSource(int sourceIndex,float blend){
        int at=sourceIndex*4;
        if(!ready||at<0||at+3>=source.length||!finite(source,at))return IDENTITY.clone();
        double amount=clamp(blend,0,1);
        double tl=source[at]+amount*(left+narrowing),tr=source[at+1]+amount*(right-narrowing);
        double tt=source[at+2]+amount*(top-bias),tb=source[at+3]+amount*(bottom-bias);
        double targetWidth=tr-tl,targetHeight=tb-tt;
        if(targetWidth<1||targetHeight<1)return IDENTITY.clone();
        double scaleX=width/targetWidth,scaleY=height/targetHeight;
        double offsetX=centerX-scaleX*(tl+tr)*.5;
        double offsetY=centerY-scaleY*(tt+tb)*.5;
        return new float[]{(float)scaleX,(float)scaleY,(float)offsetX,(float)offsetY};
    }

    void reset(){ready=false;left=right=top=bottom=bias=0;}

    /** Original generated mouth and the current video mouth, for natural release only. */
    float[] releaseBounds(int sourceIndex){
        int at=sourceIndex*4;
        if(!ready||at<0||at+3>=source.length||!finite(source,at))return null;
        return new float[]{(float)(centerX-width*.5),(float)(centerX+width*.5),
                (float)(centerY-height*.5),(float)(centerY+height*.5),
                source[at],source[at+1],source[at+2],source[at+3]};
    }

    static double[] bounds(byte[] rgb,int offset){
        int[] histogram=new int[256];
        int[] chroma=new int[(X1-X0)*(Y1-Y0)];
        for(int y=Y0;y<Y1;y++)for(int x=X0;x<X1;x++){
            int p=offset+(y*WIDTH+x)*3;
            int a=LipColorMatch.labA(rgb[p]&255,rgb[p+1]&255,rgb[p+2]&255);
            chroma[(y-Y0)*(X1-X0)+x-X0]=a;histogram[a]++;
        }
        int median=0,total=0;
        while(median<255&&(total+=histogram[median])<chroma.length/2)median++;
        double[] columns=new double[X1-X0],rows=new double[Y1-Y0];double mass=0;
        for(int y=0;y<rows.length;y++)for(int x=0;x<columns.length;x++){
            double weight=Math.max(0,chroma[y*columns.length+x]-median-8);
            columns[x]+=weight;rows[y]+=weight;mass+=weight;
        }
        if(mass<500)return new double[]{Double.NaN,Double.NaN,Double.NaN,Double.NaN};
        return new double[]{quantile(columns,.05,X0),quantile(columns,.95,X0),
                quantile(rows,.05,Y0),quantile(rows,.95,Y0)};
    }

    private static double quantile(double[] weights,double fraction,int origin){
        double mass=0;for(double weight:weights)mass+=weight;
        double rank=mass*fraction,before=0;
        for(int i=0;i<weights.length;i++)if(weights[i]>0&&before+weights[i]>=rank)
            return origin+i-.5+(rank-before)/weights[i];else before+=weights[i];
        return Double.NaN;
    }

    private static double filtered(double current,double previous,double strength){
        double d=current-previous;
        double oldWeight=strength*(1-smooth(1.5,4,Math.abs(d)));
        return current-clamp(oldWeight*d,-.75,.75);
    }
    private static double smooth(double low,double high,double value){
        double t=clamp((value-low)/(high-low),0,1);return t*t*(3-2*t);
    }
    private static double clamp(double value,double low,double high){return Math.max(low,Math.min(high,value));}
    private static boolean finite(double[] value){for(double v:value)if(!Double.isFinite(v))return false;return true;}
    private static boolean finite(float[] value,int offset){for(int i=0;i<4;i++)if(!Float.isFinite(value[offset+i]))return false;return true;}
}
