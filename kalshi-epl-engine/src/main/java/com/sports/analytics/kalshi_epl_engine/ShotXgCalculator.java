package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

/**
 * Computes our own expected-goals value per shot from its pitch geometry,
 * instead of trusting Understat's precomputed xG number.
 *
 * This is a hand-tuned logistic model on distance-to-goal and shot angle,
 * with fixed adjustments for shot type and situation. It has NOT been fit
 * against real outcome data - it is a reasonable first-pass heuristic and
 * should eventually be calibrated (see backtesting item in the roadmap).
 */
@Service
public class ShotXgCalculator {

    // Standard pitch dimensions in meters (Understat/Opta convention: 105 x 68).
    private static final double PITCH_LENGTH_M = 105.0;
    private static final double PITCH_WIDTH_M = 68.0;
    private static final double GOAL_WIDTH_M = 7.32;

    public double calculateXg(UnderstatShot shot) {
        double distance = distanceToGoalCenterMeters(shot.parsedX(), shot.parsedY());
        double angle = shotAngleRadians(shot.parsedX(), shot.parsedY());

        double logit = -1.0
            - 0.10 * distance
            + 2.20 * angle;

        logit += situationAdjustment(shot.getSituation());
        logit += shotTypeAdjustment(shot.getShotType());

        String situation = shot.getSituation() == null ? "" : shot.getSituation();
        if (situation.equalsIgnoreCase("Penalty")) {
            // Penalties are a fixed, well-known conversion rate independent of geometry.
            return 0.76;
        }

        double xg = 1.0 / (1.0 + Math.exp(-logit));
        return Math.round(xg * 10000.0) / 10000.0;
    }

    /**
     * Distance in meters from the shot location to the center of the goal line.
     * Understat's X/Y are normalized to [0,1] with the attacking goal at x = 1
     * and y = 0.5 at the center of the pitch width.
     */
    double distanceToGoalCenterMeters(double normalizedX, double normalizedY) {
        double xMeters = (1.0 - normalizedX) * PITCH_LENGTH_M;
        double yMeters = (normalizedY - 0.5) * PITCH_WIDTH_M;
        return Math.sqrt(xMeters * xMeters + yMeters * yMeters);
    }

    /**
     * The angle (radians) subtended by the goal mouth as seen from the shot location.
     * A shot dead-center right on the goal line has the widest angle; wide/far shots
     * have a narrow angle. Uses the standard two-post triangle formula.
     */
    double shotAngleRadians(double normalizedX, double normalizedY) {
        double xMeters = (1.0 - normalizedX) * PITCH_LENGTH_M;
        double yMeters = (normalizedY - 0.5) * PITCH_WIDTH_M;

        double nearPostY = yMeters - GOAL_WIDTH_M / 2.0;
        double farPostY = yMeters + GOAL_WIDTH_M / 2.0;

        double angleToNearPost = Math.atan2(Math.abs(nearPostY), Math.max(xMeters, 0.01));
        double angleToFarPost = Math.atan2(Math.abs(farPostY), Math.max(xMeters, 0.01));

        double angle;
        if ((nearPostY < 0) != (farPostY < 0)) {
            // Shot location is directly between the posts (dead center) - the visible
            // angle is the sum of the two half-angles.
            angle = angleToNearPost + angleToFarPost;
        } else {
            angle = Math.abs(angleToFarPost - angleToNearPost);
        }
        return Math.max(angle, 0.0);
    }

    private double situationAdjustment(String situation) {
        if (situation == null) return 0.0;
        switch (situation) {
            case "OpenPlay": return 0.0;
            case "FromCorner": return -0.20;
            case "SetPiece": return -0.10;
            case "DirectFreekick": return -0.60;
            case "FastBreak": return 0.30;
            default: return 0.0;
        }
    }

    private double shotTypeAdjustment(String shotType) {
        if (shotType == null) return 0.0;
        switch (shotType) {
            case "RightFoot": return 0.0;
            case "LeftFoot": return 0.0;
            case "Head": return -0.45;
            case "OtherBodyPart": return -0.70;
            default: return 0.0;
        }
    }
}
