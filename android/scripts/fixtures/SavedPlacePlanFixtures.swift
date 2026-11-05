import Foundation
let inputs=try JSONSerialization.jsonObject(with:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))) as! [[String:Any]]
var results:[[String:Any]]=[]
func imported(_ value:[String:Any])->ImportedSavedPlace {
    ImportedSavedPlace(id:UUID(uuidString:value["id"] as! String)!,name:value["name"] as! String,address:value["address"] as? String,
        latitude:value["latitude"] as! Double,longitude:value["longitude"] as! Double,radiusMeters:value["radius"] as! Double)
}
for input in inputs {
    let existing=(input["existing"] as! [[String:Any]]).map {SavedPlaceDraft(imported($0))}
    let incoming=(input["incoming"] as! [[String:Any]]).map(imported)
    let plan=SavedPlaceImportPlan(places:incoming,existing:existing,policy:SavedPlaceDuplicatePolicy(rawValue:input["policy"] as! String)!)
    let all=existing+incoming.map {SavedPlaceDraft($0)}
    var result=input
    result["distances"]=all.map {a in all.map {a.distance(to:$0)}}
    result["nameKeys"]=Dictionary(all.map {($0.name,$0.matchingName)},uniquingKeysWith:{$1})
    result["entries"]=plan.entries.map {entry in
        let action=switch entry.action {case .add:"add";case .update:"update";case .skip:"skip"}
        return ["targetId":entry.targetID.uuidString.lowercased(),"action":action]
    }
    result["summary"]=["added":plan.summary.added,"updated":plan.summary.updated,"skipped":plan.summary.skipped]
    results.append(result)
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys,.prettyPrinted,.withoutEscapingSlashes]))
