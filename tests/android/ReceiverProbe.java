package dev.elias.cast22;

import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.EditText;
import android.app.Dialog;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Test APK only. Never packaged with 22Cast. Injected video bypasses AirPlay transport. */
public final class ReceiverProbe extends Instrumentation {
 private String mode;
 private MainActivity activity;
 private ReceiverService service;
 private JSONObject saved;
 public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode","playback");start();}
 public void onStart(){
  Bundle result=new Bundle();int code=0;
  try{
   activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   waitForIdleSync();Thread.sleep(1500);service=ReceiverService.instance;
   if(service==null)throw new AssertionError("Receiver service unavailable");
   saved=service.snapshot();
   if(mode.equals("pairing"))pairing();else if(mode.equals("updates"))updates();else if(mode.equals("input"))input();else playback();
   result.putString("result","PASS: "+mode);
  }catch(Throwable e){result.putString("error",e.toString());code=1;try{if(activity!=null)screenshot("failure");}catch(Exception ignored){}}
  finally{if(saved!=null&&service!=null)try{service.command("settings",saved);}catch(Exception ignored){}}
  finish(code,result);
 }
 private void pairing()throws Exception{
  CountDownLatch stop=new CountDownLatch(1);
  BroadcastReceiver control=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
   if(intent.getAction().endsWith("STOP")){stop.countDown();return;}
   new Thread(()->{Bundle update=new Bundle();try{socketRecovery();update.putString("recovery","PASS");}catch(Exception e){update.putString("recovery",e.toString());}sendStatus(0,update);}).start();
  }};
  IntentFilter filter=new IntentFilter("dev.elias.cast22.probe.STOP");filter.addAction("dev.elias.cast22.probe.RECOVERY");
  getTargetContext().registerReceiver(control,filter,Context.RECEIVER_EXPORTED);
  try{
   String previousPort=service.snapshot().optString("port");
   service.command("settings",new JSONObject(saved.toString()).put("pinRequired",true));
   long deadline=System.currentTimeMillis()+10000;
   while(System.currentTimeMillis()<deadline){
    JSONObject state=service.snapshot();
    if(state.optString("status").equals("ready")&&!state.optString("port").equals(previousPort))break;
    Thread.sleep(50);
   }
   String previous="";deadline=System.currentTimeMillis()+60000;
   while(stop.getCount()>0&&System.currentTimeMillis()<deadline){
    String state=service.snapshot().toString();
    if(!state.equals(previous)){Bundle update=new Bundle();update.putString("state",state);sendStatus(0,update);previous=state;}
    stop.await(50,TimeUnit.MILLISECONDS);
   }
  }finally{getTargetContext().unregisterReceiver(control);}
 }
 private JSONObject release()throws Exception{
  return new JSONObject().put("schemaVersion",1).put("packageId","dev.elias.cast22")
   .put("versionName","0.4.0").put("versionCode",7).put("minSdk",24)
   .put("apkUrl","https://22cast.elias.dev/downloads/22Cast-0.4.0.apk")
   .put("sha256","0123456789abcdef".repeat(4)).put("releaseNotes","A test update.")
   .put("storeUrl",JSONObject.NULL);
 }
 private ReleaseUpdate parsed(JSONObject release,long current,int sdk){
  return UpdateCheck.parse(release.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),current,sdk);
 }
 private void rejected(String key,Object value)throws Exception{
  if(parsed(release().put(key,value),6,36)!=null)throw new AssertionError("Accepted invalid release "+key+": "+value);
 }
 private void updates()throws Exception{
  ReleaseUpdate update=parsed(release(),6,36);
  if(update==null||update.getVersionCode()!=7)throw new AssertionError("Valid update rejected");
  if(parsed(release().put("extra","ignored"),6,36)==null)throw new AssertionError("Extra field rejected");
  if(parsed(release(),7,36)!=null||parsed(release(),8,36)!=null)throw new AssertionError("Old/current update offered");
  rejected("schemaVersion",2);rejected("schemaVersion","1");rejected("packageId","com.twentytwo.cast");
  rejected("versionCode","7");rejected("versionCode",7.1);rejected("versionCode",0);rejected("versionCode",2147483648L);
  rejected("minSdk",99);rejected("versionName","bad\nversion");rejected("sha256","invalid");
  for(String url:new String[]{"http://22cast.elias.dev/downloads/app.apk","https://evil.example/downloads/app.apk",
   "https://22cast.elias.dev.evil.example/downloads/app.apk","https://user@22cast.elias.dev/downloads/app.apk",
   "https://22cast.elias.dev:444/downloads/app.apk","https://22cast.elias.dev/downloads/app.apk?tracking=id",
   "https://22cast.elias.dev/downloads/app.apk#fragment","https://22cast.elias.dev/elsewhere/app.apk"})rejected("apkUrl",url);
  for(byte[] body:new byte[][]{"not json".getBytes(),(release()+" junk").getBytes(),new byte[32769],new byte[]{(byte)0xc3,(byte)0x28}})
   if(UpdateCheck.parse(body,6,36)!=null)throw new AssertionError("Malformed response accepted");
  if(UpdateCheck.readBody(new ByteArrayInputStream(new byte[32768]),-1,Long.MAX_VALUE).length!=32768)throw new AssertionError("Bounded response failed");
  for(long length:new long[]{-1,32769}){
   boolean refused=false;try{UpdateCheck.readBody(new ByteArrayInputStream(new byte[32769]),length,Long.MAX_VALUE);}catch(IllegalArgumentException expected){refused=true;}
   if(!refused)throw new AssertionError("Oversized response accepted");
  }
  // A recommendation waits for open settings, a PIN prompt, and active playback.
  readUi(()->{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("showSettings");method.setAccessible(true);method.invoke(activity);return null;});
  readUi(()->{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("pendingUpdate");field.setAccessible(true);field.set(activity,update);return null;});
  Thread.sleep(700);expectText(R.id.dialog_title,"Settings");
  service.event("pin","2468");Thread.sleep(700);
  if(readUi(()->modal())!=null)throw new AssertionError("Update interrupted PIN prompt");
  service.event("audio","");Thread.sleep(700);
  if(readUi(()->modal())!=null)throw new AssertionError("Update interrupted playback");
  service.event("disconnected","");Thread.sleep(900);
  expectText(R.id.dialog_title,"An update is available");expectFocus(R.id.update_later);screenshot("update-recommendation");
  key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.update_download);
  key(KeyEvent.KEYCODE_DPAD_UP);expectFocus(R.id.update_later);key(KeyEvent.KEYCODE_DPAD_CENTER);
  Thread.sleep(700);if(readUi(()->modal())!=null)throw new AssertionError("Later did not dismiss update");
  readUi(()->{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("pendingUpdate");field.setAccessible(true);field.set(activity,update);return null;});
  Thread.sleep(700);if(readUi(()->modal())!=null)throw new AssertionError("Update repeated during same launch");
 }
 private boolean imeVisible()throws Exception{
  if(android.os.Build.VERSION.SDK_INT>=30&&Boolean.TRUE.equals(readUi(()->{
   Dialog dialog=modal();View decor=dialog==null?activity.getWindow().getDecorView():dialog.getWindow().getDecorView();
   WindowInsets insets=decor.getRootWindowInsets();return insets!=null&&insets.isVisible(WindowInsets.Type.ime());
  })))return true;
  for(AccessibilityWindowInfo window:getUiAutomation().getWindows())
   if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)return true;
  return false;
 }
 private void awaitIme(boolean visible)throws Exception{
  long deadline=SystemClock.uptimeMillis()+8000;
  while(SystemClock.uptimeMillis()<deadline){if(imeVisible()==visible){Thread.sleep(200);return;}Thread.sleep(100);}
  throw new AssertionError(visible?"Remote activation did not show the real system IME":"System IME did not hide after finishing input");
 }
 private InputConnection nameInput()throws Exception{
  return (InputConnection)readUi(()->{
   EditText name=(EditText)find(R.id.device_name);
   if(!name.hasFocus())throw new IllegalStateException("Name editor lost focus during IME input");
   InputConnection input=name.onCreateInputConnection(new EditorInfo());
   if(input==null)throw new IllegalStateException("Name editor has no input connection");return input;
  });
 }
 private void enterName(String value)throws Exception{
  InputConnection input=nameInput();
  readUi(()->{
   EditText name=(EditText)find(R.id.device_name);input.beginBatchEdit();
   try{
    input.finishComposingText();
    if(!input.setSelection(0,name.length())||!input.commitText(value,1))throw new IllegalStateException("IME could not replace receiver name");
   }finally{input.endBatchEdit();}
   return null;
  });
  expectText(R.id.device_name,value);
 }
 private void finishName()throws Exception{
  InputConnection input=nameInput();
  readUi(()->{if(!input.performEditorAction(EditorInfo.IME_ACTION_DONE))throw new IllegalStateException("IME Done was not accepted");return null;});
  awaitIme(false);expectText(R.id.dialog_title,"Settings");expectFocus(R.id.quality);
 }
 private void openSettings()throws Exception{
  expectFocus(R.id.dock_settings);key(KeyEvent.KEYCODE_DPAD_CENTER);expectText(R.id.dialog_title,"Settings");
 }
 private void goToAction(boolean save)throws Exception{
  expectFocus(R.id.quality);
  int[] route=save?new int[]{R.id.fps,R.id.audio,R.id.pin_required,R.id.keep_awake,R.id.cancel_settings,R.id.save_settings}
                  :new int[]{R.id.fps,R.id.audio,R.id.pin_required,R.id.keep_awake,R.id.cancel_settings};
  for(int id:route){key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(id);}
  key(KeyEvent.KEYCODE_DPAD_CENTER);
 }
 private void expectSavedName(String expected)throws Exception{
  long deadline=SystemClock.uptimeMillis()+5000;
  while(SystemClock.uptimeMillis()<deadline&&!expected.equals(service.snapshot().optString("name")))Thread.sleep(50);
  if(!expected.equals(service.snapshot().optString("name")))throw new AssertionError("Receiver did not apply edited name");
  String stored=getTargetContext().getSharedPreferences("22cast",Context.MODE_PRIVATE).getString("name","");
  if(!expected.equals(stored))throw new AssertionError("Edited name was not persisted in preferences");
 }
 private void input()throws Exception{
  AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
  info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
  // Navigation and activation use remote keys. Never force editor focus or mutate it with setText.
  expectFocus(R.id.dock_connect);key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_RIGHT);openSettings();
  awaitIme(false);key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.device_name);
  key(KeyEvent.KEYCODE_DPAD_CENTER);awaitIme(true);screenshot("input-remote-keyboard");
  enterName("22Cast Remote Probe");finishName();goToAction(true);expectSavedName("22Cast Remote Probe");
  openSettings();expectText(R.id.device_name,"22Cast Remote Probe");
  key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.device_name);key(KeyEvent.KEYCODE_DPAD_CENTER);awaitIme(true);
  enterName("22Cast Unsaved Probe");key(KeyEvent.KEYCODE_BACK);awaitIme(false);
  expectText(R.id.dialog_title,"Settings");expectFocus(R.id.device_name);
  key(KeyEvent.KEYCODE_DPAD_DOWN);goToAction(false);expectSavedName("22Cast Remote Probe");
  openSettings();expectText(R.id.device_name,"22Cast Remote Probe");
  float[] point=(float[])readUi(()->{View name=find(R.id.device_name);int[] location=new int[2];name.getLocationOnScreen(location);return new float[]{location[0]+name.getWidth()/2f,location[1]+name.getHeight()/2f};});
  tap(point[0],point[1]);awaitIme(true);enterName("22Cast Touch Probe");finishName();goToAction(false);
  expectSavedName("22Cast Remote Probe");openSettings();expectText(R.id.device_name,"22Cast Remote Probe");
  screenshot("input-persisted-name");key(KeyEvent.KEYCODE_BACK);
 }
 private void socketRecovery()throws Exception{
  int port=Integer.parseInt(service.snapshot().getString("port"));
  byte[] request="GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  for(int i=0;i<64;i++)try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)){
   socket.getOutputStream().write(request);socket.setSoLinger(true,0);
  }
  try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)){
   socket.setSoTimeout(5000);socket.getOutputStream().write(request);
   String line=new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
   if(line==null||!line.contains("200"))throw new IOException("Receiver unavailable after socket resets: "+line);
  }
 }
 private Object readUi(Callable<Object> query)throws Exception{
  Object[] value={null};Exception[] error={null};
  runOnMainSync(()->{try{value[0]=query.call();}catch(Exception e){error[0]=e;}});
  if(error[0]!=null)throw error[0];return value[0];
 }
 private Dialog modal()throws Exception{
  java.lang.reflect.Field field=MainActivity.class.getDeclaredField("modal");field.setAccessible(true);return (Dialog)field.get(activity);
 }
 private View find(int id)throws Exception{
  Dialog dialog=modal();View view=dialog==null?null:dialog.findViewById(id);
  return view==null?activity.findViewById(id):view;
 }
 private void expectFocus(int id)throws Exception{
  if(!Boolean.TRUE.equals(readUi(()->find(id)!=null&&find(id).hasFocus())))throw new AssertionError("Expected native focus: "+getTargetContext().getResources().getResourceEntryName(id));
 }
 private void expectText(int id,String expected)throws Exception{
  Object value=readUi(()->((TextView)find(id)).getText().toString());if(!expected.equals(value))throw new AssertionError(value+" != "+expected);
 }
 private void expectVisibility(int id,int expected)throws Exception{
  Object actual=readUi(()->find(id).getVisibility());if(!Integer.valueOf(expected).equals(actual))throw new AssertionError("Unexpected visibility for "+id+": "+actual);
 }
 private boolean hasBrowser(View view){
  if(view.getClass().getName().startsWith("android.webkit."))return true;
  if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasBrowser(group.getChildAt(i)))return true;}
  return false;
 }
 private void awaitStatus(String status)throws Exception{
  long deadline=System.currentTimeMillis()+10000;
  while(!service.snapshot().optString("status").equals(status)&&System.currentTimeMillis()<deadline)Thread.sleep(50);
  if(!service.snapshot().optString("status").equals(status))throw new AssertionError(service.snapshot().toString());
 }
 private void key(int key)throws Exception{sendKeyDownUpSync(key);waitForIdleSync();Thread.sleep(100);}
 private void screenshot(String name)throws Exception{
  waitForIdleSync();Thread.sleep(500);
  File folder=new File(getTargetContext().getExternalFilesDir(null),"validation");folder.mkdirs();
  Bitmap image=getUiAutomation().takeScreenshot();
  try(OutputStream stream=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{image.recycle();}
 }
 private List<byte[]> frames(String asset)throws Exception{
  byte[] data;try(InputStream stream=getContext().getAssets().open(asset);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1)out.write(buffer,0,n);data=out.toByteArray();
  }
  ArrayList<Integer> starts=new ArrayList<>();
  for(int i=0;i+5<data.length;i++){
   int prefix=data[i]==0&&data[i+1]==0&&data[i+2]==0&&data[i+3]==1?4:data[i]==0&&data[i+1]==0&&data[i+2]==1?3:0;
   if(prefix>0){if((data[i+prefix]&31)==9)starts.add(i);i+=prefix-1;}
  }
  starts.add(data.length);ArrayList<byte[]> result=new ArrayList<>();
  for(int i=0;i+1<starts.size();i++)result.add(Arrays.copyOfRange(data,starts.get(i),starts.get(i+1)));
  return result;
 }
 private void play(String asset,int width,int height,boolean burst)throws Exception{
  service.format(width,height);service.event("client","22Cast playback check");service.event("mirroring","");
  List<byte[]> frames=frames(asset);long base=System.currentTimeMillis()*1000000L;
  if(burst){for(int j=0;j<5;j++)for(byte[] data:frames)service.video(data,base);Thread.sleep(300);}
  for(int i=0;i<frames.size();i++){service.video(frames.get(i),base+i*33333333L);Thread.sleep(33);}
  Thread.sleep(600);expectVisibility(R.id.home,View.GONE);
  key(KeyEvent.KEYCODE_DPAD_RIGHT);expectVisibility(R.id.stream_bar,View.VISIBLE);
  if(service.snapshot().optString("status").equals("error"))throw new AssertionError(service.snapshot().toString());
 }
 private void tap(float x,float y)throws Exception{
  long down=SystemClock.uptimeMillis();
  for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){
   MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
   event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
   try{sendPointerSync(event);}finally{event.recycle();}
   Thread.sleep(60);
  }
  waitForIdleSync();Thread.sleep(150);
 }
 private void overlay()throws Exception{
  float[] disconnectPoint=(float[])readUi(()->{View view=find(R.id.disconnect);int[] point=new int[2];view.getLocationOnScreen(point);return new float[]{point[0]+view.getWidth()/2f,point[1]+view.getHeight()/2f};});
  Thread.sleep(5500);expectVisibility(R.id.stream_bar,View.GONE);expectVisibility(R.id.home,View.GONE);screenshot("overlay-hidden");
  for(int code:new int[]{KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER}){
   key(code);expectVisibility(R.id.stream_bar,View.VISIBLE);expectFocus(R.id.disconnect);
   if(!service.snapshot().optBoolean("streaming"))throw new AssertionError("Wake-up key disconnected the sender: "+code);
   Thread.sleep(5200);expectVisibility(R.id.stream_bar,View.GONE);
  }
  // A tap where Disconnect will appear must only reveal the controls.
  tap(disconnectPoint[0],disconnectPoint[1]);expectVisibility(R.id.stream_bar,View.VISIBLE);
  if(!service.snapshot().optBoolean("streaming"))throw new AssertionError("Wake-up tap disconnected the sender");
  screenshot("overlay-touch");Thread.sleep(5200);expectVisibility(R.id.stream_bar,View.GONE);
  key(KeyEvent.KEYCODE_DPAD_UP);Thread.sleep(3000);key(KeyEvent.KEYCODE_DPAD_LEFT);
  Thread.sleep(2500);expectVisibility(R.id.stream_bar,View.VISIBLE);
  Thread.sleep(2800);expectVisibility(R.id.stream_bar,View.GONE);
  if(!service.snapshot().optBoolean("streaming"))throw new AssertionError("Hiding controls interrupted playback");
 }
 private void playback()throws Exception{
  if(Boolean.TRUE.equals(readUi(()->hasBrowser(activity.getWindow().getDecorView()))))throw new AssertionError("Android TV must use native views, without a WebView");
  screenshot("focus-first");key(KeyEvent.KEYCODE_DPAD_LEFT);expectFocus(R.id.dock_about);screenshot("focus-last");key(KeyEvent.KEYCODE_DPAD_RIGHT);
  key(KeyEvent.KEYCODE_DPAD_CENTER);expectText(R.id.dialog_title,"A bigger view, in three steps.");key(KeyEvent.KEYCODE_BACK);
  key(KeyEvent.KEYCODE_DPAD_RIGHT);expectFocus(R.id.dock_network);
  key(KeyEvent.KEYCODE_DPAD_CENTER);expectText(R.id.dialog_title,"Network");
  key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.action_pause);key(KeyEvent.KEYCODE_DPAD_CENTER);awaitStatus("paused");Thread.sleep(600);expectText(R.id.action_pause,"Resume receiver");
  key(KeyEvent.KEYCODE_DPAD_CENTER);awaitStatus("ready");key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.action_restart);key(KeyEvent.KEYCODE_DPAD_CENTER);Thread.sleep(300);awaitStatus("ready");
  key(KeyEvent.KEYCODE_BACK);expectFocus(R.id.dock_network);
  key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);expectText(R.id.dialog_title,"Settings");
  key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.quality);
  String quality=(String)readUi(()->((TextView)find(R.id.quality)).getText().toString());key(KeyEvent.KEYCODE_DPAD_RIGHT);
  expectText(R.id.quality,quality.equals("1080p")?"720p":"1080p");key(KeyEvent.KEYCODE_DPAD_LEFT);
  key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.fps);
  for(int i=0;i<5;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
  expectFocus(R.id.save_settings);screenshot("settings-720p");
  if(!Boolean.TRUE.equals(readUi(()->{View button=find(R.id.save_settings);int[] point=new int[2];button.getLocationOnScreen(point);return point[1]>=0&&point[1]+button.getHeight()<=activity.getResources().getDisplayMetrics().heightPixels;})))throw new AssertionError("Native Settings actions are outside the display");
  key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.close_dialog);
  key(KeyEvent.KEYCODE_BACK);if(readUi(()->modal())!=null)throw new AssertionError("Native dialog did not close");
  key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);expectText(R.id.dialog_title,"A little closer.");
  key(KeyEvent.KEYCODE_DPAD_DOWN);expectFocus(R.id.about_licenses);key(KeyEvent.KEYCODE_DPAD_CENTER);expectText(R.id.dialog_title,"Open-source licenses");
  key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_DPAD_LEFT);expectFocus(R.id.dock_settings);
  key(KeyEvent.KEYCODE_DPAD_CENTER);expectText(R.id.dialog_title,"Settings");
  readUi(()->{((EditText)find(R.id.device_name)).setText("22Cast Native Probe");find(R.id.save_settings).requestFocus();return null;});
  key(KeyEvent.KEYCODE_DPAD_CENTER);Thread.sleep(300);
  long readyDeadline=System.currentTimeMillis()+10000;
  while(!service.snapshot().optString("status").equals("ready")&&System.currentTimeMillis()<readyDeadline)Thread.sleep(50);
  if(!service.snapshot().optString("name").equals("22Cast Native Probe"))throw new AssertionError("Native settings did not save");
  Thread.sleep(2000);
  screenshot("home");
  service.event("pin","2468");Thread.sleep(700);
  Object displayedPin=readUi(()->{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("pinDialog");field.setAccessible(true);Dialog dialog=(Dialog)field.get(activity);return ((TextView)dialog.findViewById(R.id.pin_code)).getText().toString();});
  if(!"2468".equals(displayedPin))throw new AssertionError("Native PIN prompt did not display the receiver code");screenshot("pin");
  service.event("client","22Cast Native Probe");Thread.sleep(700);
  play("landscape.h264",1280,720,false);expectFocus(R.id.disconnect);screenshot("landscape");overlay();
  play("portrait.h264",360,640,false);screenshot("portrait");
  play("landscape.h264",1280,720,true);screenshot("overflow-recovery");
  byte[] oversized=new byte[8*1024*1024];oversized[3]=1;oversized[4]=0x65;
  service.video(oversized,System.currentTimeMillis()*1000000L);
  long deadline=System.currentTimeMillis()+5000;
  while(!service.snapshot().optString("status").equals("error")&&System.currentTimeMillis()<deadline)Thread.sleep(50);
  if(!service.snapshot().optString("status").equals("error"))throw new AssertionError("Decoder failure was not reported");
  Thread.sleep(1000);
  expectVisibility(R.id.home,View.VISIBLE);
  service.event("disconnected","");
  play("landscape.h264",1280,720,false);screenshot("restart-recovery");
  service.event("disconnected","");Thread.sleep(1000);expectFocus(R.id.dock_settings);
  service.event("client","22Cast audio check");service.event("audio","");Thread.sleep(700);
  expectVisibility(R.id.home,View.VISIBLE);expectVisibility(R.id.stream_bar,View.VISIBLE);expectText(R.id.receiver_name,"22Cast audio check");screenshot("audio-interface");
  service.event("disconnected","");
 }
}
