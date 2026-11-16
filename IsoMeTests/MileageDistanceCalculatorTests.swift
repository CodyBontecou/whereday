import XCTest
@testable import IsoMe

final class MileageDistanceCalculatorTests: XCTestCase {
    private func sample(_ seconds: Double, _ longitude: Double, accuracy: Double = 5,
                        outlier: Bool = false, latitude: Double = 0) -> MileageDistanceSample {
        MileageDistanceSample(timestamp: Date(timeIntervalSince1970: seconds), latitude: latitude,
                              longitude: longitude, horizontalAccuracy: accuracy, isOutlier: outlier)
    }

    func testEmptyAndSingleFixHaveZeroDistance() throws {
        XCTAssertEqual(try MileageDistanceCalculator.calculate([]).filteredDistanceMeters, 0)
        XCTAssertEqual(try MileageDistanceCalculator.calculate([sample(0, 0)]).rawDistanceMeters, 0)
    }

    func testNormalRouteSortedByTime() throws {
        let result = try MileageDistanceCalculator.calculate([sample(20, 0.002), sample(0, 0), sample(10, 0.001)])
        XCTAssertEqual(result.filteredDistanceMeters, 222.39, accuracy: 0.1)
        XCTAssertEqual(result.filteredDistanceMeters, result.rawDistanceMeters)
        XCTAssertEqual(result.rejectedSampleCount, 0)
    }

    func testImpossibleSpeedSpikeDoesNotPoisonNextFix() throws {
        let result = try MileageDistanceCalculator.calculate([
            sample(0, 0), sample(10, 1), sample(20, 0.002), sample(30, 0.003)
        ])
        XCTAssertEqual(result.filteredDistanceMeters, 333.585, accuracy: 0.1)
        XCTAssertGreaterThan(result.rawDistanceMeters, 200_000)
        XCTAssertEqual(result.rejectedSampleCount, 1)
    }

    func testExplicitOutlierCanNeverBecomeDistanceFallback() throws {
        let result = try MileageDistanceCalculator.calculate([sample(0, 0, outlier: true), sample(10, 0.001, outlier: true)])
        XCTAssertEqual(result.filteredDistanceMeters, 0)
        XCTAssertGreaterThan(result.rawDistanceMeters, 100)
        XCTAssertEqual(result.rejectedSampleCount, 2)
    }

    func testPoorAccuracyBreaksContinuity() throws {
        let result = try MileageDistanceCalculator.calculate([
            sample(0, 0), sample(10, 0.001, accuracy: 101), sample(20, 0.002), sample(30, 0.003)
        ])
        XCTAssertEqual(result.filteredDistanceMeters, 111.195, accuracy: 0.1)
        XCTAssertEqual(result.rejectedSampleCount, 1)
    }

    func testNegativeAndNonfiniteAccuracyRejected() throws {
        for accuracy in [-1, Double.nan, Double.infinity] {
            let result = try MileageDistanceCalculator.calculate([sample(0, 0, accuracy: accuracy), sample(10, 0.001)])
            XCTAssertEqual(result.filteredDistanceMeters, 0)
            XCTAssertEqual(result.rejectedSampleCount, 1)
        }
    }

    func testLongGapNotBridgedButRouteResumes() throws {
        let result = try MileageDistanceCalculator.calculate([
            sample(0, 0), sample(121, 0.01), sample(131, 0.011)
        ])
        XCTAssertEqual(result.filteredDistanceMeters, 111.195, accuracy: 0.1)
        XCTAssertEqual(result.gapCount, 1)
        XCTAssertGreaterThan(result.rawDistanceMeters, 1_200)
    }

    func testGapAfterRejectedOutlierStillBreaksContinuity() throws {
        let result = try MileageDistanceCalculator.calculate([
            sample(0, 0), sample(100, 1, outlier: true), sample(121, 0.01)
        ])
        XCTAssertEqual(result.filteredDistanceMeters, 0)
        XCTAssertEqual(result.gapCount, 1)
    }

    func testDuplicateTimestampCannotIntroduceSpeedOrDistance() throws {
        let result = try MileageDistanceCalculator.calculate([sample(0, 0), sample(0, 1), sample(10, 0.001)])
        XCTAssertEqual(result.filteredDistanceMeters, 111.195, accuracy: 0.1)
        XCTAssertEqual(result.rejectedSampleCount, 1)
    }

    func testConflictingDuplicateTimestampCannotChangeRawOrFilteredDistance() throws {
        // The first fix at t=0 wins, whether its duplicate is nearby or a distant spike.
        for duplicateLongitude in [0.001, 1.0] {
            let result = try MileageDistanceCalculator.calculate([
                sample(0, 0), sample(0, duplicateLongitude), sample(10, 0.002)
            ])
            XCTAssertEqual(result.rawDistanceMeters, 222.39, accuracy: 0.1,
                           "Duplicate longitude: \(duplicateLongitude)")
            XCTAssertEqual(result.filteredDistanceMeters, 222.39, accuracy: 0.1)
            XCTAssertEqual(result.rejectedSampleCount, 1)
        }
    }

    func testInvalidCoordinatesBreakBothRawAndFilteredContinuity() throws {
        for point in [sample(10, 181), sample(10, 0, latitude: 91), sample(10, .nan)] {
            let result = try MileageDistanceCalculator.calculate([sample(0, 0), point, sample(20, 0.002)])
            XCTAssertEqual(result.rawDistanceMeters, 0)
            XCTAssertEqual(result.filteredDistanceMeters, 0)
            XCTAssertEqual(result.rejectedSampleCount, 1)
        }
    }

    func testNonfiniteTimestampRejectedBeforeSorting() {
        let invalid = MileageDistanceSample(timestamp: Date(timeIntervalSince1970: .nan), latitude: 0,
                                            longitude: 0, horizontalAccuracy: 5)
        XCTAssertThrowsError(try MileageDistanceCalculator.calculate([sample(0, 0), invalid]))
    }

    func testAntimeridianUsesShortArc() throws {
        let result = try MileageDistanceCalculator.calculate([sample(0, 179.999), sample(10, -179.999)])
        XCTAssertEqual(result.filteredDistanceMeters, 222.39, accuracy: 0.1)
    }

    func testThresholdsAreInclusiveAndInvalidConfigurationRejected() throws {
        var config = MileageDistanceCalculator.Configuration()
        config.maximumGapSeconds = 10
        let result = try MileageDistanceCalculator.calculate([sample(0, 0, accuracy: 100), sample(10, 0.001)], configuration: config)
        XCTAssertGreaterThan(result.filteredDistanceMeters, 100)
        config.maximumAccuracyMeters = .nan
        XCTAssertThrowsError(try MileageDistanceCalculator.calculate([], configuration: config))
        config = MileageDistanceCalculator.Configuration()
        config.maximumSpeedMetersPerSecond = 0
        XCTAssertThrowsError(try MileageDistanceCalculator.calculate([], configuration: config))
        config = MileageDistanceCalculator.Configuration()
        config.maximumGapSeconds = -1
        XCTAssertThrowsError(try MileageDistanceCalculator.calculate([], configuration: config))
    }
}
