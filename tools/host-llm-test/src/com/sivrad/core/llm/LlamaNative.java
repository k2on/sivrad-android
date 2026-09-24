package com.sivrad.core.llm;
public class LlamaNative {
    public native void backendInit();
    public native long load(byte[] path, int nCtx, int nThreads, int nThreadsBatch);
    public native void free(long h);
    public native void abort(long h);
    public native int contextSize(long h);
    public native byte[] applyTemplate(long h, byte[][] roles, byte[][] contents, boolean addAssistant);
    public native int generate(long h, byte[] prompt, byte[] grammar, int maxTokens, float[] sampling, TokenSink sink);
}
