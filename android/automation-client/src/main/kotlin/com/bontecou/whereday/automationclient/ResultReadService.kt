package com.bontecou.whereday.automationclient

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/** Foreign-UID background read after the result-receiving Activity has actually finished. */
class ResultReadService:Service() {
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val token=intent?.getStringExtra("token") ?: return START_NOT_STICKY
        val uri=Uri.parse(intent.getStringExtra("uri"))
        Handler(Looper.getMainLooper()).postDelayed({
            val destroyed=ClientActivity.destroyedRequesters.remove(token)
            val receipt=JSONObject().put("requesterDestroyed",destroyed).put("uri",uri.toString())
                .put("code",intent.getIntExtra("code",0))
            try {
                check(destroyed)
                require(uri.authority=="com.bontecou.whereday.mapqa.automation-results")
                contentResolver.openInputStream(uri)!!.use {stream ->
                    val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(bytes.size()<=65536) {val count=stream.read(buffer);if(count<0) break;bytes.write(buffer,0,count)}
                    check(bytes.size()<=65536)
                    receipt.put("payload",bytes.toString("UTF-8")).put("readable",true)
                }
            } catch(_:Exception) {receipt.put("readable",false)}
            getSharedPreferences("receipts",MODE_PRIVATE).edit().putString(token,receipt.toString()).commit()
            stopSelf(startId)
        },1000)
        return START_NOT_STICKY
    }
}
