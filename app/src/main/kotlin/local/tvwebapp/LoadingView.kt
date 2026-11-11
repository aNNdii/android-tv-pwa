package local.tvwebapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar

/**
 * What the user sees while a page loads, above the WebView. First load: the app's banner with a spinner on
 * the app colour, until the page shows something. Later page changes: a thin bar at the top, only when
 * loading takes longer than [BAR_DELAY_MS]. Takes no keys or touches. [onSplashGone] runs once, when the
 * splash starts to fade.
 */
class LoadingView(context: Context, theme: Theme, private val onSplashGone: () -> Unit) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private var splash: View? = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(if (BuildConfig.COLOR < 0) theme.base else Color.BLACK or BuildConfig.COLOR)
        // Never enlarged (a generated banner is only 320x180 px); a large own banner shrinks to fit.
        addView(ImageView(context).apply {
            setImageResource(R.drawable.banner)
            adjustViewBounds = true
            maxWidth = px(400); maxHeight = px(225)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(theme.text) },
            LinearLayout.LayoutParams(px(48), px(48)).apply { topMargin = px(32) })
    }
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(theme.accent) // like the focus frame
        progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        visibility = GONE
    }
    private var barPending = false
    private val showBar = Runnable { barPending = false; if (splash == null) bar.visibility = VISIBLE }

    init {
        addView(splash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, px(4), Gravity.TOP))
    }

    /** onProgressChanged: below 100 a page loads (onPageStarted comes only once the server answered).
     *  The bar appears only if it is still loading after the delay. */
    fun progress(percent: Int) {
        bar.progress = percent
        if (percent >= 100) done()
        else if (!barPending && bar.visibility != VISIBLE) { barPending = true; postDelayed(showBar, BAR_DELAY_MS) }
    }

    /** The page shows something (onPageCommitVisible): the splash fades out, only once. */
    fun shown() {
        val s = splash ?: return
        splash = null
        onSplashGone()
        s.animate().alpha(0f).setDuration(200).withEndAction { removeView(s) }
    }

    /** onPageFinished, also for error pages. */
    fun done() {
        shown()
        removeCallbacks(showBar)
        barPending = false
        bar.visibility = GONE
    }

    private fun px(dpValue: Int) = (dpValue * dp).toInt()

    private companion object {
        const val BAR_DELAY_MS = 400L
    }
}
