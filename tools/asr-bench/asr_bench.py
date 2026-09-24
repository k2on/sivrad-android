"""
Ranks the speech models Sivrad offers on assistant-style commands.

Synthesises each sentence below with two Piper voices, adds light noise, and
reports word error rate and real-time factor for every model. Synthetic
speech is cleaner than a real voice in a real room, so read the WER as a
ranking. Run through run.sh, which downloads everything.
"""
import os
import sherpa_onnx as so, numpy as np, time, re, sys
S = [
 "set a timer for twenty five minutes",
 "wake me up at six thirty tomorrow",
 "set an alarm for seven fifteen on weekdays",
 "text jonathan that i'm running about ten minutes late",
 "send a message to aunt margaret saying happy birthday",
 "open signal",
 "open the fennec browser",
 "what's the capital of australia",
 "turn off the living room lights",
 "remind me to call the dentist tomorrow morning",
 "how many ounces are in a cup",
 "text siobhan that dinner is at eight",
]
def tts(v):
    d=f"{os.environ['WORK']}/tts-{v}"
    return so.OfflineTts(so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(vits=so.OfflineTtsVitsModelConfig(
        model=f"{d}/en_US-{v}.onnx", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"), num_threads=4)))
rng=np.random.default_rng(0)
clips=[]
for v in ["ryan-medium","amy-medium"]:
    t=tts(v)
    for s in S:
        a=t.generate(s, sid=0, speed=1.0)
        x=np.array(a.samples,dtype=np.float32)
        # resample to 16k (linear)
        n=int(len(x)*16000/a.sample_rate); x=np.interp(np.linspace(0,len(x)-1,n),np.arange(len(x)),x).astype(np.float32)
        x=np.concatenate([np.zeros(4000,np.float32),x,np.zeros(4000,np.float32)])
        x=x+rng.normal(0,0.004,len(x)).astype(np.float32)  # light room noise
        clips.append((s,x))
NUM={"10":"ten","8":"eight","25":"twenty five","6:30":"six thirty","7:15":"seven fifteen"}
def norm(t):
    for k,v in NUM.items(): t=t.replace(k,v)
    return re.sub(r"[^a-z0-9' ]"," ",t.lower()).split()
def wer(r,h):
    d=list(range(len(h)+1))
    for i in range(1,len(r)+1):
        p=d[:]; d[0]=i
        for j in range(1,len(h)+1): d[j]=min(p[j]+1,d[j-1]+1,p[j-1]+(r[i-1]!=h[j-1]))
    return d[len(h)]
M=f"{os.environ['WORK']}/models"
def online(name,enc,dec,join):
    return so.OnlineRecognizer.from_transducer(tokens=f"{M}/{name}/tokens.txt",encoder=f"{M}/{name}/{enc}",decoder=f"{M}/{name}/{dec}",joiner=f"{M}/{name}/{join}",num_threads=2,decoding_method="greedy_search")
def run_online(r,x):
    s=r.create_stream()
    for i in range(0,len(x),512):
        s.accept_waveform(16000,x[i:i+512])
        while r.is_ready(s): r.decode_stream(s)
    s.accept_waveform(16000,np.zeros(PAD,np.float32)); s.input_finished()
    while r.is_ready(s): r.decode_stream(s)
    return r.get_result(s)
def run_offline(r,x):
    s=r.create_stream(); s.accept_waveform(16000,x); r.decode_stream(s); return s.result.text
# Silence fed after the utterance to flush a streaming model (the app uses 1 s).
PAD=int(os.environ.get("PAD","16000"))
ONLY=os.environ.get("ONLY","")
models={
 "zipformer-2023-06-26 (original)": ("on", lambda: online("zipformer","encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx","decoder-epoch-99-avg-1-chunk-16-left-128.onnx","joiner-epoch-99-avg-1-chunk-16-left-128.onnx")),
 "kroko-2025-08-06": ("on", lambda: online("kroko","encoder.onnx","decoder.onnx","joiner.onnx")),
 "nemo-fastconformer-80ms-int8": ("on", lambda: online("nemo80","encoder.int8.onnx","decoder.int8.onnx","joiner.int8.onnx")),
 "parakeet-tdt-0.6b-v2-int8": ("off", lambda: so.OfflineRecognizer.from_transducer(tokens=f"{M}/parakeet/tokens.txt",encoder=f"{M}/parakeet/encoder.int8.onnx",decoder=f"{M}/parakeet/decoder.int8.onnx",joiner=f"{M}/parakeet/joiner.int8.onnx",model_type="nemo_transducer",num_threads=4)),
 "moonshine-base-int8": ("off", lambda: so.OfflineRecognizer.from_moonshine(preprocessor=f"{M}/mbase/preprocess.onnx",encoder=f"{M}/mbase/encode.int8.onnx",uncached_decoder=f"{M}/mbase/uncached_decode.int8.onnx",cached_decoder=f"{M}/mbase/cached_decode.int8.onnx",tokens=f"{M}/mbase/tokens.txt",num_threads=4)),
 "moonshine-tiny-int8": ("off", lambda: so.OfflineRecognizer.from_moonshine(preprocessor=f"{M}/mtiny/preprocess.onnx",encoder=f"{M}/mtiny/encode.int8.onnx",uncached_decoder=f"{M}/mtiny/uncached_decode.int8.onnx",cached_decoder=f"{M}/mtiny/cached_decode.int8.onnx",tokens=f"{M}/mtiny/tokens.txt",num_threads=4)),
}
audio_s=sum(len(x) for _,x in clips)/16000
for name,(kind,mk) in models.items():
    if ONLY and ONLY not in kind: continue
    r=mk(); errs=0; words=0; t0=time.time(); samples=[]
    for ref,x in clips:
        h=run_online(r,x) if kind=="on" else run_offline(r,x)
        errs+=wer(norm(ref),norm(h)); words+=len(norm(ref)); samples.append(h)
    dt=time.time()-t0
    print(f"{name:34s} WER {100*errs/words:5.1f}%   RTF {dt/audio_s:.3f}")
    for i in (3,6,11): print("     ", repr(samples[i]))
    sys.stdout.flush()
