import Foundation
import CoreLocation
struct LocationPoint {
    let latitude:Double,longitude:Double
    // ACTUAL_DISTANCE
}
enum RecordingSessionBuilder {
    // ACTUAL_TOTAL_DISTANCE
}
let input=try JSONSerialization.jsonObject(with:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))) as! [[String:Any]]
let results=input.map {row -> [String:Any] in
    let points=(row["points"] as! [[Double]]).map {LocationPoint(latitude:$0[0],longitude:$0[1])}
    var result=row
    result["expected"]=RecordingSessionBuilder.totalDistance(in:points)
    return result
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys]))
