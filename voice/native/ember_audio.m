#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioToolbox.h>
#import <CoreAudio/CoreAudio.h>
#import <Foundation/Foundation.h>
#import <math.h>
#import <stdio.h>
#import <string.h>
#include "ember_audio.h"

/* One graph for the process. The mic tap only copies into the ring; the recognizer runs on the Kotlin thread. */
static const int kRing = 16000 * 4;
static const int kPlayRing = 48000 * 2;
static const int kScratch = 8192;
static char lastError[1024] = "";

#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 120000
#define EMBER_ELEMENT kAudioObjectPropertyElementMain
#else
#define EMBER_ELEMENT kAudioObjectPropertyElementMaster
#endif

static void remember(NSString *text) {
    NSLog(@"ember_audio %@", text);
    NSString *line = text ?: @"";
    snprintf(lastError, sizeof lastError, "%s", line.UTF8String ?: "");
}

static AudioDeviceID defaultDevice(AudioObjectPropertySelector selector) {
    AudioDeviceID device = kAudioObjectUnknown;
    AudioObjectPropertyAddress address = {
        selector,
        kAudioObjectPropertyScopeGlobal,
        EMBER_ELEMENT
    };
    UInt32 size = sizeof device;
    OSStatus status = AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, NULL, &size, &device);
    return status == noErr ? device : kAudioObjectUnknown;
}

static NSString *deviceName(AudioDeviceID device) {
    if (device == kAudioObjectUnknown) return @"(none)";
    CFStringRef name = NULL;
    AudioObjectPropertyAddress address = {
        kAudioDevicePropertyDeviceNameCFString,
        kAudioObjectPropertyScopeGlobal,
        EMBER_ELEMENT
    };
    UInt32 size = sizeof name;
    OSStatus status = AudioObjectGetPropertyData(device, &address, 0, NULL, &size, &name);
    if (status != noErr || name == NULL) return @"(unknown)";
    NSString *result = [NSString stringWithString:(__bridge NSString *)name];
    CFRelease(name);
    return result;
}

static double deviceRate(AudioDeviceID device) {
    if (device == kAudioObjectUnknown) return 0;
    Float64 rate = 0;
    AudioObjectPropertyAddress address = {
        kAudioDevicePropertyNominalSampleRate,
        kAudioObjectPropertyScopeGlobal,
        EMBER_ELEMENT
    };
    UInt32 size = sizeof rate;
    AudioObjectGetPropertyData(device, &address, 0, NULL, &size, &rate);
    return rate;
}

static NSString *deviceSummary(void) {
    AudioDeviceID input = defaultDevice(kAudioHardwarePropertyDefaultInputDevice);
    AudioDeviceID output = defaultDevice(kAudioHardwarePropertyDefaultOutputDevice);
    return [NSString stringWithFormat:@"input=\"%@\" %.0f Hz output=\"%@\" %.0f Hz",
        deviceName(input), deviceRate(input), deviceName(output), deviceRate(output)];
}

static NSString *micStatusName(AVAuthorizationStatus status) {
    switch (status) {
        case AVAuthorizationStatusAuthorized: return @"authorized";
        case AVAuthorizationStatusDenied: return @"denied";
        case AVAuthorizationStatusRestricted: return @"restricted";
        case AVAuthorizationStatusNotDetermined: return @"undetermined";
        default: return @"unknown";
    }
}

/* Prompt when the app has not been asked yet. A wait on the main thread pumps the run loop so the dialog can finish. */
static AVAuthorizationStatus ensureMicrophone(void) {
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    if (status != AVAuthorizationStatusNotDetermined) return status;
    [NSApplication sharedApplication];
    __block BOOL done = NO;
    dispatch_semaphore_t gate = dispatch_semaphore_create(0);
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio completionHandler:^(BOOL granted) {
        (void)granted;
        done = YES;
        dispatch_semaphore_signal(gate);
    }];
    if ([NSThread isMainThread]) {
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:60];
        while (!done && [deadline timeIntervalSinceNow] > 0) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        }
    } else {
        dispatch_semaphore_wait(gate, dispatch_time(DISPATCH_TIME_NOW, 60 * NSEC_PER_SEC));
    }
    return [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
}

static NSString *osStatusString(OSStatus status) {
    uint32_t swapped = CFSwapInt32HostToBig((uint32_t)status);
    char text[5] = {0};
    memcpy(text, &swapped, 4);
    BOOL printable = YES;
    for (int i = 0; i < 4; i++) if (text[i] < 32 || text[i] > 126) printable = NO;
    if (printable) return [NSString stringWithFormat:@"%d '%s'", (int)status, text];
    return [NSString stringWithFormat:@"%d", (int)status];
}

@interface EmberGraph : NSObject
@property(nonatomic, strong) AVAudioEngine *engine;
@property(nonatomic, strong) AVAudioPlayerNode *player;
@property(nonatomic, assign) BOOL voiceProcessing;
@property(nonatomic, assign) BOOL useUnit;
@property(nonatomic, assign) double playRate;
- (BOOL)startEngine:(NSError **)error;
- (BOOL)startUnit:(NSError **)error;
- (OSStatus)renderFlags:(AudioUnitRenderActionFlags *)flags
    timestamp:(const AudioTimeStamp *)timestamp
    frames:(UInt32)frames
    data:(AudioBufferList *)data;
- (int)read:(float *)dst capacity:(int)capacity;
- (void)play:(const int16_t *)pcm samples:(int)samples rate:(int)rate;
- (void)flush;
- (void)stopGraph;
@end

static OSStatus emberRender(void *ref, AudioUnitRenderActionFlags *flags, const AudioTimeStamp *timestamp,
    UInt32 bus, UInt32 frames, AudioBufferList *data) {
    (void)bus;
    return [(__bridge EmberGraph *)ref renderFlags:flags timestamp:timestamp frames:frames data:data];
}

@implementation EmberGraph {
    float *_ring;
    int _write;
    int _count;
    float *_playRing;
    int _playWrite;
    int _playCount;
    float *_micScratch;
    AudioUnit _unit;
    NSLock *_lock;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _ring = calloc(kRing, sizeof(float));
        _playRing = calloc(kPlayRing, sizeof(float));
        _micScratch = calloc(kScratch, sizeof(float));
        _lock = [NSLock new];
    }
    return self;
}

- (void)dealloc {
    /* Stop the unit and the engine before the rings disappear. A tap must not outlive them. */
    [self stopGraph];
    free(_ring);
    free(_playRing);
    free(_micScratch);
}

- (void)push:(const float *)samples count:(int)count {
    [_lock lock];
    for (int i = 0; i < count; i++) {
        _ring[_write] = samples[i];
        _write = (_write + 1) % kRing;
        if (_count < kRing) _count++;
    }
    [_lock unlock];
}

- (void)acceptBuffer:(AVAudioPCMBuffer *)buffer {
    AVAudioFrameCount frames = buffer.frameLength;
    if (frames == 0 || buffer.floatChannelData == NULL || buffer.floatChannelData[0] == NULL) return;
    float *channel = buffer.floatChannelData[0];
    double inRate = buffer.format.sampleRate > 0 ? buffer.format.sampleRate : self.playRate;
    double step = inRate / 16000.0;
    if (step <= 0) return;
    int outCount = (int)(frames / step);
    if (outCount <= 0) return;
    if (outCount > 2048) outCount = 2048;
    float stack[2048];
    for (int i = 0; i < outCount; i++) {
        int left = (int)(i * step);
        if (left < 0) left = 0;
        if (left >= (int)frames) left = (int)frames - 1;
        stack[i] = channel[left];
    }
    [self push:stack count:outCount];
}

- (BOOL)installTapOn:(AVAudioInputNode *)input format:(AVAudioFormat *)format label:(NSString *)label {
    __weak EmberGraph *weakSelf = self;
    @try {
        [input installTapOnBus:0 bufferSize:1024 format:format block:^(AVAudioPCMBuffer *buffer, AVAudioTime *when) {
            (void)when;
            [weakSelf acceptBuffer:buffer];
        }];
        remember([NSString stringWithFormat:@"tap %@ format=%@", label, format ?: @"nil"]);
        return YES;
    } @catch (NSException *ex) {
        remember([NSString stringWithFormat:@"tap %@ failed: %@", label, ex]);
        return NO;
    }
}

/* Voice processing is enabled before outputNode is touched. The 9-channel VP format stays off the output connection. */
- (BOOL)buildEngineWithMonoTap:(BOOL)monoTap error:(NSError **)error {
    self.engine = [AVAudioEngine new];
    AVAudioInputNode *input = self.engine.inputNode;
    NSError *local = nil;
    if (![input setVoiceProcessingEnabled:YES error:&local]) {
        self.voiceProcessing = NO;
        if (error) *error = local;
        return NO;
    }
    self.voiceProcessing = YES;

    AVAudioFormat *vpFormat = [input outputFormatForBus:0];
    AVAudioFormat *micFormat = [input inputFormatForBus:0];
    AVAudioOutputNode *output = self.engine.outputNode;
    AVAudioFormat *speaker = [output outputFormatForBus:0];
    remember([NSString stringWithFormat:@"formats vp=%@ mic=%@ speaker=%@", vpFormat, micFormat, speaker]);

    double rate = speaker.sampleRate > 0 ? speaker.sampleRate : 48000;
    AVAudioFormat *outputConnection = speaker;
    if (speaker.channelCount == 0 || speaker.channelCount > 2 || speaker.sampleRate <= 0) {
        outputConnection = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:2];
    }
    self.playRate = outputConnection.sampleRate > 0 ? outputConnection.sampleRate : rate;
    AVAudioFormat *playback = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:self.playRate channels:1];
    self.player = [AVAudioPlayerNode new];
    [self.engine attachNode:self.player];
    @try {
        [self.engine disconnectNodeOutput:self.engine.mainMixerNode];
        /* Speaker format only. The 9-channel voice-processing format is not used here. */
        [self.engine connect:self.engine.mainMixerNode to:output format:outputConnection];
        [self.engine connect:self.player to:self.engine.mainMixerNode format:playback];
    } @catch (NSException *ex) {
        if (error) *error = [NSError errorWithDomain:@"ember_audio" code:-1 userInfo:@{NSLocalizedDescriptionKey: ex.reason ?: ex.name ?: @"connect"}];
        return NO;
    }

    double tapRate = micFormat.sampleRate > 0 ? micFormat.sampleRate : self.playRate;
    AVAudioFormat *oneChannel = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:tapRate channels:1];
    BOOL tapped = NO;
    if (monoTap) tapped = [self installTapOn:input format:oneChannel label:@"1ch"];
    if (!tapped) tapped = [self installTapOn:input format:nil label:@"nil"];
    if (!tapped) {
        if (error) *error = [NSError errorWithDomain:@"ember_audio" code:-1 userInfo:@{NSLocalizedDescriptionKey: @"input tap failed"}];
        return NO;
    }

    [self.engine prepare];
    if (![self.engine startAndReturnError:&local]) {
        if (error) *error = local;
        return NO;
    }
    [self.player play];
    self.useUnit = NO;
    return YES;
}

- (BOOL)startEngine:(NSError **)error {
    NSError *first = nil;
    if ([self buildEngineWithMonoTap:YES error:&first]) return YES;
    remember([NSString stringWithFormat:@"engine 1ch-tap failed: %@", first]);
    [self stopGraph];
    NSError *second = nil;
    if ([self buildEngineWithMonoTap:NO error:&second]) return YES;
    if (error) *error = second ?: first;
    return NO;
}

- (void)pushPlayback:(const float *)samples count:(int)count {
    if (!_playRing || count <= 0) return;
    [_lock lock];
    for (int i = 0; i < count; i++) {
        _playRing[_playWrite] = samples[i];
        _playWrite = (_playWrite + 1) % kPlayRing;
        if (_playCount < kPlayRing) _playCount++;
        else _playWrite = _playWrite; /* drop oldest by advancing the read side below */
    }
    if (_playCount > kPlayRing) _playCount = kPlayRing;
    [_lock unlock];
}

- (void)fillPlayback:(float *)dst frames:(UInt32)frames {
    [_lock lock];
    for (UInt32 i = 0; i < frames; i++) {
        if (_playCount > 0) {
            int start = (_playWrite - _playCount + kPlayRing) % kPlayRing;
            dst[i] = _playRing[start];
            _playCount--;
        } else {
            dst[i] = 0;
        }
    }
    [_lock unlock];
}

- (OSStatus)renderFlags:(AudioUnitRenderActionFlags *)flags
    timestamp:(const AudioTimeStamp *)timestamp
    frames:(UInt32)frames
    data:(AudioBufferList *)data {
    if (_unit && frames > 0 && frames <= kScratch && _micScratch) {
        AudioBufferList mic;
        mic.mNumberBuffers = 1;
        mic.mBuffers[0].mNumberChannels = 1;
        mic.mBuffers[0].mDataByteSize = frames * (UInt32)sizeof(float);
        mic.mBuffers[0].mData = _micScratch;
        OSStatus rendered = AudioUnitRender(_unit, flags, timestamp, 1, frames, &mic);
        if (rendered == noErr) {
            double step = self.playRate / 16000.0;
            if (step > 0) {
                int outCount = (int)(frames / step);
                if (outCount > 2048) outCount = 2048;
                float stack[2048];
                for (int i = 0; i < outCount; i++) {
                    int left = (int)(i * step);
                    if (left >= (int)frames) left = (int)frames - 1;
                    if (left < 0) left = 0;
                    stack[i] = _micScratch[left];
                }
                [self push:stack count:outCount];
            }
        }
    }
    float play[kScratch];
    UInt32 n = frames <= kScratch ? frames : kScratch;
    [self fillPlayback:play frames:n];
    if (data) {
        for (UInt32 b = 0; b < data->mNumberBuffers; b++) {
            float *dst = data->mBuffers[b].mData;
            if (!dst) continue;
            UInt32 channels = data->mBuffers[b].mNumberChannels > 0 ? data->mBuffers[b].mNumberChannels : 1;
            for (UInt32 i = 0; i < n; i++) {
                for (UInt32 c = 0; c < channels; c++) dst[i * channels + c] = play[i];
            }
        }
    }
    return noErr;
}

- (BOOL)startUnit:(NSError **)error {
    AudioComponentDescription desc;
    memset(&desc, 0, sizeof desc);
    desc.componentType = kAudioUnitType_Output;
    desc.componentSubType = kAudioUnitSubType_VoiceProcessingIO;
    desc.componentManufacturer = kAudioUnitManufacturer_Apple;
    AudioComponent component = AudioComponentFindNext(NULL, &desc);
    if (!component) {
        if (error) *error = [NSError errorWithDomain:@"ember_audio" code:-1 userInfo:@{NSLocalizedDescriptionKey: @"VoiceProcessingIO not found"}];
        return NO;
    }
    AudioUnit unit = NULL;
    OSStatus status = AudioComponentInstanceNew(component, &unit);
    if (status != noErr || !unit) {
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:nil];
        return NO;
    }
    UInt32 enable = 1;
    status = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input, 1, &enable, sizeof enable);
    if (status != noErr) remember([NSString stringWithFormat:@"EnableIO %@", osStatusString(status)]);
    UInt32 bypass = 0;
    AudioUnitSetProperty(unit, kAUVoiceIOProperty_BypassVoiceProcessing, kAudioUnitScope_Global, 0, &bypass, sizeof bypass);

    double rate = deviceRate(defaultDevice(kAudioHardwarePropertyDefaultOutputDevice));
    if (rate < 8000) rate = 48000;
    AudioStreamBasicDescription format;
    memset(&format, 0, sizeof format);
    format.mSampleRate = rate;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    format.mFramesPerPacket = 1;
    format.mChannelsPerFrame = 1;
    format.mBitsPerChannel = 32;
    format.mBytesPerFrame = 4;
    format.mBytesPerPacket = 4;
    status = AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output, 1, &format, sizeof format);
    if (status != noErr) {
        AudioComponentInstanceDispose(unit);
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:@{NSLocalizedDescriptionKey: @"mic format"}];
        return NO;
    }
    status = AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof format);
    if (status != noErr) {
        AudioComponentInstanceDispose(unit);
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:@{NSLocalizedDescriptionKey: @"speaker format"}];
        return NO;
    }
    _unit = unit;
    self.playRate = rate;
    self.useUnit = YES;
    AURenderCallbackStruct callback;
    callback.inputProc = emberRender;
    callback.inputProcRefCon = (__bridge void *)self;
    status = AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &callback, sizeof callback);
    if (status != noErr) {
        [self stopGraph];
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:@{NSLocalizedDescriptionKey: @"render callback"}];
        return NO;
    }
    status = AudioUnitInitialize(unit);
    if (status != noErr) {
        [self stopGraph];
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"AudioUnitInitialize %@", osStatusString(status)]}];
        return NO;
    }
    status = AudioOutputUnitStart(unit);
    if (status != noErr) {
        [self stopGraph];
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"AudioOutputUnitStart %@", osStatusString(status)]}];
        return NO;
    }
    self.voiceProcessing = YES;
    return YES;
}

- (int)read:(float *)dst capacity:(int)capacity {
    if (capacity <= 0) return 0;
    [_lock lock];
    int n = _count < capacity ? _count : capacity;
    int start = (_write - _count + kRing) % kRing;
    for (int i = 0; i < n; i++) dst[i] = _ring[(start + i) % kRing];
    _count -= n;
    [_lock unlock];
    return n;
}

- (void)play:(const int16_t *)pcm samples:(int)samples rate:(int)rate {
    if (samples <= 0 || self.playRate <= 0) return;
    double dstRate = self.playRate;
    double srcRate = rate > 0 ? rate : dstRate;
    int outCount = (int)(samples * (dstRate / srcRate));
    if (outCount <= 0) outCount = samples;
    float *resampled = calloc((size_t)outCount, sizeof(float));
    if (!resampled) return;
    double step = srcRate / dstRate;
    for (int i = 0; i < outCount; i++) {
        double at = i * step;
        int left = (int)at;
        if (left < 0) left = 0;
        if (left >= samples) left = samples - 1;
        int right = left + 1 < samples ? left + 1 : left;
        float frac = (float)(at - left);
        resampled[i] = (pcm[left] / 32768.f) * (1.f - frac) + (pcm[right] / 32768.f) * frac;
    }
    if (self.useUnit) {
        [self pushPlayback:resampled count:outCount];
        free(resampled);
        return;
    }
    if (!self.player) {
        free(resampled);
        return;
    }
    AVAudioFormat *format = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:dstRate channels:1];
    AVAudioPCMBuffer *buffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:(AVAudioFrameCount)outCount];
    if (buffer && buffer.floatChannelData && buffer.floatChannelData[0]) {
        buffer.frameLength = (AVAudioFrameCount)outCount;
        memcpy(buffer.floatChannelData[0], resampled, (size_t)outCount * sizeof(float));
        [self.player scheduleBuffer:buffer completionHandler:nil];
        if (!self.player.isPlaying) [self.player play];
    }
    free(resampled);
}

- (void)flush {
    if (self.useUnit) {
        [_lock lock];
        _playCount = 0;
        _playWrite = 0;
        [_lock unlock];
        return;
    }
    [self.player stop];
    [self.player play];
}

- (void)stopGraph {
    if (_unit) {
        AudioOutputUnitStop(_unit);
        AudioUnitUninitialize(_unit);
        AudioComponentInstanceDispose(_unit);
        _unit = NULL;
    }
    self.useUnit = NO;
    if (self.engine) {
        @try { [self.engine.inputNode removeTapOnBus:0]; } @catch (NSException *ex) { (void)ex; }
        [self.engine stop];
        self.engine = nil;
        self.player = nil;
    }
    [_lock lock];
    _count = 0;
    _write = 0;
    _playCount = 0;
    _playWrite = 0;
    [_lock unlock];
}
@end

static EmberGraph *graph;

int ember_audio_start(int sample_rate) {
    (void)sample_rate;
    @autoreleasepool {
        AVAuthorizationStatus mic = ensureMicrophone();
        NSString *devices = deviceSummary();
        remember([NSString stringWithFormat:@"mic=%@ %@", micStatusName(mic), devices]);
        if (mic == AVAuthorizationStatusDenied || mic == AVAuthorizationStatusRestricted) {
            remember([NSString stringWithFormat:@"mic-denied %@ %@", micStatusName(mic), devices]);
            return EMBER_AUDIO_MIC_DENIED;
        }
        if (graph) [graph stopGraph];
        graph = [EmberGraph new];
        NSError *engineError = nil;
        if ([graph startEngine:&engineError] && graph.voiceProcessing) {
            remember([NSString stringWithFormat:@"backend=avaudioengine %@", devices]);
            return EMBER_AUDIO_OK;
        }
        remember([NSString stringWithFormat:@"avaudioengine failed: %@ %@", engineError, devices]);
        [graph stopGraph];
        NSError *unitError = nil;
        if ([graph startUnit:&unitError] && graph.voiceProcessing) {
            remember([NSString stringWithFormat:@"backend=voiceprocessingio %@", devices]);
            return EMBER_AUDIO_OK;
        }
        remember([NSString stringWithFormat:@"voice-processing-off engine=%@ unit=%@ %@", engineError, unitError, devices]);
        [graph stopGraph];
        graph = nil;
        return EMBER_AUDIO_VOICE_PROCESSING;
    }
}

int ember_audio_read(float *dst, int capacity) {
    if (!graph) return -1;
    return [graph read:dst capacity:capacity];
}

int ember_audio_play(const int16_t *pcm, int samples, int sample_rate) {
    if (!graph) return -1;
    [graph play:pcm samples:samples rate:sample_rate];
    return 0;
}

void ember_audio_flush(void) {
    if (graph) [graph flush];
}

void ember_audio_stop(void) {
    if (!graph) return;
    [graph stopGraph];
    graph = nil;
}

int ember_audio_voice_processing(void) {
    return graph && graph.voiceProcessing ? 1 : 0;
}

const char *ember_audio_last_error(void) {
    return lastError;
}
