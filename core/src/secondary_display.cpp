// Second-screen support (e.g. AYN Thor bottom display).
//
// Kotlin (SecondaryDisplayPresenter) owns a Presentation + SurfaceView on the other display and feeds
// us its Surface and touch events through the JNI functions below. The game's managed code (a mod)
// calls the plain C exports (RAL_Secondary_*) via dlsym/NativeLibrary to push RGBA frames and to read
// touches. Frames are copied straight into the Surface with ANativeWindow (no EGL, no Java hop).

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>

#include "jni_entry.hpp"
#include "logger.hpp"

#define RAL_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

struct TouchEvent {
    int type;   // 0 down, 1 move, 2 up
    int id;
    float x;    // 0..1
    float y;    // 0..1
};

std::mutex g_window_mutex;
ANativeWindow *g_window = nullptr;
int g_width = 0;
int g_height = 0;

std::mutex g_touch_mutex;
std::deque<TouchEvent> g_touches;
constexpr size_t kMaxQueuedTouches = 512;

jclass g_bridge_class = nullptr;          // global ref
jmethodID g_request_method = nullptr;     // static void requestFromNative(boolean)

}  // namespace

// ------------------------------------------------------------------ JNI (called from Kotlin)

extern "C" JNIEXPORT void JNICALL
Java_com_app_ralaunch_feature_game_secondary_SecondaryDisplayBridge_nativeInit(
        JNIEnv *env, jclass clazz) {
    if (g_bridge_class == nullptr) {
        g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    }
    g_request_method = env->GetStaticMethodID(clazz, "requestFromNative", "(Z)V");
    if (g_request_method == nullptr) {
        env->ExceptionClear();
        LOGE("SecondaryDisplay: requestFromNative not found");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_ralaunch_feature_game_secondary_SecondaryDisplayBridge_nativeSurfaceChanged(
        JNIEnv *env, jclass clazz, jobject surface, jint width, jint height) {
    (void) clazz;
    ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;

    std::lock_guard<std::mutex> lock(g_window_mutex);
    if (g_window != nullptr) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    g_window = window;
    if (g_window != nullptr) {
        // Keep the buffer size chosen by Kotlin (SurfaceHolder.setFixedSize); just force RGBA8888.
        ANativeWindow_setBuffersGeometry(g_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
        g_width = ANativeWindow_getWidth(g_window);
        g_height = ANativeWindow_getHeight(g_window);
        if (g_width <= 0) g_width = width;
        if (g_height <= 0) g_height = height;
        LOGI("SecondaryDisplay: surface ready {}x{}", g_width, g_height);
    } else {
        g_width = g_height = 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_ralaunch_feature_game_secondary_SecondaryDisplayBridge_nativeSurfaceDestroyed(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    std::lock_guard<std::mutex> lock(g_window_mutex);
    if (g_window != nullptr) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    g_width = g_height = 0;
    LOGI("SecondaryDisplay: surface destroyed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_ralaunch_feature_game_secondary_SecondaryDisplayBridge_nativeTouch(
        JNIEnv *env, jclass clazz, jint type, jint id, jfloat x, jfloat y) {
    (void) env;
    (void) clazz;
    std::lock_guard<std::mutex> lock(g_touch_mutex);
    if (g_touches.size() >= kMaxQueuedTouches) {
        // Drop oldest *move* events first so down/up are never lost.
        for (auto it = g_touches.begin(); it != g_touches.end(); ++it) {
            if (it->type == 1) {
                g_touches.erase(it);
                break;
            }
        }
        if (g_touches.size() >= kMaxQueuedTouches) g_touches.pop_front();
    }
    g_touches.push_back({type, id, x, y});
}

// ------------------------------------------------------------------ C exports (called from the mod)

// Lets the mod check that it is running on a launcher that has this feature.
RAL_EXPORT int RAL_Secondary_Version(void) {
    return 1;
}

// Ask the launcher to show (1) or hide (0) the second-screen window.
RAL_EXPORT void RAL_Secondary_SetEnabled(int enable) {
    if (g_bridge_class == nullptr || g_request_method == nullptr) {
        LOGW("SecondaryDisplay: bridge not initialised, ignoring SetEnabled");
        return;
    }
    JNIEnv *env = JniEntry_GetEnv();
    if (env == nullptr) return;
    env->CallStaticVoidMethod(g_bridge_class, g_request_method, static_cast<jboolean>(enable ? 1 : 0));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("SecondaryDisplay: exception in requestFromNative");
    }
}

// Writes the surface size and returns 1 when a surface is available, else 0.
RAL_EXPORT int RAL_Secondary_GetSize(int *width, int *height) {
    std::lock_guard<std::mutex> lock(g_window_mutex);
    if (g_window == nullptr || g_width <= 0 || g_height <= 0) return 0;
    if (width) *width = g_width;
    if (height) *height = g_height;
    return 1;
}

// Copies a tightly-packed RGBA8888 frame (w*h*4 bytes, top row first) to the surface.
// Returns 0 on success, negative on failure.
RAL_EXPORT int RAL_Secondary_Present(const uint8_t *rgba, int w, int h) {
    if (rgba == nullptr || w <= 0 || h <= 0) return -1;

    std::lock_guard<std::mutex> lock(g_window_mutex);
    if (g_window == nullptr) return -2;

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_window, &buffer, nullptr) != 0) return -3;

    int rc = 0;
    if (buffer.format == WINDOW_FORMAT_RGBA_8888 || buffer.format == WINDOW_FORMAT_RGBX_8888) {
        const int copyW = std::min(w, static_cast<int>(buffer.width));
        const int copyH = std::min(h, static_cast<int>(buffer.height));
        auto *dst = static_cast<uint8_t *>(buffer.bits);
        const size_t dstStride = static_cast<size_t>(buffer.stride) * 4;
        const size_t srcStride = static_cast<size_t>(w) * 4;
        for (int y = 0; y < copyH; ++y) {
            std::memcpy(dst + y * dstStride, rgba + y * srcStride, static_cast<size_t>(copyW) * 4);
        }
    } else {
        rc = -4;  // unexpected pixel format
    }
    ANativeWindow_unlockAndPost(g_window);
    return rc;
}

// Pops one queued touch event. Returns 1 if an event was written, 0 if the queue is empty.
RAL_EXPORT int RAL_Secondary_PollTouch(int *type, int *id, float *x, float *y) {
    std::lock_guard<std::mutex> lock(g_touch_mutex);
    if (g_touches.empty()) return 0;
    const TouchEvent ev = g_touches.front();
    g_touches.pop_front();
    if (type) *type = ev.type;
    if (id) *id = ev.id;
    if (x) *x = ev.x;
    if (y) *y = ev.y;
    return 1;
}
