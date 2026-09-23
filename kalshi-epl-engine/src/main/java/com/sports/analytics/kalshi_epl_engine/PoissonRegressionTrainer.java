package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * Fits a Poisson regression (log-link) with L2 regularization: predicts a
 * count/rate as exp(w . x), fit to minimize Poisson deviance. Used to
 * calibrate the match-level xG formula (attack rating, opponent defense
 * rating, home/away -> actual goals scored) against real Understat results.
 *
 * Uses Newton's method rather than gradient descent: with unscaled,
 * correlated features, fixed-step gradient descent stopped far short of the
 * optimum (it produced coefficients ~4x too small that barely let team
 * ratings affect predictions), while Newton converges exactly in a handful
 * of iterations.
 */
public class PoissonRegressionTrainer {

    public record Example(double[] features, double actualCount) {
    }

    public record TrainingResult(double[] weights, double finalDeviance) {
    }

    private static final double CONVERGENCE_TOLERANCE = 1e-10;

    private final int maxIterations;
    private final double l2Lambda;

    public PoissonRegressionTrainer(int maxIterations, double l2Lambda) {
        this.maxIterations = maxIterations;
        this.l2Lambda = l2Lambda;
    }

    /** Fits weights (including a bias term at index 0 - callers must prepend a constant 1.0 feature). */
    public TrainingResult fit(List<Example> examples) {
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("cannot fit on an empty dataset");
        }

        int p = examples.get(0).features().length;
        double[] weights = new double[p];
        double meanCount = examples.stream().mapToDouble(Example::actualCount).average().orElse(1.0);
        weights[0] = Math.log(Math.max(meanCount, 1e-6));

        double objective = penalizedDeviance(weights, examples);
        for (int iter = 0; iter < maxIterations; iter++) {
            double[] gradient = new double[p];
            double[][] hessian = new double[p][p];

            for (Example example : examples) {
                double[] x = example.features();
                double mu = predict(weights, x);
                double residual = mu - example.actualCount();
                for (int i = 0; i < p; i++) {
                    gradient[i] += residual * x[i];
                    for (int j = 0; j < p; j++) {
                        hessian[i][j] += mu * x[i] * x[j];
                    }
                }
            }
            for (int i = 1; i < p; i++) { // don't regularize the bias term
                gradient[i] += l2Lambda * examples.size() * weights[i];
                hessian[i][i] += l2Lambda * examples.size();
            }

            double[] step = solve(hessian, gradient);

            // Step-halving keeps Newton stable when a full step would overshoot
            // (possible with the exponential link far from the optimum).
            double scale = 1.0;
            double[] candidate = new double[p];
            double candidateObjective;
            do {
                for (int i = 0; i < p; i++) candidate[i] = weights[i] - scale * step[i];
                candidateObjective = penalizedDeviance(candidate, examples);
                scale /= 2;
            } while (candidateObjective > objective && scale > 1e-8);

            double maxChange = 0.0;
            for (int i = 0; i < p; i++) maxChange = Math.max(maxChange, Math.abs(candidate[i] - weights[i]));
            weights = candidate.clone();
            objective = candidateObjective;
            if (maxChange < CONVERGENCE_TOLERANCE) break;
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

    private double penalizedDeviance(double[] weights, List<Example> examples) {
        double penalty = 0.0;
        for (int i = 1; i < weights.length; i++) penalty += weights[i] * weights[i];
        return deviance(weights, examples) + l2Lambda * penalty;
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

    /** Solves A x = b by Gaussian elimination with partial pivoting (A is small: one row per feature). */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r;
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col || m[col][col] == 0.0) continue;
                double factor = m[r][col] / m[col][col];
                for (int k = col; k <= n; k++) m[r][k] -= factor * m[col][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][i] == 0.0 ? 0.0 : m[i][n] / m[i][i];
        return x;
    }
}
