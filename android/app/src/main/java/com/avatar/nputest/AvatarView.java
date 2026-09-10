package com.avatar.nputest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Displays the source video and restores generated 256x256 RGB faces into it. */
public final class AvatarView extends TextureView implements AutoCloseable,
        TextureView.SurfaceTextureListener {
    private static final String TAG = "AvatarView";
    private static final int FACE_SIZE = 256;
    private static final int FACE_BYTES = FACE_SIZE * FACE_SIZE * 3;
    private static final int QUEUE_CAPACITY = 2;

    public interface Listener {
        void onReady();
        void onError(String safeMessage);
        default void onUnavailable() { }
    }

    private static final class Request {
        final long phase;
        final byte[] face;
        final float blend;
        final boolean naturalRelease;
        // Null preserves explicit render opacity; playback supplies its existing speech/idle fact.
        final Boolean speaking;
        final Runnable presented;
        final BooleanSupplier faceValid;
        final long generation;
        final SurfaceTexture surface;

        Request(long phase, byte[] face, float blend, boolean naturalRelease, Boolean speaking, BooleanSupplier faceValid, Runnable presented,
                long generation, SurfaceTexture surface) {
            this.phase = phase;
            this.face = face;
            this.blend = blend;
            this.naturalRelease = naturalRelease;
            this.speaking = speaking;
            this.presented = presented;
            this.faceValid = faceValid;
            this.generation = generation;
            this.surface = surface;
        }
    }

    private static final class FrameSignal {
        boolean available;
        boolean retired;
    }

    private final Object stateLock = new Object();
    private final ArrayDeque<Request> requests = new ArrayDeque<>(QUEUE_CAPACITY);
    private final ArrayDeque<SurfaceTexture> retiredSurfaces = new ArrayDeque<>();
    private final RenderWorker worker = new RenderWorker();
    private volatile boolean closed;
    private volatile boolean renderFailed;
    private volatile boolean readyForRendering;
    private volatile long lifecycleGeneration;
    private File requestedDirectory;
    private Listener requestedListener;
    private long requestedGeneration;
    private SurfaceTexture requestedSurface;
    private int requestedSurfaceWidth;
    private int requestedSurfaceHeight;
    private boolean workerAcceptsSurfaceHandoffs = true;

    public AvatarView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
        worker.start();
    }

    public void open(File assetDirectory, Listener listener) {
        if (assetDirectory == null || listener == null) {
            throw new NullPointerException("assetDirectory and listener are required");
        }
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("AvatarView is closed");
            if (requestedDirectory != null) throw new IllegalStateException("AvatarView is already open");
            requestedDirectory = assetDirectory;
            requestedListener = listener;
            readyForRendering = false;
            advanceGenerationLocked();
            stateLock.notifyAll();
        }
    }

    /**
     * Applies bounded backpressure until admitted. onPresented runs inline on the render thread
     * after a successful swap and must stay short; calling render from any AvatarView callback
     * is rejected to prevent self-deadlock.
     */
    public void render(long phase, byte[] faceRgb, float blend, Runnable onPresented)
            throws InterruptedException {
        render(phase, faceRgb, blend, () -> true, onPresented);
    }

    /** Returns the accepted Surface generation; face validity independently belongs to speech. */
    public long render(long phase, byte[] faceRgb, float blend, BooleanSupplier faceValid,
            Runnable onPresented) throws InterruptedException {
        return render(phase, faceRgb, blend, false, null, faceValid, onPresented, true);
    }

    /** Playback must remain able to service audio and cancellation when the render queue is full. */
    public long tryRender(long phase, byte[] faceRgb, float blend, BooleanSupplier faceValid,
            Runnable onPresented) throws InterruptedException {
        return tryRender(phase, faceRgb, blend, false, faceValid, onPresented);
    }

    public long tryRender(long phase, byte[] faceRgb, float blend, boolean naturalRelease,
            BooleanSupplier faceValid, Runnable onPresented) throws InterruptedException {
        return render(phase, faceRgb, blend, naturalRelease, null, faceValid, onPresented, false);
    }

    long tryRenderPlayback(long phase, byte[] faceRgb, float blend, boolean naturalRelease, boolean speaking,
            BooleanSupplier faceValid, Runnable onPresented) throws InterruptedException {
        return render(phase, faceRgb, blend, naturalRelease, speaking, faceValid, onPresented, false);
    }

    private long render(long phase, byte[] faceRgb, float blend, boolean naturalRelease, Boolean speaking, BooleanSupplier faceValid,
            Runnable onPresented, boolean waitForCapacity) throws InterruptedException {
        if (faceValid == null) throw new NullPointerException("faceValid");
        if (Thread.currentThread() == worker) {
            throw new IllegalStateException("render cannot be called from an AvatarView callback");
        }
        if (phase < 0) throw new IllegalArgumentException("phase must be non-negative");
        if (!Float.isFinite(blend)) throw new IllegalArgumentException("blend must be finite");
        float strength = Math.max(0f, Math.min(1f, blend));
        if (faceRgb != null && strength > 0f) {
            if (faceRgb.length != FACE_BYTES) {
                throw new IllegalArgumentException("faceRgb must contain 256x256 RGB bytes");
            }
        }
        synchronized (stateLock) {
            for (;;) {
                ensureRenderAllowedLocked();
                if (requests.size() < QUEUE_CAPACITY) {
                    // Release owns the last successful swap; idle requests need no additional RGB copy.
                    byte[] immutableFace = !naturalRelease && faceRgb != null && strength > 0f ? faceRgb.clone() : null;
                    requests.addLast(new Request(phase, immutableFace, strength, naturalRelease, speaking, faceValid, onPresented,
                            requestedGeneration, requestedSurface));
                    stateLock.notifyAll();
                    return requestedGeneration;
                }
                if (!waitForCapacity) return -1;
                stateLock.wait();
            }
        }
    }

    /** True only while the current TextureView surface has a live EGL/decoder pipeline. */
    public boolean isReadyForRendering() {
        return readyForRendering;
    }

    /** Identifies the actual rendering target, including changes before Activity callbacks. */
    public long renderingGeneration() {
        return lifecycleGeneration;
    }

    @Override public void close() {
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            readyForRendering = false;
            advanceGenerationLocked();
            requests.clear();
            stateLock.notifyAll();
        }
        worker.cancelFrameWait();
        worker.interrupt();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        synchronized (stateLock) {
            if (closed) return;
            requestedSurface = surface;
            requestedSurfaceWidth = width;
            requestedSurfaceHeight = height;
            readyForRendering = false;
            advanceGenerationLocked();
            requests.clear();
            stateLock.notifyAll();
        }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        synchronized (stateLock) {
            if (closed || surface != requestedSurface) return;
            requestedSurfaceWidth = width;
            requestedSurfaceHeight = height;
            stateLock.notifyAll();
        }
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        boolean handedOff = false;
        Listener unavailable = null;
        synchronized (stateLock) {
            if (surface == requestedSurface) {
                readyForRendering = false;
                requestedSurface = null;
                requestedSurfaceWidth = 0;
                requestedSurfaceHeight = 0;
                advanceGenerationLocked();
                requests.clear();
                stateLock.notifyAll();
                if (workerAcceptsSurfaceHandoffs) {
                    retiredSurfaces.addLast(surface);
                    handedOff = true;
                }
                unavailable = requestedListener;
            }
        }
        worker.cancelFrameWait();
        notifyUnavailable(unavailable);
        return !handedOff;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    private void ensureRenderAllowedLocked() {
        if (closed) throw new IllegalStateException("AvatarView is closed");
        if (renderFailed) throw new IllegalStateException("AvatarView renderer is unavailable");
        if (!readyForRendering) throw new IllegalStateException("AvatarView renderer is not ready");
        if (requestedDirectory == null) throw new IllegalStateException("AvatarView is not open");
        if (requestedSurface == null) throw new IllegalStateException("AvatarView surface is unavailable");
    }

    private void advanceGenerationLocked() {
        lifecycleGeneration = ++requestedGeneration;
    }

    private final class RenderWorker extends Thread {
        private long generation = -1;
        private long failedGeneration = -1;
        private File directory;
        private Listener listener;
        private SurfaceTexture windowTexture;
        private int windowWidth;
        private int windowHeight;
        private Metadata metadata;

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private SurfaceTexture decoderTexture;
        private Surface decoderSurface;
        private HandlerThread frameCallbackThread;
        private volatile FrameSignal frameSignal;
        private final float[] decoderTransform = new float[16];

        private MediaExtractor extractor;
        private MediaCodec codec;
        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        private boolean inputEnded;
        private int decodedIndex = -1;

        private int videoProgram;
        private int faceProgram;
        private int externalTexture;
        private int faceTexture;
        private MouthMotion mouthMotion;
        private byte[] measuredFace;
        private float[] mouthTransform = {1,1,0,0};
        private byte[] displayedFace;
        private float displayedBlend;
        private float[] displayedMouth = {1,1,0,0};
        private long releaseStartedNs;
        private final LinkedHashMap<Integer, Integer> maskTextures =
                new LinkedHashMap<Integer, Integer>(8, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                        if (size() <= 8) return false;
                        int[] texture = {eldest.getValue()};
                        GLES20.glDeleteTextures(1, texture, 0);
                        return true;
                    }
                };
        private final FloatBuffer videoVertices = floats(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f);
        private final FloatBuffer faceVertices = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final ByteBuffer facePixels = ByteBuffer.allocateDirect(FACE_BYTES);

        RenderWorker() {
            super("avatar-render");
        }

        @Override public void run() {
            try {
                while (!closed) {
                    refreshConfiguration();
                    if (closed) break;
                    if (!isReady()) {
                        synchronized (stateLock) {
                            if (!closed) stateLock.wait(100);
                        }
                        continue;
                    }
                    Request request = nextRequest();
                    if (request != null) {
                        try {
                            present(request);
                        } catch (Throwable error) {
                            synchronized (stateLock) {
                                if (closed) break;
                                if (requestedSurface != windowTexture
                                        || requestedGeneration != generation) continue;
                            }
                            throw error;
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                // close() interrupts decoder waits and queue waits.
            } catch (Throwable error) {
                Log.e(TAG, "Render worker stopped", error);
                synchronized (stateLock) {
                    renderFailed = true;
                    readyForRendering = false;
                    requests.clear();
                    stateLock.notifyAll();
                }
                failedGeneration = generation;
                notifyError(listener, "视频渲染已停止");
            } finally {
                try { releasePipeline(); }
                finally { finishSurfaceHandoffs(); }
            }
        }

        private Request nextRequest() throws InterruptedException {
            synchronized (stateLock) {
                if (requests.isEmpty()) {
                    stateLock.wait(100);
                    return null;
                }
                Request request = requests.removeFirst();
                stateLock.notifyAll();
                return request;
            }
        }

        private void refreshConfiguration() {
            File wantedDirectory;
            Listener wantedListener;
            long wantedGeneration;
            SurfaceTexture wantedSurface;
            int wantedWidth;
            int wantedHeight;
            synchronized (stateLock) {
                wantedDirectory = requestedDirectory;
                wantedListener = requestedListener;
                wantedGeneration = requestedGeneration;
                wantedSurface = requestedSurface;
                wantedWidth = requestedSurfaceWidth;
                wantedHeight = requestedSurfaceHeight;
            }

            boolean assetChanged = wantedGeneration != generation;
            boolean surfaceChanged = wantedSurface != windowTexture;
            if (assetChanged || surfaceChanged) {
                releasePipeline();
                releaseRetiredSurfaces();
                generation = wantedGeneration;
                directory = wantedDirectory;
                listener = wantedListener;
                windowTexture = wantedSurface;
                failedGeneration = -1;
            }
            windowWidth = wantedWidth;
            windowHeight = wantedHeight;

            if (!isReady() && failedGeneration != generation && directory != null
                    && windowTexture != null && windowWidth > 0 && windowHeight > 0) {
                try {
                    metadata = Metadata.read(directory);
                    File sourceFaces=new File(directory,"aligned_faces_rgb.bin");
                    mouthMotion=sourceFaces.isFile()?new MouthMotion(
                            Files.readAllBytes(sourceFaces.toPath()),metadata.frameCount):null;
                    createEgl();
                    createGlObjects();
                    createDecoder();
                    boolean current;
                    Listener readyListener = null;
                    long readyGeneration = generation;
                    synchronized (stateLock) {
                        current = !closed && generation == requestedGeneration
                                && windowTexture == requestedSurface;
                        if (current) {
                            renderFailed = false;
                            readyForRendering = true;
                            readyListener = listener;
                            stateLock.notifyAll();
                        }
                    }
                    if (!current) releasePipeline();
                    else if (readyForRendering && readyGeneration == lifecycleGeneration) {
                        notifyReady(readyListener);
                    }
                } catch (Throwable error) {
                    if (!isPipelineCurrent()) {
                        releasePipeline();
                        return;
                    }
                    Log.e(TAG, "Cannot open avatar assets", error);
                    synchronized (stateLock) {
                        renderFailed = true;
                        readyForRendering = false;
                        requests.clear();
                        stateLock.notifyAll();
                    }
                    failedGeneration = generation;
                    releasePipeline();
                    notifyError(listener, "视频资源无法打开");
                }
            }
        }

        private boolean isReady() {
            return codec != null && eglSurface != EGL14.EGL_NO_SURFACE;
        }

        private boolean isPipelineCurrent() {
            return !closed && generation == lifecycleGeneration && windowTexture != null;
        }

        private void releaseRetiredSurfaces() {
            for (;;) {
                SurfaceTexture surface;
                synchronized (stateLock) { surface = retiredSurfaces.pollFirst(); }
                if (surface == null) return;
                try { surface.release(); }
                catch (Throwable error) { Log.w(TAG, "Retired SurfaceTexture release", error); }
            }
        }

        private void finishSurfaceHandoffs() {
            synchronized (stateLock) {
                workerAcceptsSurfaceHandoffs = false;
                stateLock.notifyAll();
            }
            releaseRetiredSurfaces();
        }

        private void present(Request request) throws Exception {
            if (!isCurrent(request)) return;
            int videoIndex = metadata.videoIndex(request.phase);
            if (!decodeTo(videoIndex, request) || !isCurrent(request)) return;
            drawVideo();
            boolean drewFace = false;
            // A streaming gap may already have faded to the loop. Resume from the
            // last successful swap, just as the first mouth of a new utterance enters.
            float blend = request.blend;
            if (!request.naturalRelease && request.speaking != null && request.face != null) {
                float previous = displayedFace == null ? 0f : displayedBlend;
                blend = request.speaking ? Math.min(blend, previous + .2f)
                        : Math.min(blend, Math.max(0f, previous - .2f));
            }
            if (!request.naturalRelease) releaseStartedNs = 0;
            if ((request.naturalRelease || request.face != null) && blend > 0f && request.faceValid.getAsBoolean()) {
                int sourceIndex=metadata.metadataIndex(videoIndex);
                if (request.naturalRelease) {
                    // Start from what actually swapped, including short-speech opacity and its warp.
                    // A recreated Surface has no displayed face and must not replay an old release.
                    if (displayedFace != null) {
                        long now = SystemClock.elapsedRealtimeNanos();
                        float elapsedMs = releaseStartedNs == 0 ? 0f : (now - releaseStartedNs) / 1_000_000f;
                        float shape = Math.min(1f, elapsedMs / 200f);
                        float opacity = displayedBlend * Math.max(0f, Math.min(1f, (400f-elapsedMs)/200f));
                        float[] bounds = mouthMotion == null ? null : mouthMotion.releaseBounds(sourceIndex);
                        if (opacity > 0f) {
                            drawFace(sourceIndex, displayedFace, opacity, displayedMouth, shape, bounds);
                            drewFace = true;
                        } else {
                            displayedFace = null;
                            if (mouthMotion != null) mouthMotion.reset();
                            measuredFace = null;
                        }
                    }
                } else {
                    if(mouthMotion!=null){
                        if(!Arrays.equals(request.face,measuredFace)){
                            mouthTransform=mouthMotion.update(request.face,sourceIndex,blend);
                            measuredFace=request.face;
                        } else {
                            mouthTransform=mouthMotion.forSource(sourceIndex,blend);
                        }
                    }
                    drawFace(sourceIndex, request.face, blend, mouthTransform, 0f, null);
                    drewFace = true;
                }
                // Cancellation can arrive during upload/draw; replace the old face before swap.
                if (!request.faceValid.getAsBoolean()) { drawVideo(); drewFace = false; }
            } else if(mouthMotion!=null){
                mouthMotion.reset();measuredFace=null;
            }
            if (!isCurrent(request)) return;
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                throw new IOException("Avatar presentation swap failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            }
            if (!drewFace) displayedFace = null;
            else if (request.naturalRelease && releaseStartedNs == 0) {
                // A slow first swap must not consume the visible shape transition.
                releaseStartedNs = SystemClock.elapsedRealtimeNanos();
            } else if (!request.naturalRelease) {
                displayedFace = request.face;
                displayedBlend = blend;
                displayedMouth = mouthTransform.clone();
            }
            if (isCurrent(request) && request.presented != null) {
                try {
                    request.presented.run();
                } catch (Throwable callbackError) {
                    Log.w(TAG, "Presentation callback failed", callbackError);
                }
            }
        }

        private void createEgl() throws IOException {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IOException("No EGL display");
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
                throw new IOException("Cannot initialize EGL");
            }
            int[] attributes = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) throw new IOException("No EGL config");
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    contextAttributes, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw new IOException("Cannot create EGL context");
            Surface window = new Surface(windowTexture);
            try {
                int[] surfaceAttributes = {EGL14.EGL_NONE};
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], window,
                        surfaceAttributes, 0);
            } finally {
                window.release();
            }
            if (eglSurface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new IOException("Cannot attach EGL surface");
            }
        }

        private void createGlObjects() throws IOException {
            videoProgram = program(
                    "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){ gl_Position=aPosition;"
                    + "vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy; }",
                    "#extension GL_OES_EGL_image_external : require\n"
                    + "precision highp float;\n"
                    + "uniform samplerExternalOES uVideo;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){ gl_FragColor=texture2D(uVideo,vTexCoord); }");
            faceProgram = program(
                    "attribute vec2 aPosition;\n"
                    + "attribute vec2 aRoi;\n"
                    + "varying vec2 vRoi;\n"
                    + "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vRoi=aRoi; }",
                    "precision highp float;\n"
                    + "uniform sampler2D uFace; uniform sampler2D uMask;\n"
                    + "uniform vec2 uBoxSize; uniform vec2 uCropSize;\n"
                    + "uniform vec3 uRow0; uniform vec3 uRow1; uniform vec4 uMouth; uniform float uBlend;\n"
                    + "uniform float uRelease; uniform vec4 uGeneratedBounds; uniform vec4 uSourceBounds;\n"
                    + "varying vec2 vRoi;\n"
                    // Ordered mouth knots and fixed image edges keep this whole-mouth map monotone.
                    + "float releaseAxis(float p,vec2 target,vec2 generated){"
                    + "if(p<target.x)return p*generated.x/target.x;"
                    + "if(p>target.y)return generated.y+(p-target.y)*(255.0-generated.y)/(255.0-target.y);"
                    + "return generated.x+(p-target.x)*(generated.y-generated.x)/(target.y-target.x);}\n"
                    + "void main(){ vec2 p=vRoi*uBoxSize-vec2(0.5);"
                    + "vec2 c=vec2(dot(uRow0,vec3(p,1.0)),dot(uRow1,vec3(p,1.0)));"
                    + "vec2 uv=(c+vec2(0.5))/uCropSize; vec2 aligned=uv*256.0-vec2(0.5);"
                    + "vec2 q=(aligned-vec2(128.0,159.0))/vec2(46.0,27.0);"
                    + "float t=clamp((length(q)-0.85)/0.15,0.0,1.0);"
                    + "float w=1.0-t*t*(3.0-2.0*t); if(aligned.y>=186.0)w=0.0;"
                    // Contraction must not pull chin texels into the mouth.
                    + "vec2 warped=uMouth.xy*aligned+uMouth.zw; warped.y=min(warped.y,185.0);"
                    + "vec2 sampled=aligned+w*(warped-aligned);"
                    + "if(uRelease>0.0){vec2 released=vec2("
                    + "releaseAxis(aligned.x,uSourceBounds.xy,uGeneratedBounds.xy),"
                    + "releaseAxis(aligned.y,uSourceBounds.zw,uGeneratedBounds.zw));"
                    + "sampled=mix(sampled,released,uRelease);} aligned=sampled;"
                    + "uv=(aligned+vec2(0.5))/256.0;"
                    + "if(uv.x<0.0||uv.y<0.0||uv.x>1.0||uv.y>1.0) discard;"
                    + "float a=texture2D(uMask,vRoi).r*uBlend;"
                    + "gl_FragColor=vec4(texture2D(uFace,uv).rgb,a); }");

            int[] names = new int[2];
            GLES20.glGenTextures(2, names, 0);
            externalTexture = names[0];
            faceTexture = names[1];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTexture);
            textureParameters(GLES20.GL_TEXTURE_2D);
            checkGl("create textures");

            decoderTexture = new SurfaceTexture(externalTexture);
            frameCallbackThread = new HandlerThread("avatar-frame-callback");
            frameCallbackThread.start();
            FrameSignal signal = new FrameSignal();
            frameSignal = signal;
            decoderTexture.setOnFrameAvailableListener(ignored -> {
                synchronized (signal) {
                    if (!signal.retired) signal.available = true;
                    signal.notifyAll();
                }
            }, new Handler(frameCallbackThread.getLooper()));
            decoderSurface = new Surface(decoderTexture);
        }

        private void createDecoder() throws IOException {
            extractor = new MediaExtractor();
            extractor.setDataSource(metadata.video.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    format = candidate;
                    break;
                }
            }
            if (format == null) throw new IOException("No video track");
            int encodedWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            int encodedHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (encodedWidth != metadata.width || encodedHeight != metadata.height) {
                throw new IOException("Video dimensions do not match avatar metadata");
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, decoderSurface, null, 0);
            codec.start();
            inputEnded = false;
            decodedIndex = -1;
        }

        private boolean decodeTo(int target, Request request) throws Exception {
            if (!isCurrent(request)) return false;
            if (decodedIndex == target) return true;
            if (decodedIndex < 0 || decodedIndex > target) resetDecoder(target);
            int remaining = metadata.loopFrames + 16;
            while (decodedIndex < target && remaining-- > 0) {
                if (!decodeOne(target, request)) return false;
            }
            if (decodedIndex != target) throw new IOException("Video frame sequence mismatch");
            return true;
        }

        private void resetDecoder(int target) {
            // Before the first input, seeking needs no flush, which could discard codec config.
            if (decodedIndex >= 0) codec.flush();
            extractor.seekTo(target * 1_000_000L / metadata.sourceFps,
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            inputEnded = false;
            decodedIndex = -1;
            FrameSignal signal = frameSignal;
            if (signal != null) synchronized (signal) { signal.available = false; }
        }

        private boolean decodeOne(int target, Request request) throws Exception {
            for (;;) {
                if (!isCurrent(request)) return false;
                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("Decoder input unavailable");
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outputIndex >= 0) {
                    boolean video = bufferInfo.size > 0
                            && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    int index = video ? (int) Math.round(bufferInfo.presentationTimeUs
                            * (double) metadata.sourceFps / 1_000_000d) : -1;
                    boolean render = video && index == target;
                    FrameSignal signal = frameSignal;
                    if (signal == null) return false;
                    synchronized (signal) { signal.available = false; }
                    codec.releaseOutputBuffer(outputIndex, render);
                    if (render) {
                        if (!awaitDecoderFrame(signal, request)) return false;
                        decoderTexture.updateTexImage();
                        decoderTexture.getTransformMatrix(decoderTransform);
                    }
                    if (video) {
                        if (index < 0 || index >= metadata.loopFrames || index <= decodedIndex) {
                            throw new IOException("Invalid decoded video timestamp");
                        }
                        decodedIndex = index;
                        return true;
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        throw new IOException("Video ended before declared frame count");
                    }
                }
            }
        }

        private boolean awaitDecoderFrame(FrameSignal signal, Request request) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            synchronized (signal) {
                while (!signal.available && !signal.retired && isCurrent(request)) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) throw new IOException("Timed out waiting for decoded frame");
                    TimeUnit.NANOSECONDS.timedWait(signal, left);
                }
                if (!signal.available || signal.retired || !isCurrent(request)) return false;
                signal.available = false;
                return true;
            }
        }

        private boolean isCurrent(Request request) {
            return !closed && request.generation == lifecycleGeneration
                    && request.surface == windowTexture;
        }

        private void cancelFrameWait() {
            FrameSignal signal = frameSignal;
            if (signal != null) synchronized (signal) { signal.notifyAll(); }
        }

        private void drawVideo() throws IOException {
            setViewport();
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(videoProgram);
            videoVertices.position(0);
            int position = GLES20.glGetAttribLocation(videoProgram, "aPosition");
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, videoVertices);
            GLES20.glEnableVertexAttribArray(position);
            videoVertices.position(2);
            int texCoord = GLES20.glGetAttribLocation(videoProgram, "aTexCoord");
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, videoVertices);
            GLES20.glEnableVertexAttribArray(texCoord);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(videoProgram, "uTexMatrix"),
                    1, false, decoderTransform, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(videoProgram, "uVideo"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGl("draw video");
        }

        private void drawFace(int index, byte[] rgb, float blend, float[] mouth,
                float release, float[] bounds) throws IOException {
            Frame frame = metadata.frames.get(index);
            int maskTexture = maskTexture(index, frame);
            float left = frame.x1 * 2f / metadata.width - 1f;
            float right = frame.x2 * 2f / metadata.width - 1f;
            float top = 1f - frame.y1 * 2f / metadata.height;
            float bottom = 1f - frame.y2 * 2f / metadata.height;
            faceVertices.clear();
            faceVertices.put(new float[] {
                    left, bottom, 0f, 1f,
                    right, bottom, 1f, 1f,
                    left, top, 0f, 0f,
                    right, top, 1f, 0f
            }).flip();

            facePixels.clear();
            facePixels.put(rgb).flip();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTexture);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, FACE_SIZE, FACE_SIZE,
                    0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, facePixels);

            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(faceProgram);
            faceVertices.position(0);
            int position = GLES20.glGetAttribLocation(faceProgram, "aPosition");
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, faceVertices);
            GLES20.glEnableVertexAttribArray(position);
            faceVertices.position(2);
            int roi = GLES20.glGetAttribLocation(faceProgram, "aRoi");
            GLES20.glVertexAttribPointer(roi, 2, GLES20.GL_FLOAT, false, 16, faceVertices);
            GLES20.glEnableVertexAttribArray(roi);

            GLES20.glUniform2f(GLES20.glGetUniformLocation(faceProgram, "uBoxSize"),
                    frame.x2 - frame.x1, frame.y2 - frame.y1);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(faceProgram, "uCropSize"),
                    frame.cropWidth, frame.cropHeight);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(faceProgram, "uRow0"),
                    frame.affine[0], frame.affine[1], frame.affine[2]);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(faceProgram, "uRow1"),
                    frame.affine[3], frame.affine[4], frame.affine[5]);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(faceProgram, "uMouth"),
                    mouth[0],mouth[1],mouth[2],mouth[3]);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(faceProgram, "uBlend"), blend);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(faceProgram, "uRelease"), bounds == null ? 0f : release);
            if (bounds != null) {
                GLES20.glUniform4f(GLES20.glGetUniformLocation(faceProgram, "uGeneratedBounds"),
                        bounds[0],bounds[1],bounds[2],bounds[3]);
                GLES20.glUniform4f(GLES20.glGetUniformLocation(faceProgram, "uSourceBounds"),
                        bounds[4],bounds[5],bounds[6],bounds[7]);
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(faceProgram, "uFace"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(faceProgram, "uMask"), 1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisable(GLES20.GL_BLEND);
            checkGl("draw face");
        }

        private int maskTexture(int index, Frame frame) throws IOException {
            Integer cached = maskTextures.get(index);
            if (cached != null) return cached;
            Bitmap bitmap = BitmapFactory.decodeFile(frame.mask.getAbsolutePath());
            if (bitmap == null) throw new IOException("Cannot decode mask");
            if (bitmap.getWidth() != frame.x2 - frame.x1
                    || bitmap.getHeight() != frame.y2 - frame.y1) {
                bitmap.recycle();
                throw new IOException("Mask dimensions do not match box");
            }
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[0]);
            textureParameters(GLES20.GL_TEXTURE_2D);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            bitmap.recycle();
            checkGl("upload mask");
            maskTextures.put(index, names[0]);
            return names[0];
        }

        private void setViewport() {
            float sourceAspect = metadata.width / (float) metadata.height;
            float windowAspect = windowWidth / (float) windowHeight;
            int width = windowWidth;
            int height = windowHeight;
            int x = 0;
            int y = 0;
            if (windowAspect > sourceAspect) {
                width = Math.round(windowHeight * sourceAspect);
                x = (windowWidth - width) / 2;
            } else if (windowAspect < sourceAspect) {
                height = Math.round(windowWidth / sourceAspect);
                y = (windowHeight - height) / 2;
            }
            GLES20.glViewport(x, y, width, height);
        }

        private void releasePipeline() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                } catch (Throwable error) { Log.w(TAG, "EGL detach", error); }
            }
            if (codec != null) {
                MediaCodec oldCodec = codec;
                codec = null;
                try { oldCodec.stop(); } catch (Throwable error) { Log.w(TAG, "Codec stop", error); }
                try { oldCodec.release(); } catch (Throwable error) { Log.w(TAG, "Codec release", error); }
            }
            if (extractor != null) {
                MediaExtractor oldExtractor = extractor;
                extractor = null;
                try { oldExtractor.release(); }
                catch (Throwable error) { Log.w(TAG, "Extractor release", error); }
            }
            if (decoderSurface != null) {
                Surface oldSurface = decoderSurface;
                decoderSurface = null;
                try { oldSurface.release(); }
                catch (Throwable error) { Log.w(TAG, "Decoder Surface release", error); }
            }
            FrameSignal signal = frameSignal;
            frameSignal = null;
            if (signal != null) {
                synchronized (signal) {
                    signal.retired = true;
                    signal.notifyAll();
                }
            }
            if (decoderTexture != null) {
                SurfaceTexture oldTexture = decoderTexture;
                decoderTexture = null;
                try { oldTexture.setOnFrameAvailableListener(null); }
                catch (Throwable error) { Log.w(TAG, "Decoder listener removal", error); }
                try { oldTexture.release(); }
                catch (Throwable error) { Log.w(TAG, "Decoder SurfaceTexture release", error); }
            }
            if (frameCallbackThread != null) {
                HandlerThread oldThread = frameCallbackThread;
                frameCallbackThread = null;
                try { oldThread.quit(); }
                catch (Throwable error) { Log.w(TAG, "Frame callback thread stop", error); }
            }
            maskTextures.clear();
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    try { EGL14.eglDestroySurface(eglDisplay, eglSurface); }
                    catch (Throwable error) { Log.w(TAG, "EGL surface destroy", error); }
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    try { EGL14.eglDestroyContext(eglDisplay, eglContext); }
                    catch (Throwable error) { Log.w(TAG, "EGL context destroy", error); }
                }
                try { EGL14.eglTerminate(eglDisplay); }
                catch (Throwable error) { Log.w(TAG, "EGL terminate", error); }
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            videoProgram = 0;
            faceProgram = 0;
            externalTexture = 0;
            faceTexture = 0;
            if(mouthMotion!=null)mouthMotion.reset();
            mouthMotion=null;
            measuredFace=null;
            mouthTransform=new float[]{1,1,0,0};
            displayedFace=null;
            displayedBlend=0f;
            displayedMouth=new float[]{1,1,0,0};
            releaseStartedNs=0;
            inputEnded = false;
            decodedIndex = -1;
            metadata = null;
        }
    }

    private static final class Frame {
        final int x1, y1, x2, y2;
        final float[] affine;
        final int cropWidth, cropHeight;
        final File mask;

        Frame(int x1, int y1, int x2, int y2, float[] affine,
              int cropWidth, int cropHeight, File mask) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.affine = affine;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.mask = mask;
        }
    }

    private static final class Metadata {
        final File video;
        final int width, height, sourceFps, frameCount, loopFrames;
        final ArrayList<Frame> frames;

        Metadata(File video, int width, int height, int sourceFps, int frameCount,
                 int loopFrames, ArrayList<Frame> frames) {
            this.video = video;
            this.width = width;
            this.height = height;
            this.sourceFps = sourceFps;
            this.frameCount = frameCount;
            this.loopFrames = loopFrames;
            this.frames = frames;
        }

        static Metadata read(File directory) throws Exception {
            File root = directory.getCanonicalFile();
            if (!root.isDirectory()) throw new IOException("Asset directory missing");
            File manifest = child(root, "avatar.json");
            JSONObject json = new JSONObject(new String(Files.readAllBytes(manifest.toPath()),
                    StandardCharsets.UTF_8));
            if (json.getInt("version") != 1) throw new IOException("Unsupported avatar version");
            int width = json.getInt("width");
            int height = json.getInt("height");
            int sourceFps = json.getInt("source_fps");
            int frameCount = json.getInt("frame_count");
            int loopFrames = json.getInt("loop_frames");
            if (width <= 0 || height <= 0 || sourceFps != 24 || frameCount < 2
                    || loopFrames != AvatarTimeline.loopFrames(frameCount)) {
                throw new IOException("Invalid avatar dimensions or timeline");
            }
            File video = child(root, json.getString("video"));
            if (!video.isFile()) throw new IOException("Video asset missing");
            JSONArray entries = json.getJSONArray("frames");
            if (entries.length() != frameCount) throw new IOException("Frame metadata count mismatch");
            ArrayList<Frame> frames = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                JSONObject entry = entries.getJSONObject(i);
                JSONArray box = entry.getJSONArray("box");
                if (box.length() != 4) throw new IOException("Invalid box");
                int x1 = box.getInt(0), y1 = box.getInt(1);
                int x2 = box.getInt(2), y2 = box.getInt(3);
                if (x1 < 0 || y1 < 0 || x2 <= x1 || y2 <= y1 || x2 > width || y2 > height) {
                    throw new IOException("Box outside video");
                }
                JSONArray values = entry.getJSONArray("affine");
                if (values.length() != 6) throw new IOException("Invalid affine");
                float[] affine = new float[6];
                for (int j = 0; j < 6; j++) {
                    affine[j] = (float) values.getDouble(j);
                    if (!Float.isFinite(affine[j])) throw new IOException("Non-finite affine");
                }
                int cropWidth = entry.getInt("crop_width");
                int cropHeight = entry.getInt("crop_height");
                if (cropWidth <= 0 || cropHeight <= 0) throw new IOException("Invalid crop");
                File mask = child(root, entry.getString("mask"));
                if (!mask.isFile()) throw new IOException("Mask asset missing");
                frames.add(new Frame(x1, y1, x2, y2, affine, cropWidth, cropHeight, mask));
            }
            return new Metadata(video, width, height, sourceFps, frameCount, loopFrames, frames);
        }

        int videoIndex(long phase) {
            return AvatarTimeline.videoIndex(phase, frameCount);
        }

        int metadataIndex(int videoIndex) {
            return AvatarTimeline.sourceIndex(videoIndex, frameCount);
        }

        private static File child(File root, String relative) throws IOException {
            File child = new File(root, relative).getCanonicalFile();
            String prefix = root.getPath() + File.separator;
            if (!child.getPath().startsWith(prefix)) throw new IOException("Asset path escapes directory");
            return child;
        }
    }

    private static FloatBuffer floats(float... values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).flip();
        return result;
    }

    private static void textureParameters(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int program(String vertexSource, String fragmentSource) throws IOException {
        int vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        String log = GLES20.glGetProgramInfoLog(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(program);
            throw new IOException("GL program link failed: " + log);
        }
        return program;
    }

    private static int shader(int type, String source) throws IOException {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IOException("GL shader compile failed: " + log);
        }
        return shader;
    }

    private static void checkGl(String operation) throws IOException {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IOException(String.format(Locale.ROOT, "%s failed: 0x%04x", operation, error));
        }
    }

    private static void notifyReady(Listener listener) {
        if (listener == null) return;
        try { listener.onReady(); }
        catch (Throwable error) { Log.w(TAG, "Ready listener failed", error); }
    }

    private static void notifyError(Listener listener, String message) {
        if (listener == null) return;
        try { listener.onError(message); }
        catch (Throwable error) { Log.w(TAG, "Error listener failed", error); }
    }

    private static void notifyUnavailable(Listener listener) {
        if (listener == null) return;
        try { listener.onUnavailable(); }
        catch (Throwable error) { Log.w(TAG, "Unavailable listener failed", error); }
    }
}
