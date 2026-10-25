package dev.elias.cast22;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.*;
import java.io.File;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class ReceiverService extends Service implements NativeReceiver.Sink {
 static volatile ReceiverService instance;
 interface VideoSink {void frame(byte[] data,long pts);void format(int w,int h);void reset();}
 static volatile VideoSink videoSink;
 private final ExecutorService control=Executors.newSingleThreadExecutor();
 private final ThreadPoolExecutor sound=(ThreadPoolExecutor)Executors.newFixedThreadPool(1);
 private final Object stateLock=new Object();
 private JSONObject current=new JSONObject();
 private NativeReceiver receiver;
 private WifiManager.MulticastLock multicast;
 private AudioTrack audioTrack;
 private int audioRate=0,audioChannels=0;
 private volatile boolean destroyed=false;
 private volatile int soundEpoch=0;
 private volatile boolean paused=false;
 private ConnectivityManager.NetworkCallback networkCallback;
 private SharedPreferences preferences;

 @Override public void onCreate(){
  super.onCreate();instance=this;preferences=getSharedPreferences("22cast",MODE_PRIVATE);
  NotificationManager nm=getSystemService(NotificationManager.class);
  if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("receiver","22Cast receiver",NotificationManager.IMPORTANCE_LOW));
  PendingIntent launch=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"receiver"):new Notification.Builder(this);
  Notification n=builder.setContentTitle("22Cast").setContentText("AirPlay receiver is running").setSmallIcon(dev.elias.cast22.R.drawable.icon).setContentIntent(launch).setOngoing(true).build();
  if(Build.VERSION.SDK_INT>=29)startForeground(22,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(22,n);
  WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
  if(wifi!=null){multicast=wifi.createMulticastLock("22Cast discovery");multicast.setReferenceCounted(false);multicast.acquire();}
  loadSettings();set("platform","android");set("streaming",false);set("video",false);set("pin","");
  receiver=new NativeReceiver(this);
  ConnectivityManager cm=getSystemService(ConnectivityManager.class);
  networkCallback=new ConnectivityManager.NetworkCallback(){
   @Override public void onAvailable(Network network){if(!paused&&!destroyed)control.execute(()->restart());}
   @Override public void onLost(Network network){if(!paused&&!destroyed)control.execute(()->restart());}
  };
  cm.registerDefaultNetworkCallback(networkCallback);
  control.execute(this::restart);
 }
 private void loadSettings(){
  set("name",preferences.getString("name","22Cast Living Room"));set("quality",preferences.getString("quality","1080"));
  set("fps",preferences.getInt("fps",30));set("audio",preferences.getBoolean("audio",true));
  set("pinRequired",preferences.getBoolean("pinRequired",false));set("keepAwake",preferences.getBoolean("keepAwake",true));
 }
 private void set(String k,Object v){synchronized(stateLock){try{current.put(k,v);}catch(JSONException ignored){}}}
 private void status(String s,String message){set("status",s);set("message",message);}
 private boolean connected(){
  ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network net=cm.getActiveNetwork();
  NetworkCapabilities caps=cm.getNetworkCapabilities(net);
  return caps!=null&&(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
 }
 private void restart(){
  if(destroyed)return;
  status("starting","Starting receiver…");set("streaming",false);set("video",false);set("pin","");set("receiverIp","");resetPlayback();receiver.stop();
  if(paused){status("paused","Receiver paused");return;}
  if(!connected()){status("error","Connect this screen to Wi-Fi or Ethernet");return;}
  String id=preferences.getString("id",null);
  if(id==null){byte[] b=new byte[6];new java.security.SecureRandom().nextBytes(b);b[0]=2;StringBuilder s=new StringBuilder();for(byte v:b){if(s.length()>0)s.append(':');s.append(String.format(Locale.US,"%02X",v&255));}id=s.toString();preferences.edit().putString("id",id).apply();}
  JSONObject state=snapshot();
  receiver.start(state.optString("name"),id,new File(getFilesDir(),"receiver.pem").getAbsolutePath(),Integer.parseInt(state.optString("quality","1080")),state.optInt("fps",30),state.optBoolean("pinRequired"),state.optBoolean("audio",true));
 }
 JSONObject snapshot(){
  synchronized(stateLock){try{
   JSONObject s=new JSONObject(current.toString());String ip="—";
   Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
   while(interfaces!=null&&interfaces.hasMoreElements()){
    NetworkInterface i=interfaces.nextElement();if(!i.isUp()||i.isLoopback())continue;
    Enumeration<InetAddress> addresses=i.getInetAddresses();while(addresses.hasMoreElements()){
     InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress())ip=a.getHostAddress();
    }
   }
   ConnectivityManager cm=getSystemService(ConnectivityManager.class);NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
   s.put("network",caps==null?"No network":caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"Wi-Fi");s.put("ip",s.optString("receiverIp","").isEmpty()?ip:s.optString("receiverIp"));return s;
  }catch(Exception e){return new JSONObject();}}
 }
 JSONObject command(String action,JSONObject data)throws JSONException{
  if(action.equals("settings")){
   String name=data.optString("name","").trim();
   if(name.isEmpty()||name.getBytes(StandardCharsets.UTF_8).length>50||name.matches(".*[\\x00-\\x1f].*"))throw new JSONException("Invalid receiver name");
   String quality=data.optString("quality","1080");if(!quality.equals("720")&&!quality.equals("1080"))throw new JSONException("Invalid resolution");
   int fps=data.optInt("fps",30);if(fps!=30&&fps!=60)throw new JSONException("Invalid frame rate");
   preferences.edit().putString("name",name).putString("quality",quality).putInt("fps",fps).putBoolean("audio",data.optBoolean("audio",true)).putBoolean("pinRequired",data.optBoolean("pinRequired")).putBoolean("keepAwake",data.optBoolean("keepAwake",true)).apply();loadSettings();
  }else if(action.equals("pause"))paused=true;
  else if(action.equals("resume"))paused=false;
  else if(!action.equals("restart")&&!action.equals("disconnect"))throw new JSONException("Unknown command");
  control.execute(this::restart);return new JSONObject().put("ok",true);
 }
 private void resetPlayback(){
  soundEpoch++;sound.execute(()->{if(audioTrack!=null){try{audioTrack.pause();audioTrack.flush();audioTrack.release();}catch(Exception ignored){}audioTrack=null;}});
  VideoSink sink=videoSink;if(sink!=null)sink.reset();
 }
 @Override public void event(String type,String value){
  if(destroyed)return;
  switch(type){
   case "address":set("receiverIp",value);break;
   case "ready":android.util.Log.i("22Cast","Receiver ready on port "+value);set("port",value);status("ready","Ready to connect");break;
   case "error":set("streaming",false);set("video",false);set("pin","");status("error",value);resetPlayback();break;
   case "client":set("sender",value);set("pin","");break;
   case "pin":set("pin",value);break;
   case "mirroring":case "videoActive":set("streaming",true);set("video",true);set("pin","");status("ready","Screen mirroring");break;
   case "audio":set("streaming",true);set("pin","");status("ready","Playing audio");break;
   case "disconnected":set("streaming",false);set("video",false);set("pin","");status(paused?"paused":"ready",paused?"Receiver paused":"Ready to connect");resetPlayback();break;
   case "flushAudio":soundEpoch++;sound.execute(()->{if(audioTrack!=null){try{audioTrack.pause();audioTrack.flush();audioTrack.play();}catch(Exception e){android.util.Log.w("22Cast","Audio flush",e);}}});break;
   case "flushVideo":VideoSink s=videoSink;if(s!=null)s.reset();break;
  }
 }
 @Override public void video(byte[] data,long pts){VideoSink s=videoSink;if(s!=null)s.frame(data,pts);}
 @Override public void format(int w,int h){VideoSink s=videoSink;if(s!=null)s.format(w,h);}
 @Override public void pcm(short[] data,int rate,int channels,long pts){
  if(destroyed||sound.getQueue().size()>20)return;
  final int epoch=soundEpoch;
  sound.execute(()->{if(epoch!=soundEpoch||destroyed)return;try{
   if(audioTrack==null||audioRate!=rate||audioChannels!=channels){
    if(audioTrack!=null)audioTrack.release();audioRate=rate;audioChannels=channels;
    int mask=channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
    int min=AudioTrack.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT);
    audioTrack=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min,rate*channels/5)).setTransferMode(AudioTrack.MODE_STREAM).build();audioTrack.play();
   }
   long wait=pts-System.currentTimeMillis()*1000000L+100000000L;
   if(wait>0&&wait<250000000L)android.os.SystemClock.sleep(wait/1000000L);
   if(epoch==soundEpoch&&!destroyed)audioTrack.write(data,0,data.length,AudioTrack.WRITE_BLOCKING);
  }catch(Exception e){android.util.Log.w("22Cast","Audio output",e);}});
 }
 @Override public int onStartCommand(Intent i,int flags,int id){return START_NOT_STICKY;}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){destroyed=true;instance=null;getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);control.execute(()->{receiver.stop();if(multicast!=null&&multicast.isHeld())multicast.release();});control.shutdown();soundEpoch++;sound.execute(()->{if(audioTrack!=null){audioTrack.release();audioTrack=null;}});sound.shutdown();super.onDestroy();}
}
