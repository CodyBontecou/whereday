package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ConfiguredRoadProvider(val directions:RoadDirections,val disclosure:RoadProviderDisclosure)

/** The controller admits each call. Never log URLs/errors from this client: the query has coordinates and a key. */
internal class GeoapifyRoadDirections(endpoint:HttpUrl,key:String,base:OkHttpClient,timeoutMillis:Long=10_000,
    private val elapsedNanos:()->Long=System::nanoTime,private val nowMillis:()->Long=System::currentTimeMillis):RoadDirections {
    private val endpoint=endpoint.also {
        require(it.encodedPath=="/v1/routing" && it.query==null && it.fragment==null && it.username.isEmpty() && it.password.isEmpty()) {"Invalid routing endpoint"}
        require(it.scheme=="https" || it.scheme=="http" && it.host in setOf("localhost","127.0.0.1","::1")) {"Routing requires HTTPS"}
    }
    private val key=key.trim().also {require(validKey(it)) {"Invalid routing configuration"}}
    private val cooldownLock=Any()
    private var blockedUntil:Long?=null
    private val client=base.newBuilder().apply {require(timeoutMillis in 1..10_000)}
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).cache(null)
        // 503 Retry-After: 0 otherwise follows up even with retryOnConnectionFailure disabled.
        .addNetworkInterceptor {chain->chain.proceed(chain.request()).let {reply->
            if(reply.code==429 || reply.code==503) cooldown(reply.header("Retry-After"))
            if(reply.code==503) reply.newBuilder().removeHeader("Retry-After").build() else reply
        }}
        .callTimeout(timeoutMillis,TimeUnit.MILLISECONDS).connectTimeout(timeoutMillis,TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis,TimeUnit.MILLISECONDS).writeTimeout(timeoutMillis,TimeUnit.MILLISECONDS).build()

    override suspend fun route(start:Coordinate,end:Coordinate,transport:RoadTransport):RoadDirectionsResult? {
        currentCoroutineContext().ensureActive()
        if(synchronized(cooldownLock) {blockedUntil?.let {it-elapsedNanos()>0}==true}) throw unavailable()
        val mode=when(transport) {RoadTransport.walking->"walk";RoadTransport.automobile->"drive"}
        val url=endpoint.newBuilder().addQueryParameter("apiKey",key)
            .addQueryParameter("waypoints","${start.latitude},${start.longitude}|${end.latitude},${end.longitude}")
            .addQueryParameter("mode",mode).addQueryParameter("units","metric").addQueryParameter("format","geojson").build()
        return suspendCancellableCoroutine {continuation->
            val call=client.newCall(Request.Builder().url(url).header("Accept","application/geo+json, application/json")
                .header("Cache-Control","no-store").build())
            continuation.invokeOnCancellation {call.cancel()}
            if(!continuation.isActive) return@suspendCancellableCoroutine
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) {if(continuation.isActive) continuation.resumeWithException(unavailable())}
                override fun onResponse(call:Call,response:Response) {
                    val result=try {response.use {reply->
                        require(reply.isSuccessful)
                        val body=requireNotNull(reply.body)
                        val type=body.contentType()
                        require(type?.type=="application" && type.subtype in setOf("json","geo+json") && type.charset(Charsets.UTF_8)==Charsets.UTF_8)
                        require(body.contentLength()<=maximumBytes)
                        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        fun checkpoint() {check(continuation.isActive)}
                        body.byteStream().use {stream->while(true) {
                            checkpoint()
                            val n=stream.read(buffer,0,minOf(buffer.size,maximumBytes+1-output.size()))
                            if(n<0) break
                            output.write(buffer,0,n);require(output.size()<=maximumBytes)
                        }}
                        checkpoint()
                        val raw=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        RoadJson(raw,::checkpoint).parse(mode)?.copy(attributions=attributions)
                    }} catch(_:Exception) {
                        if(continuation.isActive) continuation.resumeWithException(unavailable())
                        return
                    }
                    if(continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun cooldown(retryAfter:String?) {
        val header=retryAfter?.takeIf {it.length<=256}
        val seconds=header?.toLongOrNull()?.coerceIn(60,3600) ?: runCatching {
            val retry=java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((retry-nowMillis())/1000).coerceIn(60,3600)
        }.getOrDefault(60)
        synchronized(cooldownLock) {blockedUntil=elapsedNanos()+seconds*1_000_000_000}
    }

    companion object {
        const val ENDPOINT="https://api.geoapify.com/v1/routing"
        private val attributions=listOf(RoadAttribution("Geoapify","https://www.geoapify.com/"),
            RoadAttribution("OpenStreetMap","https://www.openstreetmap.org/copyright",RoadAttributionKind.data))
        private const val maximumBytes=2_097_152
        private fun validKey(value:String)=value.length in 1..512 && value.none(Char::isISOControl)
        private fun unavailable()=IOException("Road routing is unavailable. Try again later.")
        fun configured(enabled:Boolean=BuildConfig.GEOAPIFY_ROUTING_AVAILABLE,key:String=BuildConfig.GEOAPIFY_API_KEY):ConfiguredRoadProvider? {
            if(!enabled || !validKey(key.trim())) return null
            return ConfiguredRoadProvider(GeoapifyRoadDirections(ENDPOINT.toHttpUrl(),key,OkHttpClient()),
                RoadProviderDisclosure("Geoapify Routing","https://www.geoapify.com/privacy-policy/"))
        }
    }

    /** Retain only the one requested leg. Unknown metadata is validated and discarded with bounded work. */
    private class RoadJson(raw:String,private val checkpoint:()->Unit) {
        private val reader=JsonReader(StringReader(raw)).apply {strictness=Strictness.STRICT}
        private var tokens=0
        private fun tick(depth:Int=0) {checkpoint();require(depth<=16 && ++tokens<=200_000)}
        private fun text():String {tick();require(reader.peek()==JsonToken.STRING);return reader.nextString().also {require(it.length<=16_384)}}
        private fun number():Double {tick();require(reader.peek()==JsonToken.NUMBER);return reader.nextString().also {require(it.length<=128)}.toDouble().also {require(it.isFinite())}}
        private fun obj(depth:Int,field:(String,Int)->Unit) {
            tick(depth);require(reader.peek()==JsonToken.BEGIN_OBJECT);reader.beginObject()
            val seen=HashSet<String>()
            while(reader.hasNext()) {
                tick(depth);val name=reader.nextName();require(name.length<=256 && seen.size<256 && seen.add(name))
                field(name,depth+1)
            }
            reader.endObject()
        }
        private fun array(depth:Int,limit:Int=20_000,element:(Int)->Unit):Int {
            tick(depth);require(reader.peek()==JsonToken.BEGIN_ARRAY);reader.beginArray();var size=0
            while(reader.hasNext()) {require(size++<limit);element(depth+1)}
            reader.endArray();return size
        }
        private fun discard(depth:Int) {
            tick(depth)
            when(reader.peek()) {
                JsonToken.BEGIN_OBJECT->obj(depth) {_,child->discard(child)}
                JsonToken.BEGIN_ARRAY->array(depth) {child->discard(child)}
                JsonToken.STRING->text()
                JsonToken.NUMBER->number()
                JsonToken.BOOLEAN->reader.nextBoolean()
                JsonToken.NULL->reader.nextNull()
                else->error("Invalid JSON")
            }
        }
        private fun point(depth:Int):Coordinate {
            val values=ArrayList<Double>(3)
            array(depth,3) {values+=number()};require(values.size>=2)
            return Coordinate(values[1],values[0])
        }
        private fun geometry(depth:Int):List<Coordinate> {
            var type:String?=null;var coordinates:List<Coordinate>?=null
            obj(depth) {name,child->when(name) {
                "type"->type=text()
                "coordinates"->{val lines=ArrayList<List<Coordinate>>(1)
                    array(child,1) {lineDepth->val line=ArrayList<Coordinate>();array(lineDepth) {line+=point(it)};require(line.size>=2);lines+=line}
                    require(lines.size==1);coordinates=lines.single()
                }
                else->discard(child)
            }}
            require(type=="MultiLineString");return requireNotNull(coordinates)
        }
        private fun feature(depth:Int,mode:String):RoadDirectionsResult {
            var type:String?=null;var distance:Double?=null;var units:String?=null;var returnedMode:String?=null
            var coordinates:List<Coordinate>?=null
            obj(depth) {name,child->when(name) {
                "type"->type=text()
                "properties"->obj(child) {key,nested->when(key) {
                    "distance"->distance=number()
                    "distance_units"->units=text()
                    "mode"->returnedMode=text()
                    else->discard(nested)
                }}
                "geometry"->coordinates=geometry(child)
                else->discard(child)
            }}
            require(type=="Feature" && units=="Meters" && (returnedMode==null || returnedMode==mode))
            return RoadDirectionsResult(requireNotNull(distance).also {require(it>0)},requireNotNull(coordinates))
        }
        fun parse(mode:String):RoadDirectionsResult?=reader.use {
            var type:String?=null;var features:List<RoadDirectionsResult>?=null
            obj(0) {name,child->when(name) {
                "type"->type=text()
                "features"->{val routes=ArrayList<RoadDirectionsResult>(1);array(child,1) {routes+=feature(it,mode)};features=routes}
                else->discard(child)
            }}
            require(type=="FeatureCollection" && reader.peek()==JsonToken.END_DOCUMENT)
            requireNotNull(features).singleOrNull()
        }
    }
}
