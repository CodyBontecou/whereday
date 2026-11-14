package com.bontecou.whereday.domain

import kotlin.math.*

/** Altitude-independent WGS84 surface distance, with the measured Core Location local branch.
 * Long arcs use Vincenty's inverse series with a bounded iteration count. Exact/near antipodes
 * remain a platform-acceptance case; no converged shortest-geodesic guarantee is made there.
 */
internal object SurfaceDistance {
    private const val major=6_378_137.0
    private const val flattening=1.0/298.257223563
    private const val minor=major*(1-flattening)
    private const val eccentricitySquared=flattening*(2-flattening)
    fun between(a:Coordinate,b:Coordinate):Double {
        val rawLongitude=b.longitude-a.longitude
        val longitudeDelta=when {rawLongitude>180->rawLongitude-360;rawLongitude< -180->rawLongitude+360;else->rawLongitude}
        val latitudeDelta=b.latitude-a.latitude
        if(latitudeDelta==0.0 && longitudeDelta==0.0) return 0.0
        val phi1=Math.toRadians(a.latitude);val phi2=Math.toRadians(b.latitude)
        val latitudeArc=Math.toRadians(latitudeDelta);val longitudeArc=Math.toRadians(longitudeDelta)
        if(abs(latitudeDelta)<=2.0 && abs(longitudeDelta)<3.0) {
            val middle=Math.toRadians((a.latitude+b.latitude)/2)
            val denominator=1-eccentricitySquared*sin(middle).pow(2)
            val meridional=major*(1-eccentricitySquared)/denominator.pow(1.5)
            val primeVertical=major/sqrt(denominator)
            return hypot(meridional*latitudeArc,primeVertical*cos(middle)*longitudeArc)
        }
        val reduced1=atan((1-flattening)*tan(phi1));val reduced2=atan((1-flattening)*tan(phi2))
        val sin1=sin(reduced1);val cos1=cos(reduced1);val sin2=sin(reduced2);val cos2=cos(reduced2)
        var lambda=longitudeArc;var sinSigma=0.0;var cosSigma=0.0;var sigma=0.0;var cosSquaredAlpha=0.0;var cosDoubleSigma=0.0
        for(iteration in 0 until 20) {
            val sinLambda=sin(lambda);val cosLambda=cos(lambda)
            sinSigma=hypot(cos2*sinLambda,cos1*sin2-sin1*cos2*cosLambda)
            cosSigma=sin1*sin2+cos1*cos2*cosLambda
            if(sinSigma==0.0) return if(cosSigma>0) 0.0 else major*PI
            sigma=atan2(sinSigma,cosSigma)
            val sinAlpha=cos1*cos2*sinLambda/sinSigma
            cosSquaredAlpha=(1-sinAlpha*sinAlpha).coerceIn(0.0,1.0)
            cosDoubleSigma=if(cosSquaredAlpha==0.0) 0.0 else cosSigma-2*sin1*sin2/cosSquaredAlpha
            val correction=flattening/16*cosSquaredAlpha*(4+flattening*(4-3*cosSquaredAlpha))
            val previous=lambda
            lambda=longitudeArc+(1-correction)*flattening*sinAlpha*(sigma+correction*sinSigma*(cosDoubleSigma+correction*cosSigma*(-1+2*cosDoubleSigma*cosDoubleSigma)))
            if(abs(lambda-previous)<1e-12) break
        }
        val squared=cosSquaredAlpha*(major*major-minor*minor)/(minor*minor)
        val seriesA=1+squared/16384*(4096+squared*(-768+squared*(320-175*squared)))
        val seriesB=squared/1024*(256+squared*(-128+squared*(74-47*squared)))
        val adjustment=seriesB*sinSigma*(cosDoubleSigma+seriesB/4*(cosSigma*(-1+2*cosDoubleSigma*cosDoubleSigma)-seriesB/6*cosDoubleSigma*(-3+4*sinSigma*sinSigma)*(-3+4*cosDoubleSigma*cosDoubleSigma)))
        val distance=minor*seriesA*(sigma-adjustment)
        if(distance.isFinite() && distance>=0.0) return distance
        val haversine=sin(latitudeArc/2).pow(2)+cos(phi1)*cos(phi2)*sin(longitudeArc/2).pow(2)
        return major*2*asin(sqrt(haversine.coerceIn(0.0,1.0)))
    }
}
