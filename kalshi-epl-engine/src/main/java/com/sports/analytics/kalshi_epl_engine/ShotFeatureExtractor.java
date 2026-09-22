package com.sports.analytics.kalshi_epl_engine;

/**
 * Turns a shot into the feature vector used to calibrate/evaluate the xG
 * logistic model. Kept separate from {@link ShotXgCalculator} so the same
 * feature definitions are shared between training (LogisticRegressionTrainer)
 * and inference, instead of two copies drifting apart.
 *
 * Feature order: [bias, distance, angle, isFromCorner, isSetPiece,
 * isDirectFreekick, isFastBreak, isHeader, isOtherBodyPart]
 * Penalties are excluded entirely - handled as a fixed rate, not modeled
 * geometrically (see ShotXgCalculator).
 */
public class ShotFeatureExtractor {

    public static final int FEATURE_COUNT = 9;

    private final ShotXgCalculator geometry;

    public ShotFeatureExtractor(ShotXgCalculator geometry) {
        this.geometry = geometry;
    }

    public double[] extract(UnderstatShot shot) {
        double distance = geometry.distanceToGoalCenterMeters(shot.parsedX(), shot.parsedY());
        double angle = geometry.shotAngleRadians(shot.parsedX(), shot.parsedY());
        String situation = shot.getSituation() == null ? "" : shot.getSituation();
        String shotType = shot.getShotType() == null ? "" : shot.getShotType();

        return new double[]{
            1.0, // bias
            distance,
            angle,
            situation.equals("FromCorner") ? 1.0 : 0.0,
            situation.equals("SetPiece") ? 1.0 : 0.0,
            situation.equals("DirectFreekick") ? 1.0 : 0.0,
            situation.equals("FastBreak") ? 1.0 : 0.0,
            shotType.equals("Head") ? 1.0 : 0.0,
            shotType.equals("OtherBodyPart") ? 1.0 : 0.0,
        };
    }

    /** True if this shot's outcome counts as a "goal" label for training/evaluation. */
    public boolean isGoal(UnderstatShot shot) {
        return "Goal".equals(shot.getResult());
    }

    /** Penalties and own goals aren't useful/valid training examples for open-play shot quality. */
    public boolean isTrainable(UnderstatShot shot) {
        String situation = shot.getSituation();
        String result = shot.getResult();
        if ("Penalty".equals(situation)) return false;
        if ("OwnGoal".equals(result)) return false;
        return true;
    }
}
