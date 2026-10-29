#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioToolbox.h>
#import <CoreAudio/CoreAudio.h>
#import <Foundation/Foundation.h>
#import <string.h>
#import <unistd.h>

/* Bisect AVAudioEngine voice processing. Each step is a fresh engine.
 * A: enable VP, prepare, start, nothing else attached.
 * B: player connected mono at the output rate, mainMixer connected with the speaker format.
 * C: B plus a 1-channel input tap (never the 9-channel VP format on the output).
 * D: raw kAudioUnitSubType_VoiceProcessingIO, no AVAudioSession.
 */

#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 120000
#define EMBER_ELEMENT kAudioObjectPropertyElementMain
#else
#define EMBER_ELEMENT kAudioObjectPropertyElementMaster
#endif

static AudioDeviceID defaultDevice(AudioObjectPropertySelector selector) {
    AudioDeviceID device = kAudioObjectUnknown;
    AudioObjectPropertyAddress address = { selector, kAudioObjectPropertyScopeGlobal, EMBER_ELEMENT };
    UInt32 size = sizeof device;
    return AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, NULL, &size, &device) == noErr ? device : kAudioObjectUnknown;
}

static NSString *deviceName(AudioDeviceID device) {
    if (device == kAudioObjectUnknown) return @"(none)";
    CFStringRef name = NULL;
    AudioObjectPropertyAddress address = { kAudioDevicePropertyDeviceNameCFString, kAudioObjectPropertyScopeGlobal, EMBER_ELEMENT };
    UInt32 size = sizeof name;
    if (AudioObjectGetPropertyData(device, &address, 0, NULL, &size, &name) != noErr || !name) return @"(unknown)";
    NSString *result = [NSString stringWithString:(__bridge NSString *)name];
    CFRelease(name);
    return result;
}

static double deviceRate(AudioDeviceID device) {
    if (device == kAudioObjectUnknown) return 0;
    Float64 rate = 0;
    AudioObjectPropertyAddress address = { kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal, EMBER_ELEMENT };
    UInt32 size = sizeof rate;
    AudioObjectGetPropertyData(device, &address, 0, NULL, &size, &rate);
    return rate;
}

static void logDevices(void) {
    AudioDeviceID input = defaultDevice(kAudioHardwarePropertyDefaultInputDevice);
    AudioDeviceID output = defaultDevice(kAudioHardwarePropertyDefaultOutputDevice);
    AVAuthorizationStatus mic = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    NSLog(@"vp-probe mic=%ld input=\"%@\" %.0f Hz output=\"%@\" %.0f Hz",
        (long)mic, deviceName(input), deviceRate(input), deviceName(output), deviceRate(output));
}

static void stopEngine(AVAudioEngine *engine) {
    if (!engine) return;
    @try { [engine.inputNode removeTapOnBus:0]; } @catch (NSException *ex) { (void)ex; }
    [engine stop];
}

static void report(NSString *step, BOOL ok, NSError *error) {
    if (ok) NSLog(@"vp-probe step=%@ result=ok", step);
    else NSLog(@"vp-probe step=%@ result=fail %@", step, error);
}

static BOOL enableVoiceProcessing(AVAudioEngine *engine, NSError **error) {
    return [engine.inputNode setVoiceProcessingEnabled:YES error:error];
}

static void stepA(void) {
    NSError *error = nil;
    AVAudioEngine *engine = [AVAudioEngine new];
    if (!enableVoiceProcessing(engine, &error)) {
        report(@"A-vp-enable", NO, error);
        return;
    }
    AVAudioFormat *vp = [engine.inputNode outputFormatForBus:0];
    NSLog(@"vp-probe step=A vp-format=%@", vp);
    [engine prepare];
    BOOL ok = [engine startAndReturnError:&error];
    report(@"A-vp-only", ok, error);
    stopEngine(engine);
}

static AVAudioFormat *speakerFormat(AVAudioEngine *engine) {
    AVAudioFormat *speaker = [engine.outputNode outputFormatForBus:0];
    NSLog(@"vp-probe speaker-format=%@", speaker);
    if (speaker.channelCount == 0 || speaker.channelCount > 2 || speaker.sampleRate <= 0) {
        double rate = speaker.sampleRate > 0 ? speaker.sampleRate : 48000;
        return [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:2];
    }
    return speaker;
}

static BOOL attachPlayer(AVAudioEngine *engine, NSError **error) {
    AVAudioFormat *outputConnection = speakerFormat(engine);
    @try {
        [engine disconnectNodeOutput:engine.mainMixerNode];
        [engine connect:engine.mainMixerNode to:engine.outputNode format:outputConnection];
        AVAudioPlayerNode *player = [AVAudioPlayerNode new];
        [engine attachNode:player];
        double rate = outputConnection.sampleRate > 0 ? outputConnection.sampleRate : 48000;
        AVAudioFormat *mono = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:1];
        [engine connect:player to:engine.mainMixerNode format:mono];
        NSLog(@"vp-probe player-format=%@", mono);
        return YES;
    } @catch (NSException *ex) {
        if (error) *error = [NSError errorWithDomain:@"vp-probe" code:-1 userInfo:@{NSLocalizedDescriptionKey: ex.reason ?: ex.name ?: @"connect"}];
        return NO;
    }
}

static void stepB(void) {
    NSError *error = nil;
    AVAudioEngine *engine = [AVAudioEngine new];
    if (!enableVoiceProcessing(engine, &error)) {
        report(@"B-vp-enable", NO, error);
        return;
    }
    if (!attachPlayer(engine, &error)) {
        report(@"B-player-connect", NO, error);
        stopEngine(engine);
        return;
    }
    [engine prepare];
    BOOL ok = [engine startAndReturnError:&error];
    report(@"B-player", ok, error);
    stopEngine(engine);
}

static void stepC(void) {
    NSError *error = nil;
    AVAudioEngine *engine = [AVAudioEngine new];
    if (!enableVoiceProcessing(engine, &error)) {
        report(@"C-vp-enable", NO, error);
        return;
    }
    if (!attachPlayer(engine, &error)) {
        report(@"C-player-connect", NO, error);
        stopEngine(engine);
        return;
    }
    AVAudioFormat *mic = [engine.inputNode inputFormatForBus:0];
    double rate = mic.sampleRate > 0 ? mic.sampleRate : 48000;
    AVAudioFormat *tap = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:1];
    @try {
        [engine.inputNode installTapOnBus:0 bufferSize:1024 format:tap block:^(AVAudioPCMBuffer *buffer, AVAudioTime *when) {
            (void)buffer; (void)when;
        }];
        NSLog(@"vp-probe step=C tap-format=%@", tap);
    } @catch (NSException *ex) {
        report(@"C-tap", NO, [NSError errorWithDomain:@"vp-probe" code:-1 userInfo:@{NSLocalizedDescriptionKey: ex.reason ?: @"tap"}]);
        stopEngine(engine);
        return;
    }
    [engine prepare];
    BOOL ok = [engine startAndReturnError:&error];
    report(@"C-player-and-tap", ok, error);
    stopEngine(engine);
}

static OSStatus silenceRender(void *ref, AudioUnitRenderActionFlags *flags, const AudioTimeStamp *ts,
    UInt32 bus, UInt32 frames, AudioBufferList *data) {
    (void)ref; (void)flags; (void)ts; (void)bus;
    if (!data) return noErr;
    for (UInt32 b = 0; b < data->mNumberBuffers; b++) {
        if (data->mBuffers[b].mData) memset(data->mBuffers[b].mData, 0, data->mBuffers[b].mDataByteSize);
    }
    (void)frames;
    return noErr;
}

static void stepD(void) {
    AudioComponentDescription desc;
    memset(&desc, 0, sizeof desc);
    desc.componentType = kAudioUnitType_Output;
    desc.componentSubType = kAudioUnitSubType_VoiceProcessingIO;
    desc.componentManufacturer = kAudioUnitManufacturer_Apple;
    AudioComponent component = AudioComponentFindNext(NULL, &desc);
    if (!component) {
        NSLog(@"vp-probe step=D-audiounit result=fail VoiceProcessingIO not found");
        return;
    }
    AudioUnit unit = NULL;
    OSStatus status = AudioComponentInstanceNew(component, &unit);
    if (status != noErr || !unit) {
        NSLog(@"vp-probe step=D-audiounit result=fail AudioComponentInstanceNew %d", (int)status);
        return;
    }
    UInt32 enable = 1;
    AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input, 1, &enable, sizeof enable);
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
    if (status != noErr) NSLog(@"vp-probe step=D mic-format result=fail %d", (int)status);
    status = AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof format);
    if (status != noErr) NSLog(@"vp-probe step=D speaker-format result=fail %d", (int)status);
    AURenderCallbackStruct callback;
    callback.inputProc = silenceRender;
    callback.inputProcRefCon = NULL;
    AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &callback, sizeof callback);
    status = AudioUnitInitialize(unit);
    if (status != noErr) {
        NSLog(@"vp-probe step=D-audiounit result=fail AudioUnitInitialize %d", (int)status);
        AudioComponentInstanceDispose(unit);
        return;
    }
    status = AudioOutputUnitStart(unit);
    if (status != noErr) {
        NSLog(@"vp-probe step=D-audiounit result=fail AudioOutputUnitStart %d", (int)status);
    } else {
        NSLog(@"vp-probe step=D-audiounit result=ok rate=%.0f", rate);
        usleep(200000);
    }
    AudioOutputUnitStop(unit);
    AudioUnitUninitialize(unit);
    AudioComponentInstanceDispose(unit);
}

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        logDevices();
        stepA();
        stepB();
        stepC();
        stepD();
        NSLog(@"vp-probe done");
    }
    return 0;
}
