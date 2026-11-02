import Foundation
let formatter=ISO8601DateFormatter()
let inputs=try JSONSerialization.jsonObject(with:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))) as! [[String:Any]]
var results:[[String:Any]]=[]
for input in inputs {
    NSTimeZone.default=TimeZone(identifier:input["zone"] as! String)!
    let format=ExportFormat.allCases.first {$0.token==input["format"] as! String}!
    var result=input
    result["expected"]=FilenameTemplate.resolve(pattern:input["pattern"] as! String,dataKind:ExportOptions.DataKind(rawValue:input["kind"] as! String)!,
        format:format,date:formatter.date(from:input["now"] as! String)!,title:input["title"] as? String)
    result["dayStable"]=FilenameTemplate.dayStablePattern(from:input["pattern"] as! String)
    results.append(result)
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys,.prettyPrinted,.withoutEscapingSlashes]))
