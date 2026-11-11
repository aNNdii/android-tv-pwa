package local.tvwebapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Remote-control input with two modes, switched by holding OK for [LONG_PRESS_MS]:
 * - pointer mode: arrow keys move a drawn pointer (accelerating while held), OK taps at its position,
 *   pushing against an edge scrolls with mouse-wheel events at the pointer;
 * - focus mode: arrow keys go to the page (WebView spatial navigation), OK goes to [onFocusOk] (its release).
 * Events go to whatever view [target] returns (WebView, or the video fullscreen view).
 * [onModeChange] runs after a switch; the pointer position is in this view's pixels, which equal the
 * WebView's (both fill the same parent).
 */
class CursorView(
    context: Context,
    startInPointerMode: Boolean,
    theme: Theme,
    private val target: () -> View,
    private val onModeChange: (pointerMode: Boolean) -> Unit,
    private val onFocusOk: (up: KeyEvent) -> Unit,
) : View(context) {
    var pointerMode = startInPointerMode
        private set
    private var x = -1f
    private var y = -1f
    private var shown = startInPointerMode
    private var tapDownTime = 0L
    private var okDown = false
    private var okLong = false
    private val hide = Runnable { shown = false; invalidate() }
    private val longOk = Runnable {
        okLong = true
        pointerMode = !pointerMode
        if (pointerMode) showNow() else hideNow()
        onModeChange(pointerMode)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.outline; style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 34f); lineTo(9f, 26f); lineTo(15f, 40f); lineTo(21f, 37f)
        lineTo(15f, 24f); lineTo(26f, 24f); close()
    }

    init {
        isFocusable = false
        isClickable = false
    }

    val pointerX get() = x
    val pointerY get() = y

    /** Moves the pointer, e.g. onto the element that had the focus. */
    fun placeAt(px: Float, py: Float) {
        x = px.coerceIn(0f, width - 1f); y = py.coerceIn(0f, height - 1f)
        mouse(MotionEvent.ACTION_HOVER_MOVE, 0f, 0f)
        if (pointerMode) showNow()
    }

    /** Taps like a finger at a screen point, e.g. into an embedded player that starts only on a tap. */
    fun tapAt(px: Float, py: Float) {
        placeAt(px, py)
        tap()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (x < 0) { x = w / 2f; y = h / 2f }
        x = x.coerceIn(0f, w - 1f); y = y.coerceIn(0f, h - 1f)
        hideLater()
    }

    override fun onDraw(canvas: Canvas) {
        if (!pointerMode || !shown) return
        canvas.save()
        canvas.translate(x, y)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }

    /** Returns true when the key was used here; false hands it on to the page. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode in OK_KEYS) { onOk(e); return true }
        if (!pointerMode) return false
        val dx = when (e.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (e.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        if (dx == 0 && dy == 0) return false
        if (e.action == KeyEvent.ACTION_DOWN) move(dx, dy, e.repeatCount)
        return true
    }

    /**
     * OK acts on release, because only then is it known whether it was a long press (mode switch).
     * Key repeats while held are swallowed.
     */
    private fun onOk(e: KeyEvent) {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            okDown = true
            okLong = false
            postDelayed(longOk, LONG_PRESS_MS)
        } else if (e.action == KeyEvent.ACTION_UP && okDown) {
            okDown = false
            removeCallbacks(longOk)
            if (!okLong && !e.isCanceled) { if (pointerMode) tap() else onFocusOk(e) }
        }
    }

    private fun tap() {
        tapDownTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        showNow()
    }

    private fun move(dx: Int, dy: Int, repeat: Int) {
        if (!shown) { showNow(); return }
        val step = minOf(40, 8 + 4 * repeat).toFloat()
        val nx = x + dx * step
        val ny = y + dy * step
        x = nx.coerceIn(0f, width - 1f)
        y = ny.coerceIn(0f, height - 1f)
        // Pushing past an edge scrolls the content under the pointer (also inner scroll areas).
        if (nx != x || ny != y) scroll(-dx * step / 16f, -dy * step / 16f)
        mouse(MotionEvent.ACTION_HOVER_MOVE, 0f, 0f)
        showNow()
    }

    /** Hides the pointer until the next arrow key (that key only brings it back). */
    fun hideNow() {
        removeCallbacks(hide)
        shown = false
        invalidate()
    }

    /** Shows the pointer (pointer mode only) for a few seconds. */
    fun showNow() {
        shown = true
        invalidate()
        hideLater()
    }

    private fun hideLater() {
        removeCallbacks(hide)
        postDelayed(hide, 5000)
    }

    /** Pointer position in [v]'s own coordinates. */
    private fun localTo(v: View): Pair<Float, Float> {
        val me = IntArray(2).also { getLocationOnScreen(it) }
        val other = IntArray(2).also { v.getLocationOnScreen(it) }
        return Pair(x + me[0] - other[0], y + me[1] - other[1])
    }

    private fun touch(action: Int) {
        val v = target()
        val (lx, ly) = localTo(v)
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = lx; this.y = ly; pressure = 1f; size = 1f }
        val ev = MotionEvent.obtain(
            tapDownTime, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        v.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun scroll(h: Float, v: Float) = mouse(MotionEvent.ACTION_SCROLL, h, v)

    private fun mouse(action: Int, hScroll: Float, vScroll: Float) {
        val v = target()
        val (lx, ly) = localTo(v)
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = lx; this.y = ly
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        }
        val ev = MotionEvent.obtain(
            now, now, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        v.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    companion object {
        const val LONG_PRESS_MS = 600L
        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
