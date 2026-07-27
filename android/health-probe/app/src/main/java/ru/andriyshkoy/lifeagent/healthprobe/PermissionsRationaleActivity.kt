package ru.andriyshkoy.lifeagent.healthprobe

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.toColorInt

@SuppressLint("SetTextI18n")
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor("#F6F7FB".toColorInt())
            }
        applySystemBarInsets(content)

        content.addView(textView("Health data privacy", 24f, bold = true))
        content.addSpaced(
            textView(
                "This temporary day-0 probe reads only Sleep, Heart Rate, and Resting " +
                    "Heart Rate from Health Connect after you grant core access. A separate, " +
                    "optional request can read HRV, oxygen saturation, respiratory rate, " +
                    "exercise sessions, steps/cadence, distance, calories, and speed.",
                16f,
            ),
            12,
        )
        content.addSpaced(
            textView(
                "It creates a capability report with record/sample counts, the expected OHealth " +
                    "package attribution, aggregated counts for every other origin, sleep-stage " +
                    "and exercise-type counts, rounded time coverage, and metadata presence.",
                16f,
            ),
            12,
        )
        content.addSpaced(
            textView(
                "The report never includes measurement values, non-OHealth package names, " +
                    "routes, record IDs, exact timestamps, titles, or notes. The app has no " +
                    "network permission, does not write to Health Connect, and does not run " +
                    "in the background.",
                16f,
            ),
            12,
        )
        content.addSpaced(
            actionButton("Close").apply { setOnClickListener { finish() } },
            20,
        )

        setContentView(
            ScrollView(this).apply {
                addView(
                    content,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }
}
