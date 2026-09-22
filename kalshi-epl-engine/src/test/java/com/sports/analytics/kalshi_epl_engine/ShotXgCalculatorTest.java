package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShotXgCalculatorTest {

    private final ShotXgCalculator calculator = new ShotXgCalculator();

    private UnderstatShot shot(double x, double y, String situation, String shotType) {
        UnderstatShot shot = new UnderstatShot();
        shot.setX(String.valueOf(x));
        shot.setY(String.valueOf(y));
        shot.setSituation(situation);
        shot.setShotType(shotType);
        return shot;
    }

    @Test
    void closeRangeCentralShotHasHighXgThanLongRangeShot() {
        double closeXg = calculator.calculateXg(shot(0.94, 0.5, "OpenPlay", "RightFoot"));
        double longRangeXg = calculator.calculateXg(shot(0.65, 0.5, "OpenPlay", "RightFoot"));

        assertTrue(closeXg > longRangeXg,
            "close-range central shot (" + closeXg + ") should have higher xG than long range (" + longRangeXg + ")");
    }

    @Test
    void wideAngleShotHasLowerXgThanCentralShotAtSameDistance() {
        double centralXg = calculator.calculateXg(shot(0.90, 0.5, "OpenPlay", "RightFoot"));
        double wideXg = calculator.calculateXg(shot(0.90, 0.85, "OpenPlay", "RightFoot"));

        assertTrue(centralXg > wideXg,
            "central shot (" + centralXg + ") should beat wide-angle shot (" + wideXg + ") at the same distance");
    }

    @Test
    void penaltiesAreFixedAtStandardConversionRate() {
        double xg = calculator.calculateXg(shot(0.945, 0.5, "Penalty", "RightFoot"));
        assertEquals(0.76, xg, 0.0001);
    }

    @Test
    void headersScoreLowerThanFootedShotsFromTheSameSpot() {
        double footXg = calculator.calculateXg(shot(0.92, 0.5, "OpenPlay", "RightFoot"));
        double headerXg = calculator.calculateXg(shot(0.92, 0.5, "OpenPlay", "Head"));

        assertTrue(footXg > headerXg);
    }

    @Test
    void directFreekicksScoreLowerThanOpenPlayFromTheSameSpot() {
        double openPlayXg = calculator.calculateXg(shot(0.80, 0.5, "OpenPlay", "RightFoot"));
        double freekickXg = calculator.calculateXg(shot(0.80, 0.5, "DirectFreekick", "RightFoot"));

        assertTrue(openPlayXg > freekickXg);
    }

    @Test
    void allProbabilitiesStayWithinValidRange() {
        double xg = calculator.calculateXg(shot(0.5, 0.5, "OpenPlay", "OtherBodyPart"));
        assertTrue(xg >= 0.0 && xg <= 1.0);
    }
}
