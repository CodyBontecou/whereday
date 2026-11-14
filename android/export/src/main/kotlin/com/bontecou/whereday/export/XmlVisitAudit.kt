package com.bontecou.whereday.export

import com.bontecou.whereday.domain.Visit
import java.math.BigDecimal

/** Order and field switches follow the current Swift VisitAuditMetadata wire contract. */
internal object XmlVisitAudit {
    fun fields(visit:Visit,options:ExportOptions):List<Pair<String,String>> = VisitAudit.values(visit,options).map {(key,value)->
        key to if(value is Double) swiftNumber(value) else value.toString()
    }
    fun swiftNumber(value:Double):String {
        require(value.isFinite()) {"XML visit metadata must be finite"}
        if(value==0.0) return if(java.lang.Double.doubleToRawLongBits(value)<0) "-0.0" else "0.0"
        val decimal=BigDecimal.valueOf(value).stripTrailingZeros()
        if(kotlin.math.abs(value)>=1e16 || kotlin.math.abs(value)<1e-4) {
            val exponent=decimal.precision()-decimal.scale()-1
            val mantissa=decimal.movePointLeft(exponent).stripTrailingZeros().toPlainString()
            return mantissa+"e"+(if(exponent>=0) "+" else "-")+kotlin.math.abs(exponent).toString().padStart(2,'0')
        }
        val text=decimal.toPlainString();return if('.' in text) text else "$text.0"
    }
}
