import XCTest
import StoreKitTest

/// Not a regression test: a QA setup step for Argent e2e tests that buy through StoreKit.
///
/// Run it with `scripts/qa-storekit-session.sh <simulator udid>`. It points the simulator's StoreKit at
/// the local `IsoMe.storekit` catalog for com.bontecou.isome, with no purchases and purchase dialogs
/// off, and leaves that session in place so a later app launch (with `--use-storekit`) buys from the
/// local catalog instead of the App Store sandbox. Skipped unless `ISOME_QA_STOREKIT_SESSION=1`.
final class StoreKitQASessionTests: XCTestCase {
    func testPrepareLocalStoreKitSessionForE2E() throws {
        guard ProcessInfo.processInfo.environment["ISOME_QA_STOREKIT_SESSION"] == "1" else {
            throw XCTSkip("QA setup only; run scripts/qa-storekit-session.sh")
        }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "IsoMe", withExtension: "storekit"),
                                "IsoMe.storekit is missing from the test bundle")
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.clearTransactions()
        session.disableDialogs = true
        session.askToBuyEnabled = false
        session.timeRate = .realTime
    }
}
