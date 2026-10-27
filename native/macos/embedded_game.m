#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>

// Prototype SPI used by Godot's macOS embedding backend.
@interface CALayerHost : CALayer
@property uint32_t contextId;
@end

/** Set the layer through JAWT while the component's drawing surface is locked. */
static BOOL set_layer(JNIEnv *env, jobject canvas, uint32_t context) {
    JAWT awt = { .version = JAWT_VERSION_1_7 };
    if (!JAWT_GetAWT(env, &awt)) return NO;
    JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, canvas);
    if (!surface) return NO;
    jint locked = surface->Lock(surface);
    BOOL success = NO;
    if (!(locked & JAWT_LOCK_ERROR)) {
        JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
        if (info && info->platformInfo) {
            id<JAWT_SurfaceLayers> layers = (__bridge id<JAWT_SurfaceLayers>)info->platformInfo;
            if (context) {
                CALayerHost *host = [NSClassFromString(@"CALayerHost") layer];
                if (host) {
                    [CATransaction begin];
                    [CATransaction setDisableActions:YES];
                    host.contextId = context;
                    CALayer *container = [CALayer layer];
                    // JAWT adds remote layers directly to the window, outside Swing's
                    // z-order. Keep this game layer below Compose's native Skia layer.
                    container.zPosition = -1;
                    container.frame = CGRectMake(0, 0, info->bounds.width, info->bounds.height);
                    container.masksToBounds = YES;
                    host.contentsScale = 2;
                    host.contentsGravity = kCAGravityCenter;
                    host.position = CGPointMake(0, container.bounds.size.height);
                    host.autoresizingMask = kCALayerMinYMargin;
                    host.actions = @{ @"position": NSNull.null, @"bounds": NSNull.null };
                    [container addSublayer:host];
                    layers.layer = container;
                    [CATransaction commit];
                    [CATransaction flush];
                    success = YES;
                }
            } else { layers.layer = nil; success = YES; }
        }
        if (info) surface->FreeDrawingSurfaceInfo(info);
        surface->Unlock(surface);
    }
    awt.FreeDrawingSurface(surface);
    return success;
}

/** Attach on the AWT event thread after the Canvas is displayable. */
JNIEXPORT jboolean JNICALL Java_org_formulagen_wrapper_progression_embedded_MacGameLayer_attach(JNIEnv *env, jobject self, jobject canvas, jlong context) {
    @autoreleasepool { return set_layer(env, canvas, (uint32_t)context); }
}

/** Clear the JAWT layer before tearing down the peer. */
JNIEXPORT void JNICALL Java_org_formulagen_wrapper_progression_embedded_MacGameLayer_detach(JNIEnv *env, jobject self, jobject canvas) {
    @autoreleasepool { set_layer(env, canvas, 0); }
}
