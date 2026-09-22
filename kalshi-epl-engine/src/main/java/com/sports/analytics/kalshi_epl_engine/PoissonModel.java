package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

@Service
public class PoissonModel {

    // Computes exact Poisson probabilities for Home Win, Away Win, and Tie
    public double calculateMarketProbability(double homeXG, double awayXG, String marketType) {
        double homeWinProb = 0.0;
        double awayWinProb = 0.0;
        double tieProb = 0.0;

        // Max goals to iterate through in our Poisson matrix (0 to 6 goals)
        int maxGoals = 6;

        for (int i = 0; i <= maxGoals; i++) {
            for (int j = 0; j <= maxGoals; j++) {
                double pHome = poissonProbability(i, homeXG);
                double pAway = poissonProbability(j, awayXG);
                double jointProb = pHome * pAway;

                if (i > j) {
                    homeWinProb += jointProb;
                } else if (i < j) {
                    awayWinProb += jointProb;
                } else {
                    tieProb += jointProb;
                }
            }
        }

        // Normalize slightly to account for truncation past maxGoals
        double total = homeWinProb + awayWinProb + tieProb;
        if (total > 0) {
            homeWinProb /= total;
            awayWinProb /= total;
            tieProb /= total;
        }

        String type = marketType == null ? "" : marketType.toUpperCase();
        if (type.contains("TIE") || type.equals("T")) {
            return tieProb;
        } else if (type.contains("HOME") || type.equals("H")) {
            return homeWinProb;
        } else {
            // Default or Away
            return awayWinProb;
        }
    }

    private double poissonProbability(int k, double lambda) {
        return (Math.pow(lambda, k) * Math.exp(-lambda)) / factorial(k);
    }

    private double factorial(int n) {
        if (n <= 1) return 1.0;
        double result = 1.0;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    public double calculateKellyWagerPercent(double modelProb, int priceCents) {
        if (priceCents <= 0 || priceCents >= 100) return 0.0;
        
        double b = (100.0 / priceCents) - 1.0; 
        double p = modelProb;
        double q = 1.0 - p;

        double kellyFraction = (p * b - q) / b;
        double quarterKelly = kellyFraction * 0.25; 
        return Math.max(0.0, Math.round(quarterKelly * 10000.0) / 100.0);
    }
}