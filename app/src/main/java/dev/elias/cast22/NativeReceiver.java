package dev.elias.cast22;

final class NativeReceiver {
 static { System.loadLibrary("cast22"); }
 interface Sink {
  void event(String type,String value);
  void video(byte[] data,long timestampNanos);
  void pcm(short[] data,int rate,int channels,long timestampNanos);
  void format(int width,int height);
 }
 private final Sink sink;
 private long handle;
 NativeReceiver(Sink sink){this.sink=sink;}
 synchronized void start(String name,String id,String key,int height,int fps,boolean pin,boolean audio){
  stop();handle=nativeStart(name,id,key,height,fps,pin,audio);
 }
 synchronized void stop(){if(handle!=0){long h=handle;handle=0;nativeStop(h);}}
 private native long nativeStart(String name,String id,String key,int height,int fps,boolean pin,boolean audio);
 private native void nativeStop(long handle);
 // Called from native receiver threads; the sink owns thread switching.
 private void onEvent(String type,String value){sink.event(type,value);}
 private void onVideo(byte[] data,long pts){sink.video(data,pts);}
 private void onPcm(short[] data,int rate,int channels,long pts){sink.pcm(data,rate,channels,pts);}
 private void onFormat(int w,int h){sink.format(w,h);}
}
