package com.avatar.nputest;

/** Keep reconstructed lip reflectance consistent with this source pose, without temporal filtering. */
final class LipColorMatch {
    private static final int BYTES=256*256*3;
    private static final double[] LINEAR=new double[256];
    private static final double[] LAB_CURVE=new double[16385];
    private static final float[] SPATIAL=new float[54*92];
    static {
        for(int i=0;i<256;i++){
            double v=i/255d;LINEAR[i]=v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);
        }
        for(int i=0;i<LAB_CURVE.length;i++){
            double value=i/16384d;LAB_CURVE[i]=value>.008856?Math.cbrt(value):7.787*value+16d/116;
        }
        for(int y=132;y<186;y++)for(int x=82;x<174;x++){
            double dx=(x-128)/46d,dy=(y-159)/27d;
            SPATIAL[(y-132)*92+x-82]=(float)(1-smooth(.85,1,Math.sqrt(dx*dx+dy*dy)));
        }
    }
    private final double[][] reference;

    LipColorMatch(byte[] sourceFaces,int count){
        if(sourceFaces.length!=count*BYTES)throw new IllegalArgumentException("Face data length");
        reference=new double[count][];
        for(int i=0;i<count;i++)reference[i]=lipMean(sourceFaces,i*BYTES);
    }

    void apply(byte[] rgb,int sourceIndex){
        if(rgb.length!=BYTES)throw new IllegalArgumentException("RGB dimensions");
        double[] target=reference[sourceIndex],current=lipMean(rgb,0);
        if(target==null||current==null)return;
        double red=Math.max(.8,Math.min(1.2,target[0]/current[0]))-1;
        double green=Math.max(.8,Math.min(1.2,target[1]/current[1]))-1;
        double blue=Math.max(.8,Math.min(1.2,target[2]/current[2]))-1;
        for(int y=132;y<186;y++)for(int x=82;x<174;x++){
            double spatial=SPATIAL[(y-132)*92+x-82];if(spatial==0)continue;
            int p=(y*256+x)*3,r=rgb[p]&255,g=rgb[p+1]&255,b=rgb[p+2]&255,sum=r+g+b;
            double score=Math.max(0,255d*(r-Math.max(g,b))/(sum+1));
            double weight=spatial*smooth(25.5,56.1,score)*smooth(96,160,sum);
            if(weight==0)continue; // Protect neutral teeth and the dark cavity within the lip region.
            rgb[p]=pixel(r*(1+weight*red));
            rgb[p+1]=pixel(g*(1+weight*green));
            rgb[p+2]=pixel(b*(1+weight*blue));
        }
    }

    private static double[] lipMean(byte[] rgb,int offset){
        int[] histogram=new int[256],eligible=new int[256],red=new int[256],green=new int[256],blue=new int[256];
        for(int y=132;y<186;y++)for(int x=82;x<174;x++){
            int p=offset+(y*256+x)*3,r=rgb[p]&255,g=rgb[p+1]&255,b=rgb[p+2]&255;
            double lr=LINEAR[r],lg=LINEAR[g],lb=LINEAR[b];
            // D65 CIE Lab a*, used only to select chromatic lip samples.
            // https://docs.opencv.org/4.x/de/d25/imgproc_color_conversions.html
            double xx=(.412453*lr+.357580*lg+.180423*lb)/.950456;
            double yy=.212671*lr+.715160*lg+.072169*lb;
            double fy=labCurve(yy);
            int a=Math.max(0,Math.min(255,(int)Math.rint(500*(labCurve(xx)-fy))+128));
            histogram[a]++;
            if((116*fy-16)*2.55>35){eligible[a]++;red[a]+=r;green[a]+=g;blue[a]+=b;}
        }
        int rank=(int)Math.ceil(.85*(54*92-1)),threshold=0,cumulative=0;
        while(threshold<255&&(cumulative+=histogram[threshold])<=rank)threshold++;
        long n=0,r=0,g=0,b=0;
        for(int a=threshold;a<256;a++){n+=eligible[a];r+=red[a];g+=green[a];b+=blue[a];}
        if(n<100||r==0||g==0||b==0)return null;
        return new double[]{r/(double)n,g/(double)n,b/(double)n};
    }

    private static double labCurve(double value){
        double position=Math.max(0,Math.min(16384,value*16384));int i=Math.min(16383,(int)position);
        return LAB_CURVE[i]+(LAB_CURVE[i+1]-LAB_CURVE[i])*(position-i);
    }
    static int labA(int r,int g,int b){
        double lr=LINEAR[r],lg=LINEAR[g],lb=LINEAR[b];
        double xx=(.412453*lr+.357580*lg+.180423*lb)/.950456;
        double yy=.212671*lr+.715160*lg+.072169*lb;
        return Math.max(0,Math.min(255,(int)Math.rint(500*(labCurve(xx)-labCurve(yy)))+128));
    }
    private static double smooth(double low,double high,double value){double t=Math.max(0,Math.min(1,(value-low)/(high-low)));return t*t*(3-2*t);}
    private static byte pixel(double value){return (byte)Math.rint(Math.max(0,Math.min(255,value)));}
}
