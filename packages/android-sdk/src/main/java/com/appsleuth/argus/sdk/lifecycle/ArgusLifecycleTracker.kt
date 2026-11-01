package com.appsleuth.argus.sdk.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.appsleuth.argus.sdk.model.ArgusEvent
import com.appsleuth.argus.sdk.model.EventType
import com.appsleuth.argus.sdk.model.Outcome
import com.appsleuth.argus.sdk.recorder.ArgusFlightRecorder

class ArgusLifecycleTracker(
    private val enableFragmentTracking: Boolean = true,
    private val enableTouchTracking: Boolean = true
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (enableFragmentTracking && activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        super.onFragmentResumed(fm, f)
                        val name = f.javaClass.simpleName
                        if (name.isNotBlank() && !name.startsWith("SupportRequestManagerFragment")) {
                            ArgusFlightRecorder.record(
                                ArgusEvent(
                                    type = EventType.SCREEN_VIEW,
                                    label = "Fragment:$name",
                                    source = f.javaClass.name,
                                    outcome = Outcome.SUCCESS
                                )
                            )
                        }
                    }
                },
                true
            )
        }

        if (enableTouchTracking) {
            wrapWindowCallback(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        val name = activity.javaClass.simpleName
        ArgusFlightRecorder.record(
            ArgusEvent(
                type = EventType.SCREEN_VIEW,
                label = "Screen:$name",
                source = activity.componentName.className,
                outcome = Outcome.SUCCESS
            )
        )
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun wrapWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val original = window.callback ?: return
        window.callback = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                if (event?.action == MotionEvent.ACTION_UP) {
                    try {
                        val decor = window.decorView
                        val touchedView = findTouchedView(decor, event.rawX, event.rawY)
                        if (touchedView != null && isClickableOrInteractive(touchedView)) {
                            val label = extractViewLabel(touchedView)
                            ArgusFlightRecorder.record(
                                ArgusEvent(
                                    type = EventType.USER_ACTION,
                                    label = "Tap:$label",
                                    source = activity.javaClass.simpleName,
                                    outcome = Outcome.SUCCESS
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
                return original.dispatchTouchEvent(event)
            }
        }
    }

    private fun isClickableOrInteractive(view: View): Boolean {
        return view.isClickable || view.isFocusable || view is TextView
    }

    private fun findTouchedView(root: View, x: Float, y: Float): View? {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + root.width
        val bottom = top + root.height

        if (x < left || x > right || y < top || y > bottom) return null

        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                if (child.visibility == View.VISIBLE) {
                    val target = findTouchedView(child, x, y)
                    if (target != null && isClickableOrInteractive(target)) {
                        return target
                    }
                }
            }
        }
        return root
    }

    private fun extractViewLabel(view: View): String {
        val desc = view.contentDescription?.toString()
        if (!desc.isNullOrBlank()) return desc.take(40)

        if (view is TextView && !view.text.isNullOrBlank()) {
            return view.text.toString().trim().take(40)
        }

        val resId = try {
            if (view.id != View.NO_ID) {
                view.resources.getResourceEntryName(view.id)
            } else null
        } catch (_: Exception) { null }

        if (!resId.isNullOrBlank()) return resId.take(40)

        return view.javaClass.simpleName
    }
}
