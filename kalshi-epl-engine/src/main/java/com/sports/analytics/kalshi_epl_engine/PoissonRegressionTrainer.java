package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * Fits a Poisson regression (log-link) by batch gradient descent with L2
 * regularization: predicts a count/rate as exp(w . x), fit to minimize
 * Poisson deviance. General-purpose - used to calibrate the match-level xG
 * formula (attack rating, opponent defense rating, home/away -> actual goals
 * scored) against real Understat results, the same way LogisticRegressionTrainer
 * calibrates the shot-level model.
 */
public class PoissonRegressionTrainer {

    public record Example(double[] features, double actualCount) {
    }

    public record TrainingResult(double[] weights, double finalDeviance) {
    }

    private final double learningRate;
    private final int iterations;
    private final double l2Lambda;

    public PoissonRegressionTrainer(double learningRate, int iterations, double l2Lambda) {
        this.learningRate = learningRate;
        this.iterations = iterations;
        this.l2Lambda = l2Lambda;
    }

    /** Fits weights (including a bias term at index 0 - callers must prepend a constant 1.0 feature). */
    public TrainingResult fit(List<Example> examples) {
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("cannot fit on an empty dataset");
        }

        int featureCount = examples.get(0).features().length;
        double[] weights = new double[featureCount];
        int n = examples.size();

        for (int iter = 0; iter < iterations; iter++) {
            double[] gradient = new double[featureCount];

            for (Example example : examples) {
                double predicted = predict(weights, example.features());
                double error = predicted - example.actualCount();
                for (int j = 0; j < featureCount; j++) {
                    gradient[j] += error * example.features()[j];
                }
            }

            for (int j = 0; j < featureCount; j++) {
                double regularization = j == 0 ? 0.0 : l2Lambda * weights[j]; // don't regularize the bias term
                weights[j] -= learningRate * ((gradient[j] / n) + regularization);
            }
        }

        return new TrainingResult(weights, deviance(weights, examples));
    }

    /** exp(w . x) - the predicted rate/count, always positive. */
    public double predict(double[] weights, double[] features) {
        double z = 0.0;
        for (int j = 0; j < weights.length; j++) {
            z += weights[j] * features[j];
        }
        return Math.exp(z);
    }

    /** Mean Poisson deviance - lower is better, 0 is a perfect fit. */
    private double deviance(double[] weights, List<Example> examples) {
        double sum = 0.0;
        for (Example example : examples) {
            double predicted = Math.max(1e-10, predict(weights, example.features()));
            double actual = example.actualCount();
            double term = actual > 0
                ? actual * Math.log(actual / predicted) - (actual - predicted)
                : predicted; // limit as actual -> 0
            sum += 2 * term;
        }
        return sum / examples.size();
    }
}
