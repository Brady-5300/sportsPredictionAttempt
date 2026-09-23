package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoissonRegressionTrainerTest {

    @Test
    void recoversKnownCoefficientsFromSyntheticData() {
        // Generate data from a KNOWN true model: lambda = exp(0.2 + 0.5*x1 - 0.3*x2),
        // sample actual counts from a real Poisson distribution, then check the
        // trainer recovers approximately the true coefficients.
        double trueBias = 0.2;
        double trueW1 = 0.5;
        double trueW2 = -0.3;

        Random random = new Random(42);
        List<PoissonRegressionTrainer.Example> examples = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            double x1 = random.nextDouble() * 2 - 1; // [-1, 1]
            double x2 = random.nextDouble() * 2 - 1;
            double trueLambda = Math.exp(trueBias + trueW1 * x1 + trueW2 * x2);
            int sampledCount = samplePoisson(trueLambda, random);
            examples.add(new PoissonRegressionTrainer.Example(new double[]{1.0, x1, x2}, sampledCount));
        }

        PoissonRegressionTrainer trainer = new PoissonRegressionTrainer(0.05, 2000, 0.0001);
        PoissonRegressionTrainer.TrainingResult result = trainer.fit(examples);

        assertEquals(trueBias, result.weights()[0], 0.15);
        assertEquals(trueW1, result.weights()[1], 0.15);
        assertEquals(trueW2, result.weights()[2], 0.15);
    }

    @Test
    void predictedRateIsAlwaysPositive() {
        double[] weights = {-5.0, 3.0}; // even with a very negative bias
        PoissonRegressionTrainer trainer = new PoissonRegressionTrainer(0.01, 1, 0.0);
        double predicted = trainer.predict(weights, new double[]{1.0, -10.0});
        assertTrue(predicted > 0);
    }

    @Test
    void devianceDecreasesAsIterationsIncrease() {
        List<PoissonRegressionTrainer.Example> examples = List.of(
            new PoissonRegressionTrainer.Example(new double[]{1.0, 1.0}, 5.0),
            new PoissonRegressionTrainer.Example(new double[]{1.0, -1.0}, 0.5)
        );

        PoissonRegressionTrainer.TrainingResult few = new PoissonRegressionTrainer(0.05, 3, 0.001).fit(examples);
        PoissonRegressionTrainer.TrainingResult many = new PoissonRegressionTrainer(0.05, 500, 0.001).fit(examples);

        assertTrue(many.finalDeviance() < few.finalDeviance(),
            "more iterations (" + many.finalDeviance() + ") should fit better than few (" + few.finalDeviance() + ")");
    }

    @Test
    void higherFeatureValueYieldsHigherPredictedRateForPositiveWeight() {
        List<PoissonRegressionTrainer.Example> examples = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            double x = random.nextDouble() * 2 - 1;
            double trueLambda = Math.exp(0.3 * x);
            examples.add(new PoissonRegressionTrainer.Example(new double[]{1.0, x}, samplePoisson(trueLambda, random)));
        }

        PoissonRegressionTrainer trainer = new PoissonRegressionTrainer(0.05, 1000, 0.0001);
        PoissonRegressionTrainer.TrainingResult result = trainer.fit(examples);

        double rateAtLowX = trainer.predict(result.weights(), new double[]{1.0, -1.0});
        double rateAtHighX = trainer.predict(result.weights(), new double[]{1.0, 1.0});

        assertTrue(rateAtHighX > rateAtLowX);
    }

    /** Knuth's algorithm - simple and fine for test-data generation at these small lambda values. */
    private int samplePoisson(double lambda, Random random) {
        double l = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > l);
        return k - 1;
    }
}
