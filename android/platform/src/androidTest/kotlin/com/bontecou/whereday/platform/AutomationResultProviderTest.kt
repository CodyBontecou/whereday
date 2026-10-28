package com.bontecou.whereday.platform

import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
class AutomationResultProviderTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get()=AppGraph.from(context)
    private val authority get()="${context.packageName}.automation-results"
    @Before fun setup()=runBlocking {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        graph.automationAccess.revoke();graph.automationResults.clear()
    }
    @After fun cleanup()=runBlocking {graph.automationAccess.revoke();graph.automationResults.clear()}
    private suspend fun result():Uri {
        graph.automationAccess.approve(requireNotNull(installedAutomationIdentity(context,context.packageName)),setOf(AutomationScope.summary))
        val result=graph.automationResults.create(context.packageName,AutomationScope.summary,"synthetic.json","application/json") {it.write("{\"synthetic\":true}".toByteArray())}
        return Uri.parse("content://$authority/${result.id}")
    }
    private inline fun <reified T:Throwable> fails(operation:()->Unit) {
        try {operation();fail("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e}
    }
    @Test fun mergedProviderIsNonexportedAndReadOnlyMetadataAndPayloadUseRealCaller()=runBlocking {
        val info=context.packageManager.resolveContentProvider(authority,0)!!
        assertFalse(info.exported);assertTrue(info.grantUriPermissions)
        val uri=result()
        assertEquals("application/octet-stream",context.contentResolver.getType(uri))
        assertEquals("application/octet-stream",context.contentResolver.getType(Uri.parse("content://$authority/00000000-0000-0000-0000-000000000000")))
        context.contentResolver.query(uri,null,null,null,null)!!.use {cursor ->
            assertEquals(1,cursor.count);assertTrue(cursor.moveToFirst())
            assertEquals("synthetic.json",cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(18L,cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val text=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()}
        assertEquals("{\"synthetic\":true}",text)
        fails<FileNotFoundException> {context.contentResolver.openFileDescriptor(uri,"rw")?.close()}
    }
    @Test fun revocationDeniesAUriThatPreviouslyOpenedAndReapprovalDoesNotReviveIt()=runBlocking {
        val uri=result()
        context.contentResolver.openInputStream(uri)!!.use {assertEquals('{'.code,it.read())}
        graph.automationAccess.revoke()
        fails<SecurityException> {context.contentResolver.openInputStream(uri)?.close()}
        graph.automationAccess.approve(requireNotNull(installedAutomationIdentity(context,context.packageName)),setOf(AutomationScope.summary))
        fails<FileNotFoundException> {context.contentResolver.openInputStream(uri)?.close()}
    }
    @Test fun invalidUrisUnsupportedQueriesAndCancelledRequestsCannotRead()=runBlocking {
        val uri=result()
        for(bad in listOf("content://$authority/../approval.json","content://$authority/${uri.lastPathSegment}?secret=x","content://$authority/${uri.lastPathSegment}/extra"))
            fails<FileNotFoundException> {context.contentResolver.openInputStream(Uri.parse(bad))?.close()}
        fails<IllegalArgumentException> {context.contentResolver.query(uri,arrayOf("secret"),null,null,null)?.close()}
        fails<IllegalArgumentException> {context.contentResolver.query(uri,null,"1=1",null,null)?.close()}
        val signal=CancellationSignal().apply {cancel()}
        fails<OperationCanceledException> {context.contentResolver.openFileDescriptor(uri,"r",signal)?.close()}
    }
}
