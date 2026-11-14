package com.bontecou.whereday.domain

/**
 * The first-run and "How Whereday listens" presets (iOS RecordingDetail). Each is distance sampling with a fixed
 * distance filter and auto-stop; choosing one writes exactly these three settings. Stable [key]s survive state saving.
 */
enum class RecordingPreset(val key:String,val distanceMeters:Int,val stopAfterHours:Int) {
    /** Places + sparse routes: stops and a route point about every 100 m. */
    places("places",100,0),
    /** Places + routes: stops and a route point about every 25 m. */
    routes("routes",25,0),
    /** Every step: a point about every 5 m, stopping on its own after 2 h. */
    everyStep("every_step",5,2);

    /** Presets are distance-based; choosing one leaves any time-based sampling. */
    val samplingMode get()=RouteSamplingMode.distance

    /** All three must agree: custom filters, timers and time-based sampling never select a different preset. */
    fun matches(mode:RouteSamplingMode,distanceMeters:Int,stopAfterHours:Int)=
        mode==samplingMode && distanceMeters==this.distanceMeters && stopAfterHours==this.stopAfterHours

    companion object {
        /** iOS onboarding preselects Places + routes. */
        val DEFAULT=routes

        fun fromKey(key:String?)=entries.firstOrNull {it.key==key}

        /** The preset the settings currently match, or null for Custom. */
        fun matching(mode:RouteSamplingMode,distanceMeters:Int,stopAfterHours:Int)=
            entries.firstOrNull {it.matches(mode,distanceMeters,stopAfterHours)}

        /**
         * What onboarding preselects. A matching preset stays selected and untouched install defaults get [DEFAULT],
         * as on iOS. Settings tuned by hand (a replayed onboarding) select nothing, so continuing keeps them.
         */
        fun onboardingChoice(mode:RouteSamplingMode,distanceMeters:Int,stopAfterHours:Int,untouchedDefaults:Boolean)=
            matching(mode,distanceMeters,stopAfterHours) ?: DEFAULT.takeIf {untouchedDefaults}
    }
}
