package com.bontecou.whereday.platform

import org.junit.Assert.assertTrue
import org.junit.Test

/** Release gate: a release build without Play's in-app review would never ask for a rating (and Support's
 * "Rate Whereday on Google Play" row would be disabled). The release workflow runs :platform:testReleaseUnitTest. */
class ReleasePlayReviewTest {
    @Test fun releaseBuildsEnablePlayReview() {
        assertTrue("release BuildConfig has PLAY_REVIEW_AVAILABLE=false; drop -Pwhereday.playReviewEnabled=false",BuildConfig.PLAY_REVIEW_AVAILABLE)
    }
}
