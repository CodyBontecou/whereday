package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Address lookup stays on the system provider; only explicit nearby requests use Geoapify. */
class GeoapifyPlaceLookup internal constructor(private val addresses:PlaceLookupProvider,private val places:GeoapifyNearbyPlaces):PlaceLookupProvider {
    override suspend fun search(query:String,language:String)=addresses.search(query,language)
    override suspend fun reverse(coordinate:Coordinate,language:String)=addresses.reverse(coordinate,language)
    override suspend fun nearby(coordinate:Coordinate,radiusMeters:Double,language:String,query:NearbyPlaceQuery)=places.nearby(coordinate,radiusMeters,language,query)
    companion object {
        val available:Boolean get()=BuildConfig.GEOAPIFY_NEARBY_AVAILABLE && BuildConfig.GEOAPIFY_API_KEY.isNotBlank()
        fun configured(addresses:PlaceLookupProvider):PlaceLookupProvider=
            if(!available) addresses
            else GeoapifyPlaceLookup(addresses,GeoapifyNearbyPlaces(GeoapifyNearbyPlaces.ENDPOINT.toHttpUrl(),BuildConfig.GEOAPIFY_API_KEY,OkHttpClient()))
    }
}

/** Test endpoints are internal. Never log this client's URLs: they contain credentials and coordinates. */
internal class GeoapifyNearbyPlaces(private val endpoint:HttpUrl,key:String,base:OkHttpClient) {
    private val key=key.trim().also {require(it.length in 1..512 && it.none(Char::isISOControl)) {"Invalid Geoapify configuration."}}
    private val client=base.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        // OkHttp's 503 Retry-After: 0 follow-up is independent of retryOnConnectionFailure.
        // The caller owns retries; strip only that follow-up instruction, keeping the failure status.
        .addNetworkInterceptor {chain->chain.proceed(chain.request()).let {reply->
            if(reply.code==503) reply.newBuilder().removeHeader("Retry-After").build() else reply
        }}
        .callTimeout(10,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build()
    suspend fun nearby(origin:Coordinate,radius:Double,language:String,query:NearbyPlaceQuery):List<PlaceLookupRecord> {
        require(radius.isFinite() && radius>0 && radius<=2000) {"Invalid nearby radius."}
        val text=(query as? NearbyPlaceQuery.Text)?.value?.trim()
        require(text==null || text.isNotEmpty() && text.length<=256) {"Invalid nearby query."}
        val mapped=text?.lowercase(Locale.ROOT)?.let {fallbackCategories[it]}
        val categories=when(query) {
            NearbyPlaceQuery.Businesses->businessCategories
            NearbyPlaceQuery.AllPlaces->allCategories
            is NearbyPlaceQuery.Text->mapped ?: allCategories
        }
        val lang=Locale.forLanguageTag(language).language.takeIf {it.length==2 && it in languages} ?: "en"
        val url=endpoint.newBuilder().addQueryParameter("apiKey",key).addQueryParameter("categories",categories)
            .addQueryParameter("filter","circle:${origin.longitude},${origin.latitude},$radius")
            .addQueryParameter("bias","proximity:${origin.longitude},${origin.latitude}")
            .addQueryParameter("limit","50").addQueryParameter("lang",lang).apply {
                if(text!=null && mapped==null) addQueryParameter("name",text)
            }.build()
        return suspendCancellableCoroutine {continuation->
            val call=client.newCall(Request.Builder().url(url).header("Accept","application/geo+json, application/json").build())
            continuation.invokeOnCancellation {call.cancel()}
            if(!continuation.isActive) return@suspendCancellableCoroutine
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) {if(continuation.isActive) continuation.resumeWithException(unavailable())}
                override fun onResponse(call:Call,response:Response) {
                    val result=runCatching {response.use {reply->
                        require(reply.isSuccessful)
                        val body=requireNotNull(reply.body)
                        require(body.contentType()?.type=="application" && body.contentType()?.subtype in setOf("json","geo+json"))
                        require(body.contentLength()<=maximumBytes)
                        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        body.byteStream().use {stream->while(output.size()<=maximumBytes) {
                            val n=stream.read(buffer,0,minOf(buffer.size,maximumBytes+1-output.size()))
                            if(n<0) break
                            output.write(buffer,0,n)
                            require(output.size()<=maximumBytes)
                        }}
                        val raw=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        parse(raw)
                    }}
                    if(continuation.isActive) result.fold({continuation.resume(it)},{continuation.resumeWithException(unavailable())})
                }
            })
        }
    }
    companion object {
        const val ENDPOINT="https://api.geoapify.com/v2/places"
        private const val maximumBytes=1_048_576
        private const val businessCategories="commercial,catering,accommodation,service,healthcare,entertainment"
        private const val allCategories="$businessCategories,leisure,tourism,education,office,parking,public_transport,sport,natural"
        private val languages=Locale.getISOLanguages().toSet()
        private val fallbackCategories=mapOf("restaurant" to "catering.restaurant","coffee" to "catering.cafe","bar" to "catering.bar","hotel" to "accommodation.hotel","shop" to "commercial")
        private fun unavailable()=IOException("Nearby business search is unavailable. Try again later.")
        private fun parse(raw:String):List<PlaceLookupRecord> {
            // Bounded strict JSON, including duplicate-key rejection throughout the document.
            val root=JsonReader(StringReader(raw)).use {reader->
                reader.strictness=Strictness.STRICT
                fun value(depth:Int):JsonElement {
                    require(depth<=16)
                    return when(reader.peek()) {
                        JsonToken.BEGIN_OBJECT->JsonObject().apply {
                            reader.beginObject();while(reader.hasNext()) {require(size()<256);val key=reader.nextName();require(!has(key));add(key,value(depth+1))};reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY->JsonArray().apply {
                            reader.beginArray();while(reader.hasNext()) {require(size()<1024);add(value(depth+1))};reader.endArray()
                        }
                        JsonToken.STRING->JsonPrimitive(reader.nextString())
                        JsonToken.NUMBER->JsonParser.parseString(reader.nextString())
                        JsonToken.BOOLEAN->JsonPrimitive(reader.nextBoolean())
                        JsonToken.NULL->{reader.nextNull();JsonNull.INSTANCE}
                        else->error("Invalid JSON")
                    }
                }
                value(0).also {require(reader.peek()==JsonToken.END_DOCUMENT && it.isJsonObject)}.asJsonObject
            }
            fun text(obj:JsonObject,key:String):String?=obj[key]?.takeIf {it.isJsonPrimitive && it.asJsonPrimitive.isString}?.asString?.trim()?.takeIf {it.isNotEmpty()}?.take(4096)
            require(text(root,"type")=="FeatureCollection")
            val features=requireNotNull(root["features"]?.takeIf {it.isJsonArray}?.asJsonArray);require(features.size()<=50)
            return features.mapNotNull {feature->
                val obj=feature.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
                if(text(obj,"type")!="Feature") return@mapNotNull null
                val p=obj["properties"]?.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
                fun number(key:String)=p[key]?.takeIf {it.isJsonPrimitive && it.asJsonPrimitive.isNumber}?.asDouble?.takeIf {it.isFinite()}
                val lat=number("lat") ?: return@mapNotNull null;val lon=number("lon") ?: return@mapNotNull null
                try {Coordinate(lat,lon)} catch(_:IllegalArgumentException) {return@mapNotNull null}
                val name=text(p,"name") ?: return@mapNotNull null
                PlaceLookupRecord(name,text(p,"formatted"),lat,lon)
            }
        }
    }
}
