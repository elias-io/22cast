#pragma once
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

struct CastCallbacks {
 std::function<void(const char*,const char*)> event;
 std::function<void(const uint8_t*,int,int64_t)> video;
 std::function<void(const int16_t*,int,int,int,int64_t)> pcm;
 std::function<void(int,int)> format;
};
struct CastOptions {
 std::string name, deviceId, keyfile;
 int width=1920, height=1080, fps=30;
 bool pin=false, audio=true;
};
class CastReceiver {
 struct Impl;
 std::unique_ptr<Impl> impl;
public:
 explicit CastReceiver(CastCallbacks callbacks);
 ~CastReceiver();
 bool start(const CastOptions &options);
 void stop();
 int port() const;
};
