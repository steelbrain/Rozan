package ai.aneesiqbal.rozan.airplay.android.ui

import ai.aneesiqbal.rozan.airplay.DeviceNames
import ai.aneesiqbal.rozan.airplay.android.AirPlayRuntime
import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.airplay.android.R
import ai.aneesiqbal.rozan.airplay.android.session.SessionState
import ai.aneesiqbal.rozan.airplay.android.video.SurfaceBridge
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi

/**
 * Full-screen mirroring: one [SurfaceView] and nothing else, so the decoder's
 * frames go to a hardware overlay plane. Keeps the screen on, hides system
 * bars, letterboxes to the stream's aspect ratio. Back ends the session.
 */
internal class MirrorActivity :
    Activity(),
    SurfaceHolder.Callback,
    SessionState.Listener,
    SurfaceBridge.VideoSizeListener {
    private lateinit var runtime: AirPlayRuntime
    private lateinit var surfaceView: AspectSurfaceView
    private var backCallback: Any? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = AirPlayRuntime.get(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = AspectSurfaceView(this)
        surfaceView.id = R.id.airplay_mirror_surface
        surfaceView.holder.addCallback(this)
        setContentView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        hideSystemBars()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
        runtime.session.addListener(this)
        runtime.surfaces.setVideoSizeListener(this)
        onSessionChanged()
    }

    override fun onDestroy() {
        runtime.session.removeListener(this)
        runtime.surfaces.setVideoSizeListener(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDestroy()
    }

    override fun onSessionChanged() {
        val client = runtime.session.mirroringClient
        if (client == null) {
            if (!isFinishing) finish()
            return
        }
        val name = client.name?.let(DeviceNames::sanitize) ?: getString(R.string.airplay_unknown_client)
        surfaceView.contentDescription = getString(R.string.airplay_mirror_description, name)
    }

    override fun onVideoSizeChanged(
        width: Int,
        height: Int,
    ) {
        surfaceView.setAspect(width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        runtime.surfaces.surfaceAvailable(holder.surface)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runtime.surfaces.surfaceDestroyed()
    }

    // Back gestures and keys go to the OnBackInvokedCallback registered on API 33+. Before API 33
    // (and for apps not opted in to predictive back) they arrive here instead; AndroidX's
    // OnBackPressedDispatcher would need androidx.activity, which these plain Activities avoid.
    @SuppressLint("GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        endSession()
    }

    private fun endSession() {
        if (isFinishing) return
        runtime.sessionEnder.endCurrentSession()
        finish()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        val callback = OnBackInvokedCallback { endSession() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback() {
        val callback = backCallback as? OnBackInvokedCallback ?: return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        backCallback = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // The flag API is deprecated from API 30, which takes the branch above.
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}

/** A [SurfaceView] that measures itself to the largest box of the video's aspect ratio. */
internal class AspectSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SurfaceView(context, attrs) {
        private var aspectWidth = 0
        private var aspectHeight = 0

        fun setAspect(
            width: Int,
            height: Int,
        ) {
            if (width == aspectWidth && height == aspectHeight) return
            aspectWidth = width
            aspectHeight = height
            requestLayout()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
            val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
            val known = aspectWidth > 0 && aspectHeight > 0
            if (!known || maxWidth <= 0 || maxHeight <= 0) {
                setMeasuredDimension(maxWidth, maxHeight)
                return
            }
            // Fit inside: compare aspect ratios with cross-multiplication to stay in integers.
            val fitsWidth = maxWidth.toLong() * aspectHeight <= maxHeight.toLong() * aspectWidth
            if (fitsWidth) {
                setMeasuredDimension(maxWidth, (maxWidth.toLong() * aspectHeight / aspectWidth).toInt())
            } else {
                setMeasuredDimension((maxHeight.toLong() * aspectWidth / aspectHeight).toInt(), maxHeight)
            }
        }
    }
