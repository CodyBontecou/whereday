package com.bontecou.whereday.domain

object WebhookDeliveryRules {
    const val maxAttempts=3
    const val maxBatchPoints=500
    const val leaseSeconds=120L
    fun retryable(status:Int)=status==0 || status==408 || status==429 || status>=500
}
