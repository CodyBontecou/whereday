import Foundation
let formatter=ISO8601DateFormatter()
formatter.formatOptions=[.withInternetDateTime,.withFractionalSeconds]
func date(_ value:Any?) -> Date? { (value as? String).flatMap {formatter.date(from:$0)} }
let inputs=try JSONSerialization.jsonObject(with:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))) as! [[String:Any]]
var results:[[String:Any]]=[]
for input in inputs {
    let start=date(input["start"])!,end=date(input["end"]),now=date(input["now"])!
    let inferred=input["inferred"] as! Bool
    let stored=inferred ? nil : RecordingSession(startedAt:start,endedAt:end,customName:input["title"] as? String,notes:input["notes"] as? String)
    let points=(input["points"] as! [[String:Any]]).map {p in
        LocationPoint(latitude:p["lat"] as! Double,longitude:p["lon"] as! Double,timestamp:date(p["time"])!,horizontalAccuracy:5,isOutlier:p["outlier"] as! Bool)
    }
    let summary=RecordingSessionSummary(id:input["id"] as! String,storedSession:stored,sequenceNumber:input["sequence"] as! Int,
        startedAt:start,endedAt:end,points:points,isInferred:inferred,isActive:!inferred && end==nil,now:now)
    let visits=(input["visits"] as! [String]).map {Visit(arrivedAt:date($0)!)}
    var json=try JSONSerialization.jsonObject(with:ExportService.exportOutingsToJSON(outings:[summary],visits:visits)) as! [String:Any]
    json.removeValue(forKey:"exportDate") // The production renderer reads Date() for this metadata field.
    var result=input
    result["expectedJSON"]=json
    result["expectedCSV"]=String(data:ExportService.exportOutingsToCSV(outings:[summary],visits:visits),encoding:.utf8)!
    let earlier=RecordingSessionSummary(id:"earlier",storedSession:nil,sequenceNumber:1,startedAt:start.addingTimeInterval(-3600),
        endedAt:start.addingTimeInterval(-3500),points:[],isInferred:true,isActive:false,now:now)
    var pairJSON=try JSONSerialization.jsonObject(with:ExportService.exportOutingsToJSON(outings:[summary,earlier],visits:visits)) as! [String:Any]
    pairJSON.removeValue(forKey:"exportDate")
    result["expectedPairJSON"]=pairJSON
    result["expectedPairCSV"]=String(data:ExportService.exportOutingsToCSV(outings:[summary,earlier],visits:visits),encoding:.utf8)!
    results.append(result)
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys,.prettyPrinted,.withoutEscapingSlashes]))
