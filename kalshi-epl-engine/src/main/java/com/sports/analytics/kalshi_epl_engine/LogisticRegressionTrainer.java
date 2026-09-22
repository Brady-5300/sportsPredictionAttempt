package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * Fits a binary logistic regression by batch gradient descent with L2
 * regularization. General-purpose - used to calibrate {@link ShotXgCalculator}
 * against real shot outcome data, but has no football-specific knowledge itself.
 */
public class LogisticRegressionTrainer {

    public record LabeledExample(double[] features, int label) {
    }

    public record TrainingResult(double[] weights, double finalLogLoss) {
    }

    private final double learningRate;
    private final int iterations;
    private final double l2Lambda;

    public LogisticRegressionTrainer(double learningRate, int iterations, double l2Lambda) {
        this.learningRate = learningRate;
        this.iterations = iterations;
        this.l2Lambda = l2Lambda;
    }

    /**
     * Fits weights (including a bias term at index 0 - callers must prepend a
     * constant 1.0 feature) to minimize regularized log-loss over the examples.
     */
    public TrainingResult fit(List<LabeledExample> examples) {
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("cannot fit on an empty dataset");
        }

        int featureCount = examples.get(0).features().length;
        double[] weights = new double[featureCount];
        int n = examples.size();

        for (int iter = 0; iter < iterations; iter++) {
            double[] gradient = new double[featureCount];

            for (LabeledExample example : examples) {
                double prediction = predict(weights, example.features());
                double error = prediction - example.label();
                for (int j = 0; j < featureCount; j++) {
                    gradient[j] += error * example.features()[j];
                }
            }

            for (int j = 0; j < featureCount; j++) {
                double regularization = j == 0 ? 0.0 : l2Lambda * weights[j]; // don't regularize the bias term
                weights[j] -= learningRate * ((gradient[j] / n) + regularization);
            }
        }

        return new TrainingResult(weights, logLoss(weights, examples));
    }

    public double predict(double[] weights, double[] features) {
        double z = 0.0;
        for (int j = 0; j < weights.length; j++) {
            z += weights[j] * features[j];
        }
        return sigmoid(z);
    }

    private double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private double logLoss(double[] weights, List<LabeledExample> examples) {
        double sum = 0.0;
        for (LabeledExample example : examples) {
            double p = clamp(predict(weights, example.features()));
            sum += example.label() == 1 ? -Math.log(p) : -Math.log(1 - p);
        }
        return sum / examples.size();
    }

    private double clamp(double p) {
        double epsilon = 1e-15;
        return Math.min(1 - epsilon, Math.max(epsilon, p));
    }
}
