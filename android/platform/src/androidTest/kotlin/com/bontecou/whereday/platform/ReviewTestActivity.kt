package com.bontecou.whereday.platform

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.*

class ReviewTestActivity:Activity(),LifecycleOwner {
    private val registry=LifecycleRegistry(this)
    override val lifecycle:Lifecycle get()=registry
    override fun onCreate(state:Bundle?) {super.onCreate(state);registry.currentState=Lifecycle.State.CREATED;setContentView(android.widget.TextView(this).apply {text="Review test host"})}
    override fun onStart() {super.onStart();registry.currentState=Lifecycle.State.STARTED}
    override fun onResume() {super.onResume();registry.currentState=Lifecycle.State.RESUMED}
    override fun onPause() {registry.currentState=Lifecycle.State.STARTED;super.onPause()}
    override fun onStop() {registry.currentState=Lifecycle.State.CREATED;super.onStop()}
    override fun onDestroy() {registry.currentState=Lifecycle.State.DESTROYED;super.onDestroy()}
}
