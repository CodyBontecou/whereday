import Foundation
import SwiftData
import Darwin

private struct AdmissionFailure:Error {let message:String}
@main private struct TimelineAdmissionAcceptance {
    @MainActor static func main() async {
        do {let report=try await verify();FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys]))}
        catch {FileHandle.standardError.write(Data("FAIL: \(error)\n".utf8));exit(EXIT_FAILURE)}
    }
    @MainActor private static func verify() async throws -> [[String:Any]] {
        var output=[[String:Any]]()
        let at=Date(timeIntervalSince1970:1577836800)
        let cases:[(String,Bool,Bool)]=[("googleDevice",true,false),("googleSemantic",true,false),("googleRaw",false,false),("wheredayVisit",true,false),("wheredayPoint",false,false),("googleDevice",true,true),("googleRaw",false,true)]
        for (source,visits,late) in cases {
            let container=try ModelContainer(for:Visit.self,LocationPoint.self,configurations:ModelConfiguration(isStoredInMemoryOnly:true))
            let step=visits ? 0.00072 : 0.000072
            let existingVisit=Visit(latitude:18-step,longitude:-66,arrivedAt:at,departedAt:at.addingTimeInterval(3600),locationName:"Keep existing",notes:"Unchanged")
            let existingPoint=LocationPoint(latitude:18-step,longitude:-66,timestamp:at,horizontalAccuracy:99)
            func seed() throws {if visits {container.mainContext.insert(existingVisit)} else {container.mainContext.insert(existingPoint)};try container.mainContext.save()}
            if !late {try seed()}
            let rows:[[String:Any]]=(0...2).map {index in
                let latitude=18+Double(index)*step
                switch source {
                case "googleDevice":return ["startTime":"2020-01-01T00:00:00Z","endTime":"2020-01-01T01:00:00Z","visit":["topCandidate":["placeLocation":"geo:\(latitude),-66"]]]
                case "googleSemantic":return ["placeVisit":["location":["latLng":"geo:\(latitude),-66"],"duration":["startTimestamp":"2020-01-01T00:00:00Z","endTimestamp":"2020-01-01T01:00:00Z"]]]
                case "googleRaw":return ["latLng":"geo:\(latitude),-66","timestamp":"2020-01-01T00:00:00Z","accuracy":5]
                case "wheredayVisit":return ["latitude":latitude,"longitude":-66,"arrivedAt":"2020-01-01T00:00:00Z","departedAt":"2020-01-01T01:00:00Z"]
                default:return ["latitude":latitude,"longitude":-66,"timestamp":"2020-01-01T00:00:00Z","horizontalAccuracy":5]
                }
            }
            let root:Any
            switch source {case "googleDevice":root=rows;case "googleSemantic":root=["timelineObjects":rows];case "googleRaw":root=["locations":rows];case "wheredayVisit":root=["visits":rows];default:root=["points":rows]}
            let service=TimelineImportService(container:container)
            let preview=try await service.preview(data:JSONSerialization.data(withJSONObject:root),fileName:"history.json")
            let previewCount=visits ? preview.newVisitCount : preview.newPointCount
            let previewDuplicates=visits ? preview.duplicateVisitCount : preview.duplicatePointCount
            guard previewCount==(late ? 2:1),previewDuplicates==(late ? 1:2) else {throw AdmissionFailure(message:"Unexpected preview for \(source), late=\(late)")}
            let before=ModelContext(container)
            let beforeCount=try (visits ? before.fetchCount(FetchDescriptor<Visit>()) : before.fetchCount(FetchDescriptor<LocationPoint>()))
            guard beforeCount==(late ? 0:1) else {throw AdmissionFailure(message:"Preview wrote history")}
            if late {try seed()}
            let committed=try await service.commit(preview,options:ImportOptions())
            let context=ModelContext(container)
            let latitude:Double
            if visits {
                let values=try context.fetch(FetchDescriptor<Visit>())
                let inserted=values.filter {$0.id != existingVisit.id}
                guard inserted.count==1,values.first(where:{$0.id==existingVisit.id})?.notes=="Unchanged" else {throw AdmissionFailure(message:"Visit ownership changed")}
                latitude=inserted[0].latitude
            } else {
                let values=try context.fetch(FetchDescriptor<LocationPoint>())
                let inserted=values.filter {$0.id != existingPoint.id}
                guard inserted.count==1,values.first(where:{$0.id==existingPoint.id})?.horizontalAccuracy==99 else {throw AdmissionFailure(message:"Point ownership changed")}
                latitude=inserted[0].latitude
            }
            guard abs(latitude-(18+step))<0.0000000001 else {throw AdmissionFailure(message:"Wrong admitted row for \(source): \(latitude)")}
            guard (visits ? committed.visitCount : committed.pointCount)==1,(visits ? committed.duplicateVisitCount : committed.duplicatePointCount)==2 else {throw AdmissionFailure(message:"Incorrect commit counts")}
            output.append(["source":source,"lateJournalInsert":late,"previewNew":previewCount,"previewDuplicates":previewDuplicates,"committedNew":1,"committedDuplicates":2,"admittedLatitude":latitude,"existingPreserved":true])
        }
        return output
    }
}
