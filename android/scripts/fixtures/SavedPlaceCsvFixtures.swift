import Foundation
let inputs=try JSONSerialization.jsonObject(with:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))) as! [[String:Any]]
var results:[[String:Any]]=[]
for input in inputs {
    var result=input
    do {
        let preview=try SavedPlaceImportService.parse(data:Data((input["csv"] as! String).utf8))
        result["rows"]=preview.rows.map {row -> [String:Any] in
            var value:[String:Any]=["rowNumber":row.rowNumber,"name":row.name,"errors":row.errors]
            value["place"]=row.place.map {p in ["name":p.name,"address":p.address as Any? ?? NSNull(),"latitude":p.latitude,"longitude":p.longitude,"radius":p.radiusMeters] as [String:Any]} as Any? ?? NSNull()
            return value
        }
    } catch {result["error"]=error.localizedDescription}
    results.append(result)
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys,.prettyPrinted,.withoutEscapingSlashes]))
