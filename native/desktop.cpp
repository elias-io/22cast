#include "receiver.h"
#include <SDL.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}
#include <atomic>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <csignal>

static std::atomic<bool> running{true},hideVideo{false};
static std::mutex outputMutex;
static std::string json(const char *s){std::string r="\"";for(;*s;s++){unsigned char c=*s;if(c=='"'||c=='\\'){r+='\\';r+=c;}else if(c=='\n')r+="\\n";else if(c=='\r')r+="\\r";else if(c>=32)r+=c;}return r+'"';}
static void emit(const char *event,const char *value){std::lock_guard<std::mutex> l(outputMutex);std::cout<<"{\"event\":"<<json(event)<<",\"value\":"<<json(value)<<"}"<<std::endl;}
struct Player {
 std::mutex mutex;
 AVCodecContext *codec=nullptr;
 AVFrame *decoded=nullptr,*latest=nullptr;
 SwsContext *convert=nullptr;
 SDL_AudioDeviceID audio=0;
 int rate=0,channels=0;
 Player(){codec=avcodec_alloc_context3(avcodec_find_decoder(AV_CODEC_ID_H264));codec->thread_count=2;codec->flags|=AV_CODEC_FLAG_LOW_DELAY;if(avcodec_open2(codec,codec->codec,nullptr)<0)emit("error","Could not start H.264 decoder");decoded=av_frame_alloc();}
 ~Player(){if(audio)SDL_CloseAudioDevice(audio);if(latest)av_frame_free(&latest);av_frame_free(&decoded);avcodec_free_context(&codec);if(convert)sws_freeContext(convert);}
 void video(const uint8_t *data,int n){std::lock_guard<std::mutex> l(mutex);AVPacket *packet=av_packet_alloc();av_new_packet(packet,n);std::memcpy(packet->data,data,n);if(avcodec_send_packet(codec,packet)>=0)while(avcodec_receive_frame(codec,decoded)==0){if(latest)av_frame_free(&latest);latest=av_frame_clone(decoded);}av_packet_free(&packet);}
 void pcm(const int16_t *data,int count,int r,int c){std::lock_guard<std::mutex> l(mutex);if(!audio||rate!=r||channels!=c){if(audio)SDL_CloseAudioDevice(audio);SDL_AudioSpec want{};want.freq=r;want.format=AUDIO_S16SYS;want.channels=c;want.samples=1024;audio=SDL_OpenAudioDevice(nullptr,0,&want,nullptr,0);rate=r;channels=c;if(audio)SDL_PauseAudioDevice(audio,0);else emit("warning","Audio output unavailable");}if(audio){if(SDL_GetQueuedAudioSize(audio)>r*c*2/3)SDL_ClearQueuedAudio(audio);SDL_QueueAudio(audio,data,count*2);}}
 void flush(){std::lock_guard<std::mutex> l(mutex);if(audio)SDL_ClearQueuedAudio(audio);avcodec_flush_buffers(codec);if(latest)av_frame_free(&latest);}
};
int main(int argc,char **argv){
 CastOptions options;options.name="22Cast Living Room";options.deviceId="02:22:CA:57:00:01";options.keyfile="";bool headless=false;
 for(int i=1;i<argc;i++){std::string a=argv[i];if(a=="--headless")headless=true;else if(a=="--pin")options.pin=true;else if(a=="--no-audio")options.audio=false;else if(i+1<argc){std::string v=argv[++i];if(a=="--name")options.name=v;else if(a=="--id")options.deviceId=v;else if(a=="--key")options.keyfile=v;else if(a=="--height"){options.height=std::stoi(v);options.width=options.height==720?1280:1920;}else if(a=="--fps")options.fps=std::stoi(v);}}
 std::signal(SIGINT,[](int){running=false;});std::signal(SIGTERM,[](int){running=false;});
 std::unique_ptr<Player> player;SDL_Window *window=nullptr;SDL_Renderer *renderer=nullptr;SDL_Texture *texture=nullptr;
 if(!headless){if(SDL_Init(SDL_INIT_VIDEO|SDL_INIT_AUDIO|SDL_INIT_EVENTS)<0){emit("error",SDL_GetError());return 2;}window=SDL_CreateWindow("22Cast — Screen Mirroring",SDL_WINDOWPOS_CENTERED,SDL_WINDOWPOS_CENTERED,1280,720,SDL_WINDOW_HIDDEN|SDL_WINDOW_RESIZABLE|SDL_WINDOW_ALLOW_HIGHDPI);renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_ACCELERATED|SDL_RENDERER_PRESENTVSYNC);if(!renderer)renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_SOFTWARE);player=std::make_unique<Player>();}
 CastCallbacks cb;
 cb.event=[&](const char *t,const char *v){
  // videoActive arrives per packet; only the first frame needs a UI event.
  if(std::string(t)!="videoActive")emit(t,v);
  if(std::string(t)=="disconnected"){hideVideo=true;if(player)player->flush();}
 };
 cb.video=[&](const uint8_t *d,int n,int64_t){if(player)player->video(d,n);};
 cb.pcm=[&](const int16_t *d,int n,int r,int c,int64_t){if(player)player->pcm(d,n,r,c);};
 cb.format=[](int w,int h){emit("format",(std::to_string(w)+"x"+std::to_string(h)).c_str());};
 CastReceiver receiver(std::move(cb));if(!receiver.start(options))return 1;
 std::thread input([]{std::string line;while(std::getline(std::cin,line)){if(line=="quit"){running=false;break;}}});input.detach();
 int tw=0,th=0;
 while(running){
  if(!headless){
   SDL_Event event;while(SDL_PollEvent(&event)){if(event.type==SDL_QUIT||(event.type==SDL_KEYDOWN&&event.key.keysym.sym==SDLK_ESCAPE)){receiver.stop();hideVideo=true;receiver.start(options);}}
   if(hideVideo.exchange(false))SDL_HideWindow(window);
   std::lock_guard<std::mutex> l(player->mutex);
   if(player->latest){AVFrame *frame=player->latest;int w=frame->width,h=frame->height;
    if(!texture||tw!=w||th!=h){if(texture)SDL_DestroyTexture(texture);texture=SDL_CreateTexture(renderer,SDL_PIXELFORMAT_IYUV,SDL_TEXTUREACCESS_STREAMING,w,h);tw=w;th=h;SDL_SetWindowSize(window,std::min(w,1440),std::min(h,900));}
    AVFrame *yuv=frame;AVFrame *converted=nullptr;
    if(frame->format!=AV_PIX_FMT_YUV420P){converted=av_frame_alloc();converted->width=w;converted->height=h;converted->format=AV_PIX_FMT_YUV420P;av_frame_get_buffer(converted,32);player->convert=sws_getCachedContext(player->convert,w,h,(AVPixelFormat)frame->format,w,h,AV_PIX_FMT_YUV420P,SWS_FAST_BILINEAR,nullptr,nullptr,nullptr);sws_scale(player->convert,frame->data,frame->linesize,0,h,converted->data,converted->linesize);yuv=converted;}
    SDL_UpdateYUVTexture(texture,nullptr,yuv->data[0],yuv->linesize[0],yuv->data[1],yuv->linesize[1],yuv->data[2],yuv->linesize[2]);SDL_ShowWindow(window);SDL_RenderSetLogicalSize(renderer,w,h);SDL_SetRenderDrawColor(renderer,0,0,0,255);SDL_RenderClear(renderer);SDL_RenderCopy(renderer,texture,nullptr,nullptr);SDL_RenderPresent(renderer);
    if(converted)av_frame_free(&converted);av_frame_free(&player->latest);
   }
  }
  std::this_thread::sleep_for(std::chrono::milliseconds(8));
 }
 receiver.stop();player.reset();if(texture)SDL_DestroyTexture(texture);if(renderer)SDL_DestroyRenderer(renderer);if(window)SDL_DestroyWindow(window);if(!headless)SDL_Quit();return 0;
}
