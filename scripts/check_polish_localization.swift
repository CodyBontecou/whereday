#!/usr/bin/env swift
// Run after building: swift scripts/check_polish_localization.swift /path/to/IsoMe.app
import Foundation

guard CommandLine.arguments.count == 2,
      let bundle = Bundle(path: CommandLine.arguments[1] + "/pl.lproj") else {
    fatalError("Pass the path to a built IsoMe.app containing pl.lproj")
}

let locale = Locale(identifier: "pl_PL")
let counts: [Int64] = [0, 1, 2, 4, 5, 12, 14, 21, 22, 25, 101, 102]
let cases: [(String, [String])] = [
    ("%lld photos", ["zdjęć", "zdjęcie", "zdjęcia", "zdjęcia", "zdjęć", "zdjęć", "zdjęć", "zdjęć", "zdjęcia", "zdjęć", "zdjęć", "zdjęcia"]),
    ("%lld points", ["punktów", "punkt", "punkty", "punkty", "punktów", "punktów", "punktów", "punktów", "punkty", "punktów", "punktów", "punkty"]),
    ("%lld minutes", ["minut", "minuta", "minuty", "minuty", "minut", "minut", "minut", "minut", "minuty", "minut", "minut", "minuty"]),
    ("%lld countries", ["krajów", "kraj", "kraje", "kraje", "krajów", "krajów", "krajów", "krajów", "kraje", "krajów", "krajów", "kraje"]),
    ("%lld days", ["dni", "dzień", "dni", "dni", "dni", "dni", "dni", "dni", "dni", "dni", "dni", "dni"]),
    ("%lld visits", ["wizyt", "wizyta", "wizyty", "wizyty", "wizyt", "wizyt", "wizyt", "wizyt", "wizyty", "wizyt", "wizyt", "wizyty"]),
    ("%lld path points", ["punktów trasy", "punkt trasy", "punkty trasy", "punkty trasy", "punktów trasy", "punktów trasy", "punktów trasy", "punktów trasy", "punkty trasy", "punktów trasy", "punktów trasy", "punkty trasy"]),
    ("%lld matched photos", ["dopasowanych zdjęć", "dopasowane zdjęcie", "dopasowane zdjęcia", "dopasowane zdjęcia", "dopasowanych zdjęć", "dopasowanych zdjęć", "dopasowanych zdjęć", "dopasowanych zdjęć", "dopasowane zdjęcia", "dopasowanych zdjęć", "dopasowanych zdjęć", "dopasowane zdjęcia"]),
    ("%lld photos taken here", ["zdjęć zrobionych tutaj", "zdjęcie zrobione tutaj", "zdjęcia zrobione tutaj", "zdjęcia zrobione tutaj", "zdjęć zrobionych tutaj", "zdjęć zrobionych tutaj", "zdjęć zrobionych tutaj", "zdjęć zrobionych tutaj", "zdjęcia zrobione tutaj", "zdjęć zrobionych tutaj", "zdjęć zrobionych tutaj", "zdjęcia zrobione tutaj"]),
]

var checked = 0
for (key, nouns) in cases {
    let format = bundle.localizedString(forKey: key, value: nil, table: nil)
    for (count, noun) in zip(counts, nouns) {
        let actual = String(format: format, locale: locale, arguments: [count])
        let expected = "\(count) \(noun)"
        precondition(actual == expected, "\(key), count \(count): expected \(expected), got \(actual)")
        checked += 1
    }
}

for (key, expected) in [("Save", "Zapisz"), ("Country Days", "Dni w krajach"), ("Family Lifetime", "Dożywotni rodzinny"), ("TEST CONNECTION", "SPRAWDŹ POŁĄCZENIE"), ("SEND NOW", "WYŚLIJ TERAZ"), ("5 min", "5 min"), ("5m", "5 m"), ("Tracking is off.", "Śledzenie jest wyłączone."), ("CONNECT PHOTOS", "POŁĄCZ ZDJĘCIA"), ("Auto-sync photo moments", "Synchronizuj zdjęcia"), ("Optional", "Opcjonalnie")] {
    let actual = bundle.localizedString(forKey: key, value: nil, table: nil)
    precondition(actual == expected, "\(key): expected \(expected), got \(actual)")
    checked += 1
}
let outing = bundle.localizedString(forKey: "Outing %lld", value: nil, table: nil)
precondition(String(format: outing, locale: locale, arguments: [Int64(7)]) == "Podróż 7")
checked += 1

// The map now uses one count-based message for every number, including 1.
// Preserve English singular wording as well as the Polish plural forms above.
guard let english = Bundle(path: CommandLine.arguments[1] + "/en.lproj") else {
    fatalError("The built app must retain English resources")
}
for (key, singular) in [("%lld visits", "1 visit"), ("%lld path points", "1 path point"), ("%lld matched photos", "1 matched photo"), ("%lld photos taken here", "1 photo taken here")] {
    let format = english.localizedString(forKey: key, value: nil, table: nil)
    for count: Int64 in [0, 1, 2] {
        let expected = count == 1 ? singular : key.replacingOccurrences(of: "%lld", with: String(count))
        let actual = String(format: format, locale: Locale(identifier: "en_US"), arguments: [count])
        precondition(actual == expected, "English \(key), count \(count): expected \(expected), got \(actual)")
        checked += 1
    }
}
print("PASS: \(checked) compiled localization checks (Polish and English regression)")
