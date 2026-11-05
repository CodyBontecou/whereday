import Foundation
struct RawValue {let rawValue:String}
struct ExportOptions {
    var includeVisitCoordinates=true,includeVisitDuration=true,includeVisitLocationName=true,includeVisitAddress=true,includeVisitNotes=true
    var includePointAltitude=true,includePointSpeed=true,includePointAccuracy=true,includePointOutlierFlag=true
}
struct Visit {
    var arrivedAt:Date
    var latitude=0.0,longitude=0.0
    var departedAt:Date?=nil,locationName:String?=nil,customName:String?=nil,address:String?=nil,notes:String?=nil
    var source=RawValue(rawValue:"automatic"),confirmationStatus=RawValue(rawValue:"unconfirmed")
    var confirmedAt:Date?=nil,updatedAt:Date?=nil
    var originalLatitude:Double?=nil,originalLongitude:Double?=nil,originalLocationName:String?=nil,originalAddress:String?=nil
    var detectedLatitude:Double?=nil,detectedLongitude:Double?=nil,detectedLocationName:String?=nil,detectedAddress:String?=nil
    var placeSource:RawValue?=nil,placeDistanceMeters:Double?=nil
    // ACTUAL_VISIT_DISPLAY
    // ACTUAL_VISIT_DURATION
}
let iso=ISO8601DateFormatter();iso.formatOptions=[.withInternetDateTime,.withFractionalSeconds]
func date(_ value:Any?)->Date? {(value as? String).flatMap {iso.date(from:$0)}}
func number(_ row:[String:Any],_ key:String)->Double? {(row[key] as? NSNumber)?.doubleValue}
let inputs=try JSONSerialization.jsonObject(with:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))) as! [[String:Any]]
NSTimeZone.default=TimeZone(identifier:inputs.first?["zone"] as? String ?? "America/Puerto_Rico")!
var results:[[String:Any]]=[]
for input in inputs {
    let start=date(input["start"])!,end=date(input["end"]),now=date(input["now"])!
    let inferred=input["inferred"] as! Bool
    let stored=inferred ? nil : RecordingSession(startedAt:start,endedAt:end,customName:input["title"] as? String,notes:input["notes"] as? String)
    let points=(input["points"] as! [[String:Any]]).map {p in
        LocationPoint(latitude:number(p,"lat")!,longitude:number(p,"lon")!,timestamp:date(p["time"])!,altitude:number(p,"altitude"),speed:number(p,"speed"),horizontalAccuracy:number(p,"accuracy") ?? 5,isOutlier:p["outlier"] as! Bool)
    }
    let summary=RecordingSessionSummary(id:input["id"] as! String,storedSession:stored,sequenceNumber:input["sequence"] as! Int,startedAt:start,endedAt:end,points:points,isInferred:inferred,isActive:!inferred && end==nil,now:now)
    let visits=(input["visits"] as! [Any]).map {value -> Visit in
        guard let row=value as? [String:Any] else {return Visit(arrivedAt:date(value)!)}
        var v=Visit(arrivedAt:date(row["arrivedAt"])!);v.departedAt=date(row["departedAt"])
        v.latitude=number(row,"latitude") ?? 0;v.longitude=number(row,"longitude") ?? 0
        v.locationName=row["name"] as? String;v.address=row["address"] as? String;v.notes=row["notes"] as? String
        v.source=RawValue(rawValue:row["source"] as? String ?? "automatic");v.confirmationStatus=RawValue(rawValue:row["confirmation"] as? String ?? "unconfirmed")
        v.confirmedAt=date(row["confirmedAt"]);v.updatedAt=date(row["updatedAt"])
        v.originalLatitude=number(row,"originalLatitude");v.originalLongitude=number(row,"originalLongitude");v.originalLocationName=row["originalName"] as? String;v.originalAddress=row["originalAddress"] as? String
        v.detectedLatitude=number(row,"detectedLatitude");v.detectedLongitude=number(row,"detectedLongitude");v.detectedLocationName=row["detectedName"] as? String;v.detectedAddress=row["detectedAddress"] as? String
        v.placeSource=(row["placeSource"] as? String).map {RawValue(rawValue:$0)};v.placeDistanceMeters=number(row,"placeDistanceMeters")
        return v
    }
    let flags=input["options"] as? [String:Bool] ?? [:];var options=ExportOptions()
    options.includeVisitCoordinates=flags["visitCoordinates"] ?? true;options.includeVisitDuration=flags["visitDuration"] ?? true;options.includeVisitLocationName=flags["visitName"] ?? true
    options.includeVisitAddress=flags["visitAddress"] ?? true;options.includeVisitNotes=flags["visitNotes"] ?? true
    options.includePointAltitude=flags["pointAltitude"] ?? true;options.includePointSpeed=flags["pointSpeed"] ?? true;options.includePointAccuracy=flags["pointAccuracy"] ?? true;options.includePointOutlierFlag=flags["pointOutlier"] ?? true
    let earlier=RecordingSessionSummary(id:"earlier",storedSession:nil,sequenceNumber:1,startedAt:start.addingTimeInterval(-3600),endedAt:start.addingTimeInterval(-3500),points:[],isInferred:true,isActive:false,now:now)
    let outings=(input["shape"] as? String)=="empty" ? [] : (input["shape"] as? String)=="pair" ? [summary,earlier] : [summary]
    var result=input;result["locale"]=Locale.current.identifier;result["zone"]=NSTimeZone.default.identifier
    result["expected"]=String(data:ExportService.exportOutingsToMarkdown(outings:outings,visits:visits,options:options),encoding:.utf8)!
    results.append(result)
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys]))
