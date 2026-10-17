package one.monero.moneroone.ui.screens.settings

import android.content.Context
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The fields are 1000 px tall. The full-height pass gives them 1200 px, where they fit and the scroll
// falls back to 0. The frame above the keyboard gives them 400 px, where they scroll by up to 600 px.
private const val CONTENT = 1000
private const val TALL = 1200
private const val SHORT = 400
private const val SCROLLED = 500

@RunWith(AndroidJUnit4::class)
class KeepScrollWhenRemeasuredTest {
    @get:Rule val rule = createComposeRule()

    /** The scroll position after each measure pass of the fields, in order. */
    private val passes = mutableListOf<Int>()

    @Test fun keepsTheScrollThroughAFullHeightPassAndSettles() {
        val state = ScrollState(0)
        lateinit var frame: TwoPassFrame
        rule.setContent {
            AndroidView(factory = { context ->
                TwoPassFrame(context).also { frame = it }.apply {
                    addView(ComposeView(context).apply {
                        setContent {
                            Column(Modifier.notePasses(state).keepScrollWhenRemeasured(state).verticalScroll(state)) {
                                Layout { _, constraints -> layout(constraints.maxWidth, CONTENT) {} }
                            }
                        }
                    })
                }
            })
        }
        rule.runOnIdle { runBlocking { state.scrollTo(SCROLLED) } }
        rule.runOnIdle {
            assertEquals(SCROLLED, state.value)
            passes.clear()
            frame.relayout()
        }

        // The full-height pass cut the position to 0. The short pass put it back.
        rule.runOnIdle {
            assertEquals(listOf(0, SCROLLED), passes)
            assertEquals(SCROLLED, state.value)
        }

        // One restore, then a stable layout: the next frames measure the fields no more.
        waitForFrames(10)
        rule.runOnIdle {
            assertEquals(listOf(0, SCROLLED), passes)
            assertEquals(SCROLLED, state.value)
        }
    }

    // Notes the position after each measure pass. It reads the position without observation, as the
    // modifier under test does, so the note itself starts no new pass.
    private fun Modifier.notePasses(state: ScrollState) = layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        passes += Snapshot.withoutReadObservation { state.value }
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    // Holds the test thread while the main thread runs [count] frames.
    private fun waitForFrames(count: Int) {
        val frames = CountDownLatch(count)
        rule.runOnUiThread {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    frames.countDown()
                    if (frames.count > 0) Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        assertTrue(frames.await(5, TimeUnit.SECONDS))
    }
}

/**
 * Measures its child twice on each layout, at full height and then short, as the system measures a
 * dialog resized for the keyboard.
 */
private class TwoPassFrame(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        val width = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)
        child.measure(width, MeasureSpec.makeMeasureSpec(TALL, MeasureSpec.AT_MOST))
        child.measure(width, MeasureSpec.makeMeasureSpec(SHORT, MeasureSpec.AT_MOST))
        setMeasuredDimension(child.measuredWidth, child.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getChildAt(0).layout(0, 0, r - l, b - t)
    }

    /** Lays the frame out again from the top, as the system does when the keyboard resizes a window. */
    fun relayout() {
        forceLayoutTree(this)
        requestLayout()
    }

    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (i in 0 until view.childCount) forceLayoutTree(view.getChildAt(i))
    }
}
