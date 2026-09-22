package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticRegressionTrainerTest {

    @Test
    void recoversKnownCoefficientsFromSyntheticData() {
        // Generate data from a KNOWN true model: p = sigmoid(0.5 - 2*x1 + 3*x2),
        // then check the trainer recovers approximately those same coefficients.
        double trueBias = 0.5;
        double trueW1 = -2.0;
        double trueW2 = 3.0;

        Random random = new Random(42);
        List<LogisticRegressionTrainer.LabeledExample> examples = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            double x1 = random.nextDouble() * 4 - 2; // [-2, 2]
            double x2 = random.nextDouble() * 4 - 2;
            double trueP = 1.0 / (1.0 + Math.exp(-(trueBias + trueW1 * x1 + trueW2 * x2)));
            int label = random.nextDouble() < trueP ? 1 : 0;
            examples.add(new LogisticRegressionTrainer.LabeledExample(new double[]{1.0, x1, x2}, label));
        }

        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer(0.1, 2000, 0.0001);
        LogisticRegressionTrainer.TrainingResult result = trainer.fit(examples);

        assertEquals(trueBias, result.weights()[0], 0.3);
        assertEquals(trueW1, result.weights()[1], 0.3);
        assertEquals(trueW2, result.weights()[2], 0.3);
    }

    @Test
    void logLossDecreasesAsIterationsIncrease() {
        List<LogisticRegressionTrainer.LabeledExample> examples = simpleSeparableDataset();

        LogisticRegressionTrainer.TrainingResult few = new LogisticRegressionTrainer(0.5, 5, 0.001).fit(examples);
        LogisticRegressionTrainer.TrainingResult many = new LogisticRegressionTrainer(0.5, 500, 0.001).fit(examples);

        assertTrue(many.finalLogLoss() < few.finalLogLoss(),
            "more iterations (" + many.finalLogLoss() + ") should fit better than few (" + few.finalLogLoss() + ")");
    }

    @Test
    void predictReturnsValidProbabilityRange() {
        List<LogisticRegressionTrainer.LabeledExample> examples = simpleSeparableDataset();
        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer(0.5, 200, 0.001);
        LogisticRegressionTrainer.TrainingResult result = trainer.fit(examples);

        for (LogisticRegressionTrainer.LabeledExample example : examples) {
            double p = trainer.predict(result.weights(), example.features());
            assertTrue(p >= 0.0 && p <= 1.0);
        }
    }

    @Test
    void higherPositiveFeatureValueYieldsHigherPredictedProbabilityForPositiveWeight() {
        // Simple 1D dataset: label = 1 whenever x > 0, roughly.
        List<LogisticRegressionTrainer.LabeledExample> examples = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            double x = random.nextDouble() * 10 - 5;
            int label = x > 0 ? 1 : 0;
            examples.add(new LogisticRegressionTrainer.LabeledExample(new double[]{1.0, x}, label));
        }

        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer(0.1, 1000, 0.0001);
        LogisticRegressionTrainer.TrainingResult result = trainer.fit(examples);

        double pLow = trainer.predict(result.weights(), new double[]{1.0, -3.0});
        double pHigh = trainer.predict(result.weights(), new double[]{1.0, 3.0});

        assertTrue(pHigh > pLow, "higher x should predict higher probability of label=1");
        assertTrue(pHigh > 0.8);
        assertTrue(pLow < 0.2);
    }

    private List<LogisticRegressionTrainer.LabeledExample> simpleSeparableDataset() {
        List<LogisticRegressionTrainer.LabeledExample> examples = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            examples.add(new LogisticRegressionTrainer.LabeledExample(new double[]{1.0, 5.0}, 1));
            examples.add(new LogisticRegressionTrainer.LabeledExample(new double[]{1.0, -5.0}, 0));
        }
        return examples;
    }
}
