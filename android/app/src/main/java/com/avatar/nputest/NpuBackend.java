package com.avatar.nputest;

import android.content.Context;
import android.os.SystemClock;
import android.util.Half;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** One inference owner. The caller supplies media phase and owns cancellation. */
public final class NpuBackend implements AutoCloseable, PcmStream.FeatureExtractor {
    public interface Progress { void onProgress(String message); }
    private static final int[] CHANNELS={16,32,64,128,256,512,512};
    private static final int[] SIDES={256,128,64,32,16,8,4};
    private static final String[] SKIPS={"conv_in","down0","down1","down2","down3","down4","down5"};
    private final OrtEnvironment env;
    private final Map<String,OrtSession> sessions=new LinkedHashMap<>();
    private final List<String> hubertParts=new ArrayList<>();
    private final File cacheDirectory,previousCacheDirectory;
    private final byte[] faces, referenceFaces, repair;
    private final LipColorMatch lipColor;
    private final int frameCount;
    private FloatBuffer generatorOutput;
    private OnnxTensor generatorOutputTensor;
    private Map<String,OnnxTensor> generatorOutputs;
    private final float[] generatorValues=new float[196608];
    private ByteBuffer referenceBuffer;
    private int referenceIndex=-1;
    private ByteBuffer nextReferenceBuffer;
    private ExecutorService referenceReader;
    private Future<Integer> nextReference;
    private boolean halfReferences;
    private float[] h=new float[1152],c=new float[1152];
    public final Map<String,Double> stageMs=new LinkedHashMap<>();
    private final JSONObject config;

    public NpuBackend(Context context, File payload, File assets, Progress progress) throws Exception {
        config=readJson(new File(payload,"phone_config.json"));
        JSONObject avatar=readJson(new File(assets,"avatar.json"));
        frameCount=avatar.getInt("frame_count");
        if(avatar.getInt("version")!=1||avatar.getInt("source_fps")!=24
                ||avatar.getInt("loop_frames")!=AvatarTimeline.loopFrames(frameCount))throw new IOException("人物时间线不完整");
        env=QnnRuntime.environment(context);
        List<OrtEpDevice> devices=new ArrayList<>();
        for (OrtEpDevice device:env.getEpDevices()) if (device.getEpName().equals("QNNExecutionProvider")
                && device.getDevice().getType()==OrtHardwareDevice.OrtHardwareDeviceType.NPU) devices.add(device);
        if (devices.isEmpty()) throw new IOException("手机 NPU 不可用");
        try {
            for (String part:new String[]{"hubert","audio1","audio10","face","generator"}) {
                progress.onProgress("正在准备对话模型");
                JSONObject entry=config.getJSONObject(part);
                JSONArray stages=part.equals("hubert")?entry.optJSONArray("stages"):null;
                if(stages!=null){
                    if(stages.length()==0)throw new IOException("HuBERT has no stages");
                    for(int i=0;i<stages.length();i++){
                        String key="hubert_stage"+i;
                        sessions.put(key,createSession(payload,stages.getJSONObject(i),devices));
                        hubertParts.add(key);
                    }
                }else{
                    sessions.put(part,createSession(payload,entry,devices));
                    if(part.equals("hubert"))hubertParts.add(part);
                }
            }
            Map<String,NodeInfo> outputInfo=sessions.get("generator").getOutputInfo();
            if(outputInfo.size()!=1)throw new IOException("Expected one generator output");
            String outputName=outputInfo.keySet().iterator().next();
            TensorInfo outputType=(TensorInfo)outputInfo.get(outputName).getInfo();
            if(outputType.type!=OnnxJavaType.FLOAT||!Arrays.equals(outputType.getShape(),new long[]{1,3,256,256}))
                throw new IOException("Unexpected generator output type or shape");
            generatorOutput=ByteBuffer.allocateDirect(196608*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            generatorOutputTensor=OnnxTensor.createTensor(env,generatorOutput,new long[]{1,3,256,256});
            generatorOutputs=Collections.singletonMap(outputName,generatorOutputTensor);
            Map<String,NodeInfo> generatorInputs=sessions.get("generator").getInputInfo();
            if(((TensorInfo)generatorInputs.get("conv_in").getInfo()).type==OnnxJavaType.FLOAT16) {
                halfReferences=true;
                for(int level=0;level<7;level++) {
                    if(((TensorInfo)generatorInputs.get(SKIPS[level]).getInfo()).type!=OnnxJavaType.FLOAT16)
                        throw new IOException("人物特征输入类型不一致");
                }
            }
            faces=Files.readAllBytes(new File(assets,"aligned_faces_rgb.bin").toPath());
            // A baked display mouth must not feed its own generated pixels back into the model.
            referenceFaces=avatar.has("reference_faces")
                    ?Files.readAllBytes(inside(assets,avatar.getString("reference_faces")).toPath()):faces;
            repair=Files.readAllBytes(new File(assets,"repair_rgb.raw").toPath());
            if (faces.length!=frameCount*256L*256*3 || referenceFaces.length!=faces.length
                    || repair.length!=256*256*3) throw new IOException("人物数据不完整");
            lipColor=new LipColorMatch(faces,frameCount);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(referenceFaces); digest.update(repair);
            File faceGraph=inside(payload,config.getJSONObject("face").getString("path"));
            digest.update(Files.readAllBytes(faceGraph.toPath()));
            digest.update(config.getJSONObject("face").toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder();
            for (byte b:digest.digest()) key.append(String.format(Locale.ROOT,"%02x",b&255));
            previousCacheDirectory=new File(context.getExternalFilesDir(null),"prepared_faces/"+key);
            cacheDirectory=new File(context.getFilesDir(),"prepared_faces/"+key);
            if (!cacheDirectory.isDirectory()&&!cacheDirectory.mkdirs()) throw new IOException("无法保存人物数据");
        } catch (Exception|Error error) { close(); throw error; }
    }

    private OrtSession createSession(File payload,JSONObject entry,List<OrtEpDevice> devices)throws Exception{
        File graph=inside(payload,entry.getString("path"));
        String target=entry.getString("provider");
        if(!target.equals("QNN")&&!target.equals("CPU"))throw new IOException("Unknown model provider: "+target);
        boolean npu=target.equals("QNN");
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(npu?1:4);options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning","0");
            if(npu){
                options.addConfigEntry("session.disable_cpu_ep_fallback","1");
                Map<String,String> provider=new HashMap<>();
                provider.put("backend_type","htp");
                provider.put("htp_performance_mode","sustained_high_performance");
                provider.put("offload_graph_io_quantization","0");
                options.addExecutionProvider(devices,provider);
            }
            return env.createSession(graph.toString(),options);
        }
    }

    /** A fixed avatar's reference features are invariant. Pay once, then read from disk. */
    public void prepareReferences(Progress progress) throws Exception {
        for (int index=0;index<frameCount;index++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            File file=cacheFile(index);
            if (file.length()!=4145152L) {
                progress.onProgress("正在准备人物 "+(index+1)+" / "+frameCount);
                File temporary=new File(cacheDirectory,index+".tmp");
                File prepared=new File(previousCacheDirectory,index+".f16");
                if(prepared.length()==4145152L) {
                    Files.copy(prepared.toPath(),temporary.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    short[][] encoded=encodeFace(index);
                    try (OutputStream out=new BufferedOutputStream(new FileOutputStream(temporary),65536)) {
                        for (short[] level:encoded) {
                            ByteBuffer bytes=ByteBuffer.allocate(level.length*2).order(ByteOrder.LITTLE_ENDIAN);
                            bytes.asShortBuffer().put(level);out.write(bytes.array());
                        }
                    }
                }
                Files.move(temporary.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        }
        if(referenceBuffer==null)referenceBuffer=ByteBuffer.allocateDirect(4145152).order(ByteOrder.LITTLE_ENDIAN);
        if(nextReferenceBuffer==null)nextReferenceBuffer=ByteBuffer.allocateDirect(4145152).order(ByteOrder.LITTLE_ENDIAN);
        referenceIndex=-1;
    }

    @Override public float[][] extract(float[] actual) throws Exception {
        long withIoStart=SystemClock.elapsedRealtimeNanos();
        if (actual.length<400||actual.length>28800) throw new IllegalArgumentException("PCM context length");
        double sum=0; for (float f:actual) sum+=f;
        double mean=sum/actual.length,variance=0;
        for (float f:actual) variance+=(f-mean)*(f-mean);
        double divisor=Math.sqrt(variance/actual.length+1e-7);
        float[] pcm=new float[28800];
        for (int i=0;i<actual.length;i++) pcm[i]=(float)((actual[i]-mean)/divisor);
        int steps=(actual.length-400)/320+1;
        float[] mask=new float[89],bias=new float[89*89];
        Arrays.fill(mask,0,steps,1f);
        for (int row=0;row<89;row++) Arrays.fill(bias,row*89+steps,(row+1)*89,-10000f);
        Map<String,OnnxTensor> input=new LinkedHashMap<>();
        List<OrtSession.Result> results=new ArrayList<>();
        try {
            input.put("pcm",tensor(pcm,1,28800)); input.put("feature_mask",tensor(mask,1,89));
            input.put("attention_bias",tensor(bias,1,1,89,89));
            Map<String,OnnxTensor> available=new HashMap<>(input);
            for(String part:hubertParts){
                OrtSession session=sessions.get(part);
                Map<String,OnnxTensor> stageInput=new LinkedHashMap<>();
                for(String name:session.getInputNames()){
                    OnnxTensor value=available.get(name);
                    if(value==null)throw new IOException("Missing HuBERT stage input: "+name);
                    stageInput.put(name,value);
                }
                double prior=stageMs.getOrDefault(part,0d);
                OrtSession.Result result=run(part,stageInput);
                results.add(result);
                if(!part.equals("hubert"))stageMs.put("hubert",stageMs.getOrDefault("hubert",0d)+stageMs.get(part)-prior);
                for(String name:session.getOutputNames())available.put(name,(OnnxTensor)result.get(name).orElseThrow());
            }
            FloatBuffer values=((OnnxTensor)results.get(results.size()-1).get(0)).getFloatBuffer();
            float[][] features=new float[steps][1024];
            for(float[] row:features){values.get(row);finite(row);}
            return features;
        } finally {
            for(int i=results.size()-1;i>=0;i--)results.get(i).close();
            input.values().forEach(OnnxTensor::close);
            stageMs.put("hubert_with_io",stageMs.getOrDefault("hubert_with_io",0d)+(SystemClock.elapsedRealtimeNanos()-withIoStart)/1e6);
        }
    }

    public void beginSpeech() { Arrays.fill(h,0); Arrays.fill(c,0); stageMs.clear(); }

    public float[] audio(PcmStream.Block block) throws Exception {
        if (block.firstFrame%10==0) { Arrays.fill(h,0); Arrays.fill(c,0); }
        float[] output=new float[block.count()*8192];
        for (int offset=0;offset<block.count();) {
            int count=block.count()==10?10:1;
            float[] windows=count==10?block.windows:Arrays.copyOfRange(block.windows,offset*10240,(offset+1)*10240);
            Map<String,OnnxTensor> input=new LinkedHashMap<>();
            try {
                input.put("windows",tensor(windows,count,10,1024));input.put("h",tensor(h,2,1,576));input.put("c",tensor(c,2,1,576));
                try (OrtSession.Result result=run(count==10?"audio10":"audio1",input)) {
                    ((OnnxTensor)result.get("features").orElseThrow()).getFloatBuffer().get(output,offset*8192,count*8192);
                    ((OnnxTensor)result.get("h_out").orElseThrow()).getFloatBuffer().get(h);
                    ((OnnxTensor)result.get("c_out").orElseThrow()).getFloatBuffer().get(c);
                }
            } finally { input.values().forEach(OnnxTensor::close); }
            offset+=count;
        }
        finite(output);finite(h);finite(c);return output;
    }

    private static byte[] toRgb(float[] values) {
        if(values.length!=196608)throw new IllegalArgumentException("Expected 256x256 RGB output");
        byte[] rgb=new byte[196608];
        for(int p=0;p<65536;p++) {
            int i=p*3;
            rgb[i]=(byte)Math.rint(Math.max(0,Math.min(1,values[p]))*255f);
            rgb[i+1]=(byte)Math.rint(Math.max(0,Math.min(1,values[65536+p]))*255f);
            rgb[i+2]=(byte)Math.rint(Math.max(0,Math.min(1,values[131072+p]))*255f);
        }
        return rgb;
    }

    @android.annotation.SuppressLint("HalfFloat")
    public byte[] generate(float[] audio,int offset,long phase) throws Exception {
        long withIoStart=SystemClock.elapsedRealtimeNanos();
        int index=AvatarTimeline.sourceIndex(AvatarTimeline.videoIndex(phase,frameCount),frameCount);
        long referenceStarted=SystemClock.elapsedRealtimeNanos();
        ShortBuffer reference=reference(index).asShortBuffer();
        stageMs.put("reference_wait",stageMs.getOrDefault("reference_wait",0d)+(SystemClock.elapsedRealtimeNanos()-referenceStarted)/1e6);
        prefetchReference(AvatarTimeline.sourceIndex(AvatarTimeline.videoIndex(phase+1,frameCount),frameCount));
        Map<String,OnnxTensor> input=new LinkedHashMap<>();
        try {
            input.put("audio",OnnxTensor.createTensor(env,FloatBuffer.wrap(audio,offset*8192,8192),new long[]{1,4,4,512}));
            int referenceOffset=0;
            for (int level=0;level<7;level++) {
                int count=CHANNELS[level]*SIDES[level]*SIDES[level];
                ShortBuffer encoded=reference.duplicate();encoded.position(referenceOffset);encoded.limit(referenceOffset+count);
                encoded=encoded.slice();referenceOffset+=count;
                long[] shape={1,CHANNELS[level],SIDES[level],SIDES[level]};
                if(halfReferences) {
                    input.put(SKIPS[level],OnnxTensor.createTensor(env,encoded,shape,OnnxJavaType.FLOAT16));
                } else {
                    FloatBuffer values=ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    while(encoded.hasRemaining())values.put(Half.toFloat(encoded.get()));
                    values.flip();
                    input.put(SKIPS[level],OnnxTensor.createTensor(env,values,shape));
                }
            }
            try (OrtSession.Result result=run("generator",input)) {
                if(result.get(0)!=generatorOutputTensor||result.isResultOwner(0))throw new IOException("Pinned output ownership mismatch");
                generatorOutput.clear();generatorOutput.get(generatorValues);generatorOutput.clear();
                float[] values=generatorValues;finite(values);
                byte[] rgb=toRgb(values);
                long colorStarted=SystemClock.elapsedRealtimeNanos();
                lipColor.apply(rgb,index);
                stageMs.put("lip_color",stageMs.getOrDefault("lip_color",0d)+(SystemClock.elapsedRealtimeNanos()-colorStarted)/1e6);
                return rgb;
            }
        } finally {
            input.values().forEach(OnnxTensor::close);
            stageMs.put("generator_with_io",stageMs.getOrDefault("generator_with_io",0d)+(SystemClock.elapsedRealtimeNanos()-withIoStart)/1e6);
        }
    }

    private ByteBuffer reference(int index) throws Exception {
        if(nextReference!=null){
            Future<Integer> pending=nextReference;nextReference=null;
            int loaded;
            try{loaded=pending.get();} // The spare buffer is never reused until its writer has finished.
            catch(ExecutionException error){
                if(!(error.getCause() instanceof IOException))throw error;
                loaded=-1; // A failed speculative read must not replace the requested source pose.
            }
            if(loaded==index){
                ByteBuffer previous=referenceBuffer;referenceBuffer=nextReferenceBuffer;nextReferenceBuffer=previous;
                referenceIndex=index;
            }
        }
        if(referenceBuffer!=null&&referenceIndex==index)return referenceBuffer;
        // The single owner only returns here after generate's synchronous run and tensor closes.
        if(referenceBuffer==null)referenceBuffer=ByteBuffer.allocateDirect(4145152).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer value=referenceBuffer;
        referenceIndex=-1;
        value.clear();
        if (!readReferenceFile(cacheFile(index),value)) {
            ShortBuffer data=value.asShortBuffer();
            for(short[] level:encodeFace(index))data.put(level);
        }
        referenceIndex=index;
        return value;
    }

    private void prefetchReference(int index){
        if(index==referenceIndex)return;
        if(nextReference!=null)throw new IllegalStateException("Unclaimed reference read");
        if(nextReferenceBuffer==null)nextReferenceBuffer=ByteBuffer.allocateDirect(4145152).order(ByteOrder.LITTLE_ENDIAN);
        if(referenceReader==null)referenceReader=Executors.newSingleThreadExecutor(job->{Thread thread=new Thread(job,"avatar-reference-read");thread.setDaemon(true);return thread;});
        ByteBuffer destination=nextReferenceBuffer;File file=cacheFile(index);
        // Only file I/O is concurrent. Model execution and input-tensor ownership stay on one thread.
        nextReference=referenceReader.submit(()->readReferenceFile(file,destination)?index:-1);
    }

    private static boolean readReferenceFile(File file,ByteBuffer destination)throws IOException{
        destination.clear();if(file.length()!=4145152L)return false;
        try(FileInputStream input=new FileInputStream(file)){
            while(destination.hasRemaining())if(input.getChannel().read(destination)<0)throw new EOFException("人物特征文件不完整");
        }
        destination.flip();return true;
    }

    private short[][] encodeFace(int index) throws Exception {
        float[] image=new float[7*65536];
        for(int p=0;p<65536;p++) {
            image[p]=(repair[p*3]&255)/255f;
            for(int k=0;k<3;k++) {
                float f=(referenceFaces[index*196608+p*3+k]&255)/255f;
                image[(k+1)*65536+p]=f*(repair[p*3+k]&255)/255f;
                image[(k+4)*65536+p]=f;
            }
        }
        try (OnnxTensor input=tensor(image,1,7,256,256);OrtSession.Result result=run("face",Collections.singletonMap("image",input))) {
            short[][] value=new short[7][];
            for(int i=0;i<7;i++) {
                FloatBuffer data=((OnnxTensor)result.get("skip"+i).orElseThrow()).getFloatBuffer();
                value[i]=new short[data.remaining()];
                for(int p=0;p<value[i].length;p++) {
                    float f=data.get(); if(!Float.isFinite(f))throw new IOException("人物模型输出异常");
                    value[i][p]=Half.toHalf(f);
                }
            }
            return value;
        }
    }

    private File cacheFile(int index) { return new File(cacheDirectory,index+".f16"); }
    private OnnxTensor tensor(float[] data,long... shape) throws OrtException { return OnnxTensor.createTensor(env,FloatBuffer.wrap(data),shape); }
    private OrtSession.Result run(String part,Map<String,OnnxTensor> inputs) throws OrtException {
        long start=SystemClock.elapsedRealtimeNanos();
        try{return part.equals("generator")?sessions.get(part).run(inputs,generatorOutputs):sessions.get(part).run(inputs);}
        finally{stageMs.put(part,stageMs.getOrDefault(part,0d)+(SystemClock.elapsedRealtimeNanos()-start)/1e6);}
    }
    private static void finite(float[] values)throws IOException{for(float value:values)if(!Float.isFinite(value))throw new IOException("模型输出异常");}
    private static JSONObject readJson(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
    private static File inside(File root,String path)throws IOException{
        File file=new File(root,path);if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Invalid model path");return file;
    }
    @Override public void close() {
        if(nextReference!=null){nextReference.cancel(true);nextReference=null;}
        if(referenceReader!=null){referenceReader.shutdownNow();referenceReader=null;}
        nextReferenceBuffer=null;
        if(generatorOutputTensor!=null){try{generatorOutputTensor.close();}catch(Exception ignored){}generatorOutputTensor=null;}
        generatorOutputs=null;generatorOutput=null;
        for(OrtSession session:sessions.values())try{session.close();}catch(Exception ignored){}
        sessions.clear();referenceBuffer=null;referenceIndex=-1;
    }
}
