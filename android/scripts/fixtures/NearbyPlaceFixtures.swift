import Foundation
import CoreLocation
import MapKit

struct Row {
    let name:String?;let lat:Double;let lon:Double;let street:String?
    init(_ name:String?,_ lat:Double=0,_ lon:Double=0,_ street:String?=nil) {self.name=name;self.lat=lat;self.lon=lon;self.street=street}
    func item()->MKMapItem {
        let place=MKPlacemark(coordinate:CLLocationCoordinate2D(latitude:lat,longitude:lon),addressDictionary:street.map {["Street":$0]})
        let item=MKMapItem(placemark:place);item.name=name;return item
    }
}
var cases:[[String:Any]]=[]
func add(_ name:String,_ rows:[Row],_ latitude:Double=0,_ longitude:Double=0,_ radius:Double=1000) {
    let items=rows.map {$0.item()};let origin=CLLocation(latitude:latitude,longitude:longitude)
    let results=Reference.makeSuggestions(from:items,origin:origin,radius:radius)
    cases.append(["name":name,"origin":[latitude,longitude],"radius":radius,
        "records":items.map {["name":$0.name as Any? ?? NSNull(),"address":MapItemPlaceFormatter.formattedAddress(for:$0) as Any? ?? NSNull(),"latitude":$0.placemark.coordinate.latitude,"longitude":$0.placemark.coordinate.longitude,"distance":origin.distance(from:CLLocation(latitude:$0.placemark.coordinate.latitude,longitude:$0.placemark.coordinate.longitude))]},
        "results":results.map {["id":$0.id,"name":$0.name,"address":$0.address as Any? ?? NSNull(),"latitude":$0.coordinate.latitude,"longitude":$0.coordinate.longitude,"distance":$0.distanceMeters]}])
}
add("empty",[])
add("trim-and-reserved-names",[Row("  Cafe  ",0.001,0,"10 Main Street"),Row("none"),Row(" UNKNOWN "),Row("Current Location"),Row(" ")])
add("first-eligible-name-before-sort",[Row("Chain",0.008),Row("CHAIN",0.001),Row("Different",0.004)])
add("outside-name-does-not-suppress-inside",[Row("Chain",0.02),Row("CHAIN",0.001)])
add("near-before-far",[Row("Far",0.008),Row("Near",0.001),Row("Middle",0.004)])
add("same-distance-name-tie",[Row("Zoo"),Row("apple"),Row("Banana")])
add("radius-twenty-percent-slack",[Row("Within slack",0.0105),Row("Outside slack",0.011),Row("At origin")])
add("unicode-equivalent-names",[Row("Café",0.001),Row("Cafe\u{301}",0.002),Row("CAFÉ",0.003),Row("Cafe",0.004)])
add("id-away-from-zero-rounding",[Row("Negative",-0.000005,-0.000015),Row("Positive",0.000005,0.000015)])
add("date-line",[Row("Across",0,-179.999),Row("Same side",0,179.998)],0,179.999)
add("near-pole",[Row("East",89.999,90),Row("West",89.999,-90)],89.999,0)
add("small-radius",[Row("Very near",0.0001),Row("Too far",0.001)],0,0,25)
add("missing-address",[Row("No street",0.001),Row("Street",0.002,0,"2 Other Road")])
let output:[String:Any]=["maximumRadius":MKLocalPointsOfInterestRequest.maxRadius,"cases":cases]
print(String(data:try! JSONSerialization.data(withJSONObject:output,options:[.sortedKeys]),encoding:.utf8)!)
