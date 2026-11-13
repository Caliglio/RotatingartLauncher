package com.app.ralaunch.feature.game.secondary

import android.util.Log
import android.view.Surface

/**
 * Glue between the game's managed code (a mod, via libmain.so exports in secondary_display.cpp)
 * and the secondary-display window owned by [SecondaryDisplayPresenter].
 *
 * Direction of traffic:
 *  - mod -> native -> Kotlin : [requestFromNative] (turn the second screen on/off)
 *  - Kotlin -> native        : surface lifecycle + touch events
 *  - mod -> native           : frames are blitted straight into the Surface from C++ (no Java hop)
 */
object SecondaryDisplayBridge {
    private const val TAG = "RALSecondary"

    /** Set by the running game activity; called on the main thread. */
    @Volatile
    var controller: ((Boolean) -> Unit)? = null

    init {
        try {
            System.loadLibrary("main")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libmain not loaded yet: ${e.message}")
        }
    }

    /** Invoked from C++ (RAL_Secondary_SetEnabled). */
    @JvmStatic
    fun requestFromNative(enable: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            controller?.invoke(enable)
        }
    }

    @JvmStatic
    external fun nativeInit()

    @JvmStatic
    external fun nativeSurfaceChanged(surface: Surface, width: Int, height: Int)

    @JvmStatic
    external fun nativeSurfaceDestroyed()

    /** type: 0 = down, 1 = move, 2 = up. x/y are normalised 0..1 across the view. */
    @JvmStatic
    external fun nativeTouch(type: Int, id: Int, x: Float, y: Float)
}
