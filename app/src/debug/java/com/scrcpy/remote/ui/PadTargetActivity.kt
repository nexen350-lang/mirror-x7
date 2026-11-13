package com.scrcpy.remote.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug only: something on the target device for the precision pad's live test to press and
 * scroll. A grid of small, touching buttons tells a hit from a near miss; only the centre one is
 * the target. Not part of a release build.
 */
class PadTargetActivity : Activity() {
    /** Clicks per button, row by row; index 4 is the centre. */
    val clicks = IntArray(GRID * GRID)
    lateinit var grid: GridLayout
        private set
    lateinit var scroller: ScrollView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        grid = GridLayout(this).apply {
            columnCount = GRID
            rowCount = GRID
            for (index in 0 until GRID * GRID) {
                addView(View(context).apply {
                    setBackgroundColor(if (index == CENTRE) Color.RED else Color.DKGRAY)
                    setOnClickListener { clicks[index]++ }
                }, GridLayout.LayoutParams().apply { width = dp(TARGET_DP); height = dp(TARGET_DP) })
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (index in 0 until 80) {
                addView(TextView(context).apply {
                    text = "Row $index"
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(if (index % 2 == 0) Color.LTGRAY else Color.WHITE)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            }
        }
        scroller = ScrollView(this).apply { addView(column) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(48); topMargin = dp(160) })
            addView(scroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
                .apply { topMargin = dp(320) })
        })
    }

    companion object {
        const val GRID = 3
        const val CENTRE = 4
        const val TARGET_DP = 8
    }
}
