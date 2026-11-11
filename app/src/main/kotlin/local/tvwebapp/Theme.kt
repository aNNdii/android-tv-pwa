package local.tvwebapp

import android.content.Context
import android.content.res.Configuration

/**
 * The colours of everything the app draws itself (focus frame, loading screen and bar, pointer), from
 * Catppuccin: Mocha when the TV is set to dark, Latte when it is set to light. Read once per start
 * (the Activity handles uiMode changes itself, so a switch shows on the next start).
 * The accent is Yellow: it stands out on videos and on most pages.
 */
class Theme private constructor(
    /** Focus frame and loading bar. */
    val accent: Int,
    /** Thin contrast line around the focus frame and the pointer. */
    val outline: Int,
    /** Loading screen of an app without its own colour. */
    val base: Int,
    /** Pointer and spinner. */
    val text: Int,
) {
    companion object {
        private val MOCHA = Theme(accent = 0xfff9e2af.toInt(), outline = 0xff11111b.toInt(), base = 0xff1e1e2e.toInt(), text = 0xffcdd6f4.toInt())
        private val LATTE = Theme(accent = 0xffdf8e1d.toInt(), outline = 0xffdce0e8.toInt(), base = 0xffeff1f5.toInt(), text = 0xff4c4f69.toInt())

        fun of(context: Context): Theme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_NO) LATTE else MOCHA
        }

        /** CSS form of a colour, e.g. "#f9e2af". */
        fun css(color: Int) = "#%06x".format(color and 0xffffff)
    }
}
