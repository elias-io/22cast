package dev.elias.cast22;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class VideoDecoder implements ReceiverService.VideoSink, AutoCloseable {
 private static final class Frame {
  final byte[] data,sps,pps;
  final long pts;
  final int epoch,width,height;
  Frame(byte[] data,long pts,int epoch,byte[] sps,byte[] pps,int width,int height){
   this.data=data;this.pts=pts;this.epoch=epoch;this.sps=sps;this.pps=pps;this.width=width;this.height=height;
  }
 }
 interface FormatListener {void changed(int width,int height);}
 private final ArrayBlockingQueue<Frame> queue=new ArrayBlockingQueue<>(12);
 private final Surface surface;
 private final FormatListener listener;
 private final Thread worker;
 private volatile boolean running=true;
 private volatile int epoch=0;
 private int width=1920,height=1080;
 private byte[] sps,pps;
 private boolean awaitingKeyframe=true;

 VideoDecoder(Surface surface,FormatListener listener){
  this.surface=surface;this.listener=listener;
  worker=new Thread(this::decode,"22Cast video");worker.start();
 }

 @Override public synchronized void frame(byte[] data,long pts){
  if(!running||data==null||data.length==0)return;
  byte[] nextSps=nal(data,7),nextPps=nal(data,8);
  boolean changed=(nextSps!=null&&!Arrays.equals(sps,nextSps))||(nextPps!=null&&!Arrays.equals(pps,nextPps));
  if(nextSps!=null)sps=nextSps;
  if(nextPps!=null)pps=nextPps;
  if(changed||queue.remainingCapacity()==0){
   // Discarding reference frames invalidates the rest of this GOP. Retain the
   // parameter sets, then configure a clean decoder at the next IDR frame.
   queue.clear();epoch++;awaitingKeyframe=true;
  }
  if(sps==null||pps==null)return;
  if(awaitingKeyframe){if(nal(data,5)==null)return;awaitingKeyframe=false;}
  queue.offer(new Frame(data,pts,epoch,sps,pps,width,height));
 }

 @Override public synchronized void format(int width,int height){
  if(width>0&&height>0&&(this.width!=width||this.height!=height)){
   this.width=width;this.height=height;queue.clear();epoch++;awaitingKeyframe=true;
   listener.changed(width,height);
  }
 }

 @Override public synchronized void reset(){
  queue.clear();sps=null;pps=null;epoch++;awaitingKeyframe=true;
 }

 private static byte[] nal(byte[] bytes,int kind){
  int start=-1;
  for(int i=0;i+3<bytes.length;i++){
   int prefix=bytes[i]==0&&bytes[i+1]==0&&bytes[i+2]==1?3:
       i+4<bytes.length&&bytes[i]==0&&bytes[i+1]==0&&bytes[i+2]==0&&bytes[i+3]==1?4:0;
   if(prefix==0||i+prefix>=bytes.length)continue;
   if(start>=0)return Arrays.copyOfRange(bytes,start,i);
   if((bytes[i+prefix]&31)==kind)start=i;
   i+=prefix-1;
  }
  return start<0?null:Arrays.copyOfRange(bytes,start,bytes.length);
 }

 private static void release(MediaCodec codec){
  if(codec==null)return;
  try{codec.stop();}catch(Exception ignored){}
  try{codec.release();}catch(Exception ignored){}
 }

 private void drain(MediaCodec codec,MediaCodec.BufferInfo output){
  while(true){
   int index=codec.dequeueOutputBuffer(output,0);
   if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
    MediaFormat format=codec.getOutputFormat();
    int w=format.getInteger(MediaFormat.KEY_WIDTH),h=format.getInteger(MediaFormat.KEY_HEIGHT);
    if(format.containsKey("crop-right")&&format.containsKey("crop-left"))w=format.getInteger("crop-right")-format.getInteger("crop-left")+1;
    if(format.containsKey("crop-bottom")&&format.containsKey("crop-top"))h=format.getInteger("crop-bottom")-format.getInteger("crop-top")+1;
    listener.changed(w,h);continue;
   }
   if(index<0)return;
   long delay=output.presentationTimeUs*1000-System.currentTimeMillis()*1000000L+100000000L;
   if(delay>0&&delay<300000000L)codec.releaseOutputBuffer(index,System.nanoTime()+delay);
   else codec.releaseOutputBuffer(index,true);
  }
 }

 private void decode(){
  MediaCodec codec=null;
  MediaCodec.BufferInfo output=new MediaCodec.BufferInfo();
  Frame pending=null;
  int codecEpoch=-1,failedEpoch=-1;
  try{
   while(running){
    try{
     if(codec!=null&&codecEpoch!=epoch){release(codec);codec=null;}
     if(pending==null)pending=queue.poll(8,TimeUnit.MILLISECONDS);
     if(pending!=null&&(pending.epoch!=epoch||pending.epoch==failedEpoch))pending=null;
     if(pending!=null){
      if(codec==null){
       codecEpoch=pending.epoch;
       MediaFormat format=MediaFormat.createVideoFormat("video/avc",pending.width,pending.height);
       format.setByteBuffer("csd-0",ByteBuffer.wrap(pending.sps));format.setByteBuffer("csd-1",ByteBuffer.wrap(pending.pps));
       format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024);
       if(android.os.Build.VERSION.SDK_INT>=30)format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
       codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,surface,null,0);codec.start();
      }
      int index=codec.dequeueInputBuffer(10000);
      if(index>=0){
       ByteBuffer input=codec.getInputBuffer(index);input.clear();
       if(input.remaining()<pending.data.length)throw new IllegalStateException("Video frame exceeds decoder input capacity");
       input.put(pending.data);codec.queueInputBuffer(index,0,pending.data.length,pending.pts/1000,0);pending=null;
      }
      // Keep a pending reference frame until the codec accepts it.
     }
     if(codec!=null)drain(codec,output);
    }catch(InterruptedException e){if(!running)break;}
    catch(Exception e){
     failedEpoch=epoch;pending=null;release(codec);codec=null;
     android.util.Log.e("22Cast","Video decoder needs a new stream",e);
     ReceiverService service=ReceiverService.instance;
     if(service!=null)service.event("error","Video decoder failed. Restart the receiver.");
     // Keep the worker alive: a reset or new stream generation can recover.
    }
   }
  }finally{release(codec);}
 }

 @Override public void close(){
  running=false;queue.clear();worker.interrupt();
  try{worker.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
 }
}
