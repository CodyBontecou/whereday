package com.bontecou.whereday.automationclient

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

/** Synthetic transport fixture only. No owner-app target, GPS/network permission or release variant. */
class ClientActivity:Activity() {
    private var token=""
    private var finishThenRead=false
    override fun onCreate(state:Bundle?) {super.onCreate(state);setContentView(TextView(this).apply {text="Automation QA client";textSize=22f;setPadding(32,64,32,32)});run(intent)}
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);run(intent)}
    private fun run(intent:Intent) {
        token=intent.getStringExtra("token").orEmpty()
        finishThenRead=intent.getStringExtra("mode")=="finishThenRead"
        if(runCatching {UUID.fromString(token).toString()==token}.getOrDefault(false)!=true) {finish();return}
        when(intent.getStringExtra("mode")) {
            "close"->{finish();return}
            "reopen"->{save(read(Intent().setDataAndType(Uri.parse(intent.getStringExtra("uri")),"application/json")));return}
        }
        val target=if(intent.getStringExtra("mode")=="forward") "com.bontecou.whereday.ForwardAutomationProbeActivity" else "com.bontecou.whereday.AutomationActivity"
        val request=intent.getStringExtra("request") ?: "{\"version\":1,\"command\":\"statistics\"}"
        if(request.length>32768) {save(JSONObject().put("error","fixture_input"));return}
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(intent.getStringExtra("action") ?: ACTION).setClassName(QA,target).putExtra(REQUEST,request)
            .putExtra("callerPackage","com.llamalab.automate"),7)
    }
    @Deprecated("Test fixture for the platform startActivityForResult contract")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(finishThenRead && data?.data!=null) {
            // Deliberately pass a STRING, with no URI grant flags/ClipData. Transferring an
            // Activity's grant to the Service would mask the lifetime defect being tested.
            startService(Intent(this,ResultReadService::class.java).putExtra("token",token)
                .putExtra("uri",data.data.toString()).putExtra("code",resultCode))
            finish();return
        }
        val result=if(data?.data!=null) read(data) else JSONObject()
        result.put("code",resultCode).put("flags",data?.flags ?: 0).put("uri",data?.data?.toString())
            .put("mime",data?.type).put("error",data?.getStringExtra(ERROR))
        save(result)
    }
    private fun read(data:Intent):JSONObject {
        val result=JSONObject();val uri=data.data ?: return result
        result.put("granted",checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        if(uri.scheme!="content" || uri.authority!="$QA.automation-results") return result.put("readable",false)
        try {
            contentResolver.openInputStream(uri)!!.use {input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(output.size()<=65536) {val n=input.read(buffer);if(n<0) break;output.write(buffer,0,n)}
                check(output.size()<=65536)
                result.put("payload",output.toString("UTF-8")).put("readable",true)
            }
        } catch(_:Exception) {result.put("readable",false)}
        try {contentResolver.openFileDescriptor(uri,"rw")?.close();result.put("writeDenied",false)} catch(_:Exception) {result.put("writeDenied",true)}
        try {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);result.put("persistDenied",false)} catch(_:Exception) {result.put("persistDenied",true)}
        return result
    }
    private fun save(result:JSONObject) {getSharedPreferences("receipts",MODE_PRIVATE).edit().putString(token,result.toString()).commit()}
    override fun onDestroy() {
        if(finishThenRead && isFinishing) destroyedRequesters.add(token)
        super.onDestroy()
    }
    companion object {
        internal val destroyedRequesters=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private const val QA="com.bontecou.whereday.mapqa"
        private const val ACTION="com.bontecou.whereday.automation.RUN"
        private const val REQUEST="com.bontecou.whereday.automation.REQUEST"
        private const val ERROR="com.bontecou.whereday.automation.ERROR"
    }
}
