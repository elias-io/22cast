#include "receiver.h"
#include "raop.h"
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/samplefmt.h>
}
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>
#include <algorithm>
#include <arpa/inet.h>

struct CastReceiver::Impl {
 CastCallbacks cb;
 CastOptions options;
 raop_t *raop=nullptr;
 dnssd_t *dns=nullptr;
 AVCodecContext *audioDecoder=nullptr;
 AVFrame *audioFrame=av_frame_alloc();
 std::mutex audioMutex;
 std::atomic<bool> streaming{false};
 std::atomic<bool> videoActive{false};
 std::atomic<float> volume{1};
 int audioType=0, audioFrames=480;
 unsigned short boundPort=0;
 std::vector<int16_t> pcm{std::vector<int16_t>(32768)};
 explicit Impl(CastCallbacks callbacks):cb(std::move(callbacks)){}
 ~Impl(){av_frame_free(&audioFrame);}
 void event(const char *type,const char *value="") {if(cb.event)cb.event(type,value);}
 void resetAudio() {
  if(audioDecoder)avcodec_free_context(&audioDecoder);
  audioType=0;
 }
 void decode(audio_decode_struct *d) {
  if(!options.audio||d->data_len<=0)return;
  std::lock_guard<std::mutex> guard(audioMutex);
  if(audioType!=d->ct){
   resetAudio();audioType=d->ct;
   if(audioType==8||audioType==4||audioType==2){
    const AVCodec *codec=avcodec_find_decoder(audioType==2?AV_CODEC_ID_ALAC:AV_CODEC_ID_AAC);
    audioDecoder=avcodec_alloc_context3(codec);
    uint8_t eld[]={0xf8,0xe8,0x50,0x00},lc[]={0x12,0x10};
    uint8_t alac[]={0,0,0,36,'a','l','a','c',0,0,0,0,0,0,1,96,0,16,40,10,14,2,0,255,0,0,0,0,0,0,0,0,0,0,172,68};
    int frames=std::max(audioFrames,352);alac[12]=frames>>24;alac[13]=frames>>16;alac[14]=frames>>8;alac[15]=frames;
    uint8_t *config=audioType==2?alac:audioType==8?eld:lc;int size=audioType==2?36:audioType==8?4:2;
    audioDecoder->sample_rate=44100;av_channel_layout_default(&audioDecoder->ch_layout,2);
    audioDecoder->extradata=(uint8_t*)av_mallocz(size+AV_INPUT_BUFFER_PADDING_SIZE);
    std::memcpy(audioDecoder->extradata,config,size);audioDecoder->extradata_size=size;
    if(avcodec_open2(audioDecoder,codec,nullptr)<0){resetAudio();event("warning","Audio decoder could not start");return;}
   }
  }
  int count=0,rate=44100,channels=2;
  if(audioDecoder){
   AVPacket *packet=av_packet_alloc();if(av_new_packet(packet,d->data_len)<0){av_packet_free(&packet);return;}
   std::memcpy(packet->data,d->data,d->data_len);int result=avcodec_send_packet(audioDecoder,packet);av_packet_free(&packet);
   if(result<0||avcodec_receive_frame(audioDecoder,audioFrame)<0)return;
   rate=audioFrame->sample_rate;channels=audioFrame->ch_layout.nb_channels;
   if(channels<1||channels>2)return;count=audioFrame->nb_samples*channels;if(count>(int)pcm.size())return;
   AVSampleFormat fmt=(AVSampleFormat)audioFrame->format;bool planar=av_sample_fmt_is_planar(fmt);
   for(int f=0;f<audioFrame->nb_samples;f++)for(int c=0;c<channels;c++){
    const uint8_t *buffer=audioFrame->extended_data[planar?c:0];int index=planar?f:f*channels+c;double value=0;
    switch(fmt){case AV_SAMPLE_FMT_FLT:case AV_SAMPLE_FMT_FLTP:value=((const float*)buffer)[index]*32767.;break;
     case AV_SAMPLE_FMT_S16:case AV_SAMPLE_FMT_S16P:value=((const int16_t*)buffer)[index];break;
     case AV_SAMPLE_FMT_S32:case AV_SAMPLE_FMT_S32P:value=((const int32_t*)buffer)[index]/65536.;break;
     case AV_SAMPLE_FMT_DBL:case AV_SAMPLE_FMT_DBLP:value=((const double*)buffer)[index]*32767.;break;default:break;}
    pcm[f*channels+c]=(int16_t)std::max(-32768.,std::min(32767.,value));
   }
  }else if(audioType==1){count=std::min((int)pcm.size(),d->data_len/2);std::memcpy(pcm.data(),d->data,count*2);}
  if(count<=0||count>(int)pcm.size())return;
  float gain=volume.load();if(gain<.999f)for(int i=0;i<count;i++)pcm[i]=(int16_t)(pcm[i]*gain);
  if(!streaming.exchange(true))event("audio");
  if(cb.pcm)cb.pcm(pcm.data(),count,rate,channels,d->ntp_time_local);
 }
};

CastReceiver::CastReceiver(CastCallbacks callbacks):impl(new Impl(std::move(callbacks))){}
CastReceiver::~CastReceiver(){stop();}
int CastReceiver::port()const{return impl->boundPort;}
bool CastReceiver::start(const CastOptions &options){
 stop();auto *p=impl.get();p->options=options;
 if(options.name.empty()||options.name.size()>50){p->event("error","Choose a shorter receiver name");return false;}
 raop_callbacks_t c{};c.cls=p;
 c.conn_init=[](void*){};c.conn_destroy=[](void*){};
 c.conn_feedback=[](void*){};
 c.conn_reset=[](void *v,int){auto *p=(Impl*)v;p->streaming=false;p->videoActive=false;p->event("disconnected");};
 c.video_reset=[](void *v,reset_type_t){auto *p=(Impl*)v;p->streaming=false;p->videoActive=false;p->event("disconnected");};
 c.video_pause=[](void *v){((Impl*)v)->event("videoPaused");};
 c.video_resume=[](void *v){((Impl*)v)->event("videoResumed");};
 c.video_flush=[](void *v){((Impl*)v)->event("flushVideo");};
 c.audio_flush=[](void *v){((Impl*)v)->event("flushAudio");};
 c.video_set_codec_and_size=[](void *v,video_codec_t codec,unsigned short sw,unsigned short sh,unsigned short,unsigned short,unsigned short w,unsigned short h)->int{
  auto *p=(Impl*)v;if(codec!=VIDEO_CODEC_H264)return -1;if(p->cb.format)p->cb.format(sw?sw:w,sh?sh:h);return 0;
 };
 c.video_process=[](void *v,raop_ntp_t*,video_decode_struct *d){auto *p=(Impl*)v;if(d->is_h265)return;p->streaming=true;if(!p->videoActive.exchange(true))p->event("mirroring");if(p->cb.video)p->cb.video(d->data,d->data_len,d->ntp_time_local);};
 c.audio_process=[](void *v,raop_ntp_t*,audio_decode_struct *d){((Impl*)v)->decode(d);};
 c.audio_get_format=[](void *v,unsigned char*,unsigned short *spf,bool*,bool*,uint64_t*){((Impl*)v)->audioFrames=*spf;};
 c.audio_set_client_volume=[](void*)->double{return -6.0;};
 c.audio_set_volume=[](void *v,float db){((Impl*)v)->volume=db<=-144?0:std::min(1.0f,std::pow(10.f,db/20));};
 c.audio_set_metadata=[](void*,const void*,int){};c.audio_set_coverart=[](void*,const void*,int){};
 c.audio_stop_coverart_rendering=[](void*){};c.audio_remote_control_id=[](void*,const char*,const char*){};
 c.audio_set_progress=[](void*,uint32_t*,uint32_t*,uint32_t*){};
 c.report_client_request=[](void *v,char*,char*,char *name,bool *admit){*admit=true;((Impl*)v)->event("client",name?name:"Apple device");};
 c.display_pin=[](void *v,char *pin){((Impl*)v)->event("pin",pin);};
 // PIN is required on every new setup. Identity persists; no prior client is silently trusted.
 c.register_client=[](void*,const char*,const char*,const char*){};
 c.check_register=[](void*,const char*)->bool{return false;};
 c.passwd=[](void*,int *len)->const char*{*len=0;return "";};
 c.export_dacp=[](void*,const char*,const char*){};
 c.mirror_video_running=[](void *v,bool running){if(!running){auto*p=(Impl*)v;p->streaming=false;p->videoActive=false;p->event("disconnected");}};
 // URL-based HLS is not advertised. Supply safe handlers for unsolicited requests.
 c.on_video_play=[](void *v,const char*,float){((Impl*)v)->event("warning","Use Screen Mirroring to share this video");};
 c.on_video_stop=[](void*){};c.on_video_rate=[](void*,float){};c.on_video_scrub=[](void*,float){};
 c.on_video_playlist_remove=[](void*)->float{return 0;};
 c.on_video_acquire_playback_info=[](void*,playback_info_t *i){std::memset(i,0,sizeof(*i));};
 c.get_custom_profile=[](void*,const char **profile){*profile="";};
 unsigned char mac[6]={0x02,0x22,0x22,0,0,1};
 unsigned values[6];if(std::sscanf(options.deviceId.c_str(),"%x:%x:%x:%x:%x:%x",&values[0],&values[1],&values[2],&values[3],&values[4],&values[5])==6)for(int i=0;i<6;i++)mac[i]=values[i];
 int error=0;p->dns=dnssd_init(options.name.c_str(),options.name.size(),(char*)mac,6,options.pin?1:0,&error);
 if(!p->dns){p->event("error","Could not initialize network discovery");return false;}
 // Advertise the implemented mirror/audio modes, without HLS or HEVC.
 dnssd_set_airplay_features(p->dns,0,0);dnssd_set_airplay_features(p->dns,4,0);dnssd_set_airplay_features(p->dns,42,0);
 p->raop=raop_init(&c);if(!p->raop){stop();p->event("error","Could not initialize AirPlay");return false;}
 raop_set_log_level(p->raop,LOGGER_WARNING);
 raop_set_log_callback(p->raop,[](void*,int,const char *message){std::fprintf(stderr,"[AirPlay] %s\n",message);},p);
 if(raop_init2(p->raop,1,options.deviceId.c_str(),options.keyfile.c_str())){stop();p->event("error","Could not initialize pairing identity");return false;}
 raop_set_plist(p->raop,"width",options.width);raop_set_plist(p->raop,"height",options.height);
 raop_set_plist(p->raop,"refreshRate",60);raop_set_plist(p->raop,"maxFPS",options.fps);
 raop_set_plist(p->raop,"audio_delay_micros",100000);
 if(options.pin)raop_set_plist(p->raop,"pin",1);
 unsigned short tcp[2]={0,0},udp[3]={0,0,0};raop_set_tcp_ports(p->raop,tcp);raop_set_udp_ports(p->raop,udp);
 raop_set_dnssd(p->raop,p->dns);
 p->boundPort=0;
 if(raop_start_httpd(p->raop,&p->boundPort)<0||!p->boundPort){stop();p->event("error","Could not open receiver port");return false;}
 raop_set_port(p->raop,p->boundPort);
 if(dnssd_register_raop(p->dns,p->boundPort)||dnssd_register_airplay(p->dns,p->boundPort)){
  stop();p->event("error","Network discovery failed. Check multicast access.");return false;
 }
 struct in_addr lan{p->dns->ipv4_addr};char address[INET_ADDRSTRLEN];
 if(lan.s_addr&&inet_ntop(AF_INET,&lan,address,sizeof(address)))p->event("address",address);
 p->streaming=false;p->videoActive=false;std::string port=std::to_string(p->boundPort);p->event("ready",port.c_str());return true;
}
void CastReceiver::stop(){
 auto *p=impl.get();
 if(p->dns){dnssd_unregister_airplay(p->dns);dnssd_unregister_raop(p->dns);}
 if(p->raop){raop_destroy(p->raop);p->raop=nullptr;}
 if(p->dns){dnssd_destroy(p->dns);p->dns=nullptr;}
 std::lock_guard<std::mutex> guard(p->audioMutex);p->resetAudio();p->boundPort=0;p->streaming=false;p->videoActive=false;
}
