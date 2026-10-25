#include "receiver.h"
#include <jni.h>
#include <memory>

static JavaVM *vm;
struct Environment {
 JNIEnv *env=nullptr;bool attached=false;
 Environment(){if(vm->GetEnv((void**)&env,JNI_VERSION_1_6)!=JNI_OK){vm->AttachCurrentThread(&env,nullptr);attached=true;}}
 ~Environment(){if(attached)vm->DetachCurrentThread();}
};
struct AndroidReceiver {
 jobject target; jmethodID event,video,pcm,format;
 std::unique_ptr<CastReceiver> receiver;
 AndroidReceiver(JNIEnv *e,jobject object):target(e->NewGlobalRef(object)){
  jclass c=e->GetObjectClass(object);event=e->GetMethodID(c,"onEvent","(Ljava/lang/String;Ljava/lang/String;)V");
  video=e->GetMethodID(c,"onVideo","([BJ)V");pcm=e->GetMethodID(c,"onPcm","([SIIJ)V");format=e->GetMethodID(c,"onFormat","(II)V");e->DeleteLocalRef(c);
  CastCallbacks cb;
  cb.event=[this](const char*t,const char*v){Environment x;auto a=x.env->NewStringUTF(t),b=x.env->NewStringUTF(v);x.env->CallVoidMethod(target,event,a,b);x.env->DeleteLocalRef(a);x.env->DeleteLocalRef(b);if(x.env->ExceptionCheck())x.env->ExceptionClear();};
  cb.video=[this](const uint8_t*d,int n,int64_t pts){if(n<1||n>4*1024*1024)return;Environment x;auto a=x.env->NewByteArray(n);if(!a)return;x.env->SetByteArrayRegion(a,0,n,(jbyte*)d);x.env->CallVoidMethod(target,video,a,(jlong)pts);x.env->DeleteLocalRef(a);if(x.env->ExceptionCheck())x.env->ExceptionClear();};
  cb.pcm=[this](const int16_t*d,int n,int r,int c,int64_t pts){Environment x;auto a=x.env->NewShortArray(n);if(!a)return;x.env->SetShortArrayRegion(a,0,n,d);x.env->CallVoidMethod(target,pcm,a,r,c,(jlong)pts);x.env->DeleteLocalRef(a);if(x.env->ExceptionCheck())x.env->ExceptionClear();};
  cb.format=[this](int w,int h){Environment x;x.env->CallVoidMethod(target,format,w,h);if(x.env->ExceptionCheck())x.env->ExceptionClear();};
  receiver=std::make_unique<CastReceiver>(std::move(cb));
 }
 ~AndroidReceiver(){receiver.reset();Environment e;e.env->DeleteGlobalRef(target);}
};
static std::string string(JNIEnv*e,jstring s){const char *p=e->GetStringUTFChars(s,nullptr);std::string v=p;e->ReleaseStringUTFChars(s,p);return v;}
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *jvm,void*){vm=jvm;return JNI_VERSION_1_6;}
extern "C" JNIEXPORT jlong JNICALL Java_dev_elias_cast22_NativeReceiver_nativeStart(JNIEnv *e,jobject self,jstring name,jstring id,jstring key,jint height,jint fps,jboolean pin,jboolean audio){
 auto p=std::make_unique<AndroidReceiver>(e,self);CastOptions o;o.name=string(e,name);o.deviceId=string(e,id);o.keyfile=string(e,key);o.height=height;o.width=height==720?1280:1920;o.fps=fps;o.pin=pin;o.audio=audio;
 if(!p->receiver->start(o))return 0;return reinterpret_cast<jlong>(p.release());
}
extern "C" JNIEXPORT void JNICALL Java_dev_elias_cast22_NativeReceiver_nativeStop(JNIEnv*,jobject,jlong ptr){delete reinterpret_cast<AndroidReceiver*>(ptr);}
