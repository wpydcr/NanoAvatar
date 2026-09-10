package com.avatar.nputest;

import android.content.Context;
import android.system.Os;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import java.io.File;
import java.io.IOException;
import java.util.Objects;

/** Owns the dynamically registered QNN EP for the lifetime of this app process. */
final class QnnRuntime {
    private static final String LIBRARY="libonnxruntime_providers_qnn.so";
    private static OrtEnvironment environment;
    private static String registeredLibrary;

    private QnnRuntime() { }

    static synchronized OrtEnvironment environment(Context context)throws Exception {
        Objects.requireNonNull(context,"context");
        String nativeDir=Objects.requireNonNull(context.getApplicationInfo().nativeLibraryDir,"nativeLibraryDir");
        String library=new File(nativeDir,LIBRARY).getCanonicalPath();
        if(environment!=null) {
            if(!library.equals(registeredLibrary))throw new IOException("QNN library path changed within app process");
            return environment;
        }
        Os.setenv("ADSP_LIBRARY_PATH",nativeDir,true);
        OrtEnvironment candidate=OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,"AvatarPhone");
        candidate.registerExecutionProviderLibrary("QNNExecutionProvider",library);
        registeredLibrary=library;
        environment=candidate;
        return candidate;
    }
}
