package com.avatar.nputest;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded media queue. AudioTrack's played sample counter paces every displayed frame. */
public final class PlaybackEngine implements AutoCloseable {
    public interface Listener {
        void onPresented(long id,long phase,boolean generated);
        void onEnded(long id);
        void onFailure(String message,Throwable error);
    }
    private static final float[] SILENCE=new float[640];
    private final ArrayBlockingQueue<Frame> queue=new ArrayBlockingQueue<>(20);
    private final AtomicLong active=new AtomicLong();
    private final Object admission=new Object();
    private final AvatarView view;
    private final Listener listener;
    private final Thread thread;
    private volatile boolean closed,paused,surfaceReady;
    private volatile long nextPhase;
    private volatile int underruns;
    private final List<long[]> underrunEvents=new ArrayList<>();

    private static final class Frame {
        final long id,phase;
        final float[] audio;
        final byte[] face;
        final float blend;
        final boolean generated,end;
        final AtomicBoolean callbackClaimed=new AtomicBoolean();
        long sampleStart;
        int audioWritten;
        byte[] presentationFace;
        float presentationBlend;
        boolean presentationRelease;
        long presentationEpoch;
        volatile long presentationGeneration=-1;
        volatile boolean presented;
        Frame(long id,long phase,float[] audio,byte[] face,float blend,boolean generated,boolean end) {
            this.id=id;this.phase=phase;this.audio=audio;this.face=face;this.blend=blend;this.generated=generated;this.end=end;
        }
    }
    public PlaybackEngine(AvatarView view,Listener listener) {
        this.view=view;this.listener=listener;
        surfaceReady=view.isReadyForRendering();
        thread=new Thread(this::play,"avatar-playback");thread.start();
    }
    public void active(long id){synchronized(admission){active.set(id);}}
    public void pause(boolean value){paused=value;}
    public void setSurfaceReady(boolean value){surfaceReady=value;}
    public long nextPhase(){return nextPhase;}
    public int queued(){return queue.size();}
    public int underruns(){return underruns;}
    public List<long[]> underrunEvents(){synchronized(underrunEvents){return new ArrayList<>(underrunEvents);}}

    /** Only the media producer calls this; phase advances only after successful admission. */
    public boolean submit(long id,float[] audio,byte[] face,float blend,boolean generated) throws InterruptedException {
        if(audio==null||audio.length==0||audio.length>640)throw new IllegalArgumentException("audio frame must contain 1..640 samples");
        while(!closed) {
            synchronized(admission) {
                if(id!=0&&active.get()!=id)return false;
                Frame frame=new Frame(id,nextPhase,audio,face,blend,generated,false);
                if(queue.offer(frame)){nextPhase++;return true;}
            }
            Thread.sleep(5);
        }
        return false;
    }
    public void idle()throws InterruptedException{submit(0,SILENCE,null,0,false);}
    public void end(long id)throws InterruptedException{
        Frame marker;
        synchronized(admission){marker=new Frame(id,nextPhase,new float[0],null,0,false,true);}
        while(!closed && active.get()==id)if(queue.offer(marker,20,TimeUnit.MILLISECONDS))return;
    }

    static float[] mediaSlot(float[] actual) {
        if(actual==null||actual.length==0||actual.length>640)throw new IllegalArgumentException("audio frame must contain 1..640 samples");
        if(actual.length==640)return actual;
        float[] slot=new float[640];
        System.arraycopy(actual,0,slot,0,actual.length);
        return slot;
    }

    private void play() {
        AudioTrack track=null;
        try {
            int minimum=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);
            track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum,640*4*4)).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("AudioTrack initialization");
            track.setBufferSizeInFrames(640*3);track.play();
            ArrayDeque<Frame> pending=new ArrayDeque<>();
            long written=0,epoch=active.get(),naturalEnd=0;
            byte[] lastFace=null;float lastBlend=0;
            boolean wasPaused=false;
            long lastUnderrunCheck=0;
            while(!closed) {
                long now=SystemClock.elapsedRealtimeNanos();
                if(now-lastUnderrunCheck>=40_000_000L){
                    int total=track.getUnderrunCount();
                    if(total>underruns)synchronized(underrunEvents){if(underrunEvents.size()<128)underrunEvents.add(new long[]{now,total});}
                    underruns=total;lastUnderrunCheck=now;
                }
                if(epoch!=active.get()) {
                    track.pause();track.flush();written=0;epoch=active.get();wasPaused=true;
                    // Cancellation discards old audio, while every admitted video phase remains.
                    for(Frame frame:pending){frame.sampleStart=-1;frame.audioWritten=0;}
                    lastFace=null;lastBlend=0;naturalEnd=0;
                }
                if(paused||!surfaceReady) {
                    // A temporary Surface pause must retain partially played/written PCM.
                    if(!wasPaused){track.pause();wasPaused=true;}
                    Thread.sleep(10);continue;
                }
                if(wasPaused){track.play();wasPaused=false;}
                while(!pending.isEmpty()&&pending.peek().presented)pending.remove();
                // Only unconfirmed renders need extra ownership; keep the original three future slots.
                int retained=0;for(Frame frame:pending)if(frame.presentationGeneration>=0&&!frame.presented)retained++;
                while(pending.size()<Math.min(6,3+retained)) {
                    Frame frame=queue.poll();if(frame==null)break;
                    frame.sampleStart=-1;pending.add(frame);
                    if(frame.end)break;
                }
                boolean restart=false;
                writeAudio: for(Frame frame:pending) {
                    if(frame.sampleStart<0)frame.sampleStart=written;
                    if(frame.end)continue;
                    if(frame.audioWritten==640)continue;
                    float[] samples=frame.id!=0&&frame.id==epoch?mediaSlot(frame.audio):SILENCE;
                    while(frame.audioWritten<samples.length&&!closed) {
                        if(paused||!surfaceReady||epoch!=active.get()){restart=true;break writeAudio;}
                        int size=track.write(samples,frame.audioWritten,samples.length-frame.audioWritten,AudioTrack.WRITE_NON_BLOCKING);
                        if(size<0)throw new IllegalStateException("Audio write failed: "+size);
                        frame.audioWritten+=size;written+=size;
                        if(size==0)break writeAudio;
                    }
                }
                if(restart)continue;
                long played=Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                for(Frame frame:pending) {
                    if(frame.sampleStart<0||played<frame.sampleStart)break;
                    if(frame.end){
                        if(frame==pending.peek()){
                            pending.remove();
                            if(frame.id==active.get()){naturalEnd=frame.id;listener.onEnded(frame.id);}
                        }
                        break;
                    }
                    if(frame.audioWritten<640)break;
                    if(frame.presented||frame.presentationGeneration==view.renderingGeneration())continue;
                    boolean speaking=frame.id!=0&&frame.id==epoch;
                    boolean release=!speaking&&naturalEnd!=0&&naturalEnd==epoch;
                    byte[] face=speaking?frame.face:lastFace;
                    float blend=speaking?frame.blend:release?lastBlend:Math.max(0,lastBlend-.2f);
                    boolean generated=speaking&&frame.generated;
                    long id=speaking?frame.id:0;
                    boolean firstAdmission=frame.presentationGeneration<0;
                    if(!requestPresentation(frame,face,blend,release,id,generated,epoch))break;
                    if(firstAdmission){lastFace=face;lastBlend=blend;}
                }
                Thread.sleep(2);
            }
        } catch(InterruptedException ignored) {
        } catch(Throwable error) {
            if(!closed)listener.onFailure("播放已停止",error);
        } finally {
            if(track!=null){try{track.pause();track.flush();}catch(Exception ignored){}track.release();}
        }
    }

    private boolean requestPresentation(Frame frame,byte[] face,float blend,boolean release,long id,boolean generated,long epoch)throws InterruptedException {
        if(!surfaceReady||!view.isReadyForRendering())return false;
        try {
            boolean retry=frame.presentationGeneration>=0;
            byte[] selectedFace=retry?frame.presentationFace:face;
            float selectedBlend=retry?frame.presentationBlend:blend;
            boolean selectedRelease=retry?frame.presentationRelease:release;
            long selectedEpoch=retry?frame.presentationEpoch:epoch;
            boolean selectedSpeaking=frame.id!=0&&frame.id==selectedEpoch;
            long generation=view.tryRenderPlayback(frame.phase,selectedFace,selectedBlend,selectedRelease,selectedSpeaking,()->!closed&&active.get()==selectedEpoch,()->{
                if(!frame.callbackClaimed.compareAndSet(false,true))return;
                try{listener.onPresented(id,frame.phase,generated&&active.get()==selectedEpoch);}
                finally{frame.presented=true;}
            });
            if(generation<0)return false;
            frame.presentationFace=selectedFace;frame.presentationBlend=selectedBlend;frame.presentationEpoch=selectedEpoch;
            frame.presentationRelease=selectedRelease;
            frame.presentationGeneration=generation;
            return true;
        } catch(IllegalStateException error) {
            // AvatarView uses IllegalStateException for lifecycle admission races.
            // Permanent pipeline failures arrive through Listener.onError.
            return false;
        }
    }
    @Override public void close(){closed=true;active.set(0);queue.clear();thread.interrupt();}
}
