package com.bontecou.whereday

import android.os.SystemClock
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot

/** Wait for the owned window's actual IME state, rather than an IMM result callback. */
internal fun closeQaKeyboard():ViewAction=object:ViewAction {
    override fun getConstraints()=isRoot()
    override fun getDescription()="Hide the software keyboard and verify the window's IME insets"
    override fun perform(controller:UiController,view:View) {
        checkNotNull(ViewCompat.getWindowInsetsController(view)).hide(WindowInsetsCompat.Type.ime())
        val deadline=SystemClock.uptimeMillis()+5000
        do {
            controller.loopMainThreadForAtLeast(50)
            val insets=checkNotNull(ViewCompat.getRootWindowInsets(view)) {"The QA window must expose IME insets"}
            if(!insets.isVisible(WindowInsetsCompat.Type.ime())) return
        } while(SystemClock.uptimeMillis()<deadline)
        error("The software keyboard is still visible after the hide request")
    }
}
