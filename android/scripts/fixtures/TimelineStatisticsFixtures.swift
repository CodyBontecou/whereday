import Foundation
let raw=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [String:Any]
func date(_ value:Any)->Date {Date(timeIntervalSince1970:(value as! NSNumber).doubleValue/1000)}
let points=(raw["points"] as! [[String:Any]]).map {p in
 LocationPoint(id:UUID(uuidString:p["id"] as! String)!,latitude:p["lat"] as! Double,longitude:p["lon"] as! Double,timestamp:date(p["time"]!),horizontalAccuracy:5,isOutlier:p["outlier"] as! Bool)
}
let records=(raw["sessions"] as! [[String:Any]]).map {s in
 RecordingSession(id:UUID(uuidString:s["id"] as! String)!,startedAt:date(s["start"]!),endedAt:s["end"].flatMap {$0 is NSNull ? nil : date($0)})
}
let config=RecordingSessionInferenceConfiguration(includesInferredSessions:raw["enabled"] as! Bool,gapThreshold:(raw["gapSeconds"] as! NSNumber).doubleValue,minimumDuration:(raw["minimumSeconds"] as! NSNumber).doubleValue,minimumPointCount:raw["minimumPoints"] as! Int)
let active=raw["activeStart"].flatMap {$0 is NSNull ? nil : date($0)}
let all=RecordingSessionBuilder.summaries(storedSessions:records,points:points,activeTrackingStart:active,inferenceConfiguration:config,now:date(raw["now"]!))
let host=Host(all,date(raw["selected"]!))
host.allVisits=(raw["visits"] as? [[String:Any]] ?? []).map {v in Visit(id:v["id"] as! String,arrivedAt:date(v["start"]!),departedAt:v["end"].flatMap {$0 is NSNull ? nil : date($0)})}
func millis(_ value:Date)->Int64 {Int64((value.timeIntervalSince1970*1000).rounded())}
func row(_ s:RecordingSessionSummary)->[String:Any] {
 let model=FocusModel();model.focusMap(on:s)
 let firstID=model.mapFocusRequest!.id
 let map=FocusMap(model);let initial=map.apply();let repeated=map.apply()
 model.focusMap(on:s);let fresh=map.apply()
 let focus:[String:Any]=["from":millis(model.mapDateRange!.lowerBound),"through":millis(model.mapDateRange!.upperBound),"pointFrom":millis(model.pointRange!.lowerBound),"pointThrough":millis(model.pointRange!.upperBound),"photoFrom":millis(model.photoRange!.lowerBound),"photoThrough":millis(model.photoRange!.upperBound),"initialApplied":initial,"repeatedApplied":repeated,"freshApplied":fresh,"distinctRequest":firstID != model.mapFocusRequest!.id,"fits":map.fits]
 return ["focus":focus,"id":s.id,"storedID":s.storedSession?.id.uuidString as Any? ?? NSNull(),"sequence":s.sequenceNumber,"start":millis(s.startedAt),"end":s.endedAt.map(millis) as Any? ?? NSNull(),"effectiveEnd":millis(s.effectiveEndDate),"active":s.isActive,"inferred":s.isInferred,"count":s.pointCount,"outliers":s.outlierCount,"distance":s.distanceMeters,"duration":s.duration,"average":s.averageSpeedMetersPerSecond as Any? ?? NSNull(),"visits":host.visitsOverlappingDateRange(s.dateRange,referenceDate:date(raw["now"]!)).map(\.id),"points":s.points.map {$0.id.uuidString}]
}
func exported(_ summaries:[RecordingSessionSummary]) throws -> [String:Any] {
 var json=try JSONSerialization.jsonObject(with:ExportService.exportOutingsToJSON(outings:summaries,visits:host.allVisits)) as! [String:Any]
 json.removeValue(forKey:"exportDate")
 return ["json":json,"csv":String(data:ExportService.exportOutingsToCSV(outings:summaries,visits:host.allVisits),encoding:.utf8)!]
}
let output:[String:Any]=try ["allExport":exported(all),"selectedExport":exported(host.selected),"all":all.map(row),"selected":host.selected.map(row),"distance":host.distance,"visits":host.visitsOverlappingDateRange(host.bounds,referenceDate:date(raw["now"]!)).map(\.id),"from":millis(host.bounds.lowerBound),"until":millis(host.bounds.upperBound)]
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:output,options:[.sortedKeys]))
