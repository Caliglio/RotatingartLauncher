package com.app.ralaunch.feature.game.secondary

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Shows a [Presentation] on a secondary physical display (e.g. the AYN Thor's bottom screen)
 * containing one SurfaceView. The game (mod) draws into that surface from native code and
 * receives touches through [SecondaryDisplayBridge].
 *
 * Nothing is shown until the game asks for it, so other games keep a normal second screen.
 */
class SecondaryDisplayPresenter(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var presentation: SecondaryPresentation? = null
    private var wanted = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (wanted) show()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) dismiss()
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    /** Call on the main thread. */
    fun setEnabled(enable: Boolean) {
        if (enable) {
            if (!wanted) {
                wanted = true
                val dm = displayManager
                    ?: (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                displayManager = dm
                dm.registerDisplayListener(listener, handler)
            }
            show()
        } else if (wanted) {
            wanted = false
            displayManager?.unregisterDisplayListener(listener)
            dismiss()
        }
    }

    fun onResume() {
        if (wanted && presentation == null) show()
    }

    fun release() = setEnabled(false)

    private fun show() {
        if (presentation != null) return
        val dm = displayManager ?: return
        val current = activity.window.decorView.display?.displayId ?: Display.DEFAULT_DISPLAY

        val target = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != current }
            ?: dm.displays.firstOrNull { it.displayId != current && it.state != Display.STATE_OFF }

        if (target == null) {
            Log.i(TAG, "No secondary display found (current=$current)")
            return
        }
        try {
            val p = SecondaryPresentation(activity, target)
            p.show()
            presentation = p
            Log.i(TAG, "Presentation shown on display ${target.displayId} (${target.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Could not show Presentation on display ${target.displayId}", e)
            presentation = null
        }
    }

    private fun dismiss() {
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "dismiss failed", e)
        }
        presentation = null
        try {
            SecondaryDisplayBridge.nativeSurfaceDestroyed()
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    private class SecondaryPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.apply {
                // NOT_FOCUSABLE: touching this screen must not steal window focus from the game
                // (that would make SDL think the game lost focus and pause it).
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }

            val surfaceView = SurfaceView(context)
            setContentView(surfaceView)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    // Render at a fraction of native resolution: cheaper to read back and copy,
                    // and the compositor scales it up (crisp pixel-art look).
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(m)
                    holder.setFixedSize(
                        maxOf(MIN_W, m.widthPixels / DIVISOR),
                        maxOf(MIN_H, m.heightPixels / DIVISOR)
                    )
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    try {
                        SecondaryDisplayBridge.nativeSurfaceChanged(holder.surface, width, height)
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "native bridge missing", e)
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    try {
                        SecondaryDisplayBridge.nativeSurfaceDestroyed()
                    } catch (_: UnsatisfiedLinkError) {
                    }
                }
            })

            surfaceView.setOnTouchListener { v, e -> forwardTouch(v, e); true }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun forwardTouch(v: View, e: MotionEvent) {
            val w = maxOf(1, v.width).toFloat()
            val h = maxOf(1, v.height).toFloat()
            try {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val i = e.actionIndex
                        SecondaryDisplayBridge.nativeTouch(0, e.getPointerId(i), e.getX(i) / w, e.getY(i) / h)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        for (i in 0 until e.pointerCount)
                            SecondaryDisplayBridge.nativeTouch(1, e.getPointerId(i), e.getX(i) / w, e.getY(i) / h)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        val i = e.actionIndex
                        SecondaryDisplayBridge.nativeTouch(2, e.getPointerId(i), e.getX(i) / w, e.getY(i) / h)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        for (i in 0 until e.pointerCount)
                            SecondaryDisplayBridge.nativeTouch(2, e.getPointerId(i), e.getX(i) / w, e.getY(i) / h)
                    }
                }
            } catch (_: UnsatisfiedLinkError) {
            }
        }
    }

    companion object {
        private const val TAG = "RALSecondary"
        private const val DIVISOR = 2
        private const val MIN_W = 320
        private const val MIN_H = 240
    }
}
