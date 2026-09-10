package com.avatar.nputest;

/** Shared 25 Hz media phase to 24 Hz ping-pong avatar indices. */
final class AvatarTimeline {
    private AvatarTimeline() { }

    static int loopFrames(int frameCount) {
        long frames=2L*(frameCount-1L);
        if(frameCount<2||frames>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid avatar frame count");
        return (int)frames;
    }

    static int videoIndex(long phase,int frameCount) {
        if(phase<0)throw new IllegalArgumentException("phase must be non-negative");
        // floor(phase*24/25), without overflowing at large media phases.
        long logical=phase-phase/25L-(phase%25L==0?0:1);
        return (int)(logical%loopFrames(frameCount));
    }

    static int sourceIndex(int videoIndex,int frameCount) {
        int loop=loopFrames(frameCount);
        if(videoIndex<0||videoIndex>=loop)throw new IllegalArgumentException("Video index outside avatar loop");
        return videoIndex<frameCount?videoIndex:loop-videoIndex;
    }
}
