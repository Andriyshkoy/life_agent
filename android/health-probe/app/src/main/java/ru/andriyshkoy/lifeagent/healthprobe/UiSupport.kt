package ru.andriyshkoy.lifeagent.healthprobe

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.textView(
    text: CharSequence = "",
    sizeSp: Float = 16f,
    bold: Boolean = false,
): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor("#172033".toColorInt())
        if (bold) {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

fun Context.actionButton(label: String): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(52)
    }

fun LinearLayout.addSpaced(view: View, topDp: Int = 10) {
    addView(
        view,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = context.dp(topDp)
        },
    )
}

fun applySystemBarInsets(view: View, horizontalDp: Int = 20, verticalDp: Int = 20) {
    val horizontal = view.context.dp(horizontalDp)
    val vertical = view.context.dp(verticalDp)
    ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        target.setPadding(
            horizontal + bars.left,
            vertical + bars.top,
            horizontal + bars.right,
            vertical + bars.bottom,
        )
        insets
    }
}
