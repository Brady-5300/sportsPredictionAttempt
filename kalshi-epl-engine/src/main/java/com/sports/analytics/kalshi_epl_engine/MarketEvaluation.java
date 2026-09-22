package com.sports.analytics.kalshi_epl_engine;

public class MarketEvaluation {
    private String ticker;
    private String title;
    private int kalshiPriceCents;
    private String modelProbability;
    private String marketProbability;
    private String edge;
    private String recommendation;
    private double recommendedWagerPercent;
    private String xgDataSource;

    public MarketEvaluation(String ticker, String title, int kalshiPriceCents,
                            String modelProbability, String marketProbability,
                            String edge, String recommendation, double recommendedWagerPercent,
                            String xgDataSource) {
        this.ticker = ticker;
        this.title = title;
        this.kalshiPriceCents = kalshiPriceCents;
        this.modelProbability = modelProbability;
        this.marketProbability = marketProbability;
        this.edge = edge;
        this.recommendation = recommendation;
        this.recommendedWagerPercent = recommendedWagerPercent;
        this.xgDataSource = xgDataSource;
    }

    public String getTicker() { return ticker; }
    public String getTitle() { return title; }
    public int getKalshiPriceCents() { return kalshiPriceCents; }
    public String getModelProbability() { return modelProbability; }
    public String getMarketProbability() { return marketProbability; }
    public String getEdge() { return edge; }
    public String getRecommendation() { return recommendation; }
    public double getRecommendedWagerPercent() { return recommendedWagerPercent; }
    public String getXgDataSource() { return xgDataSource; }

    @Override
    public String toString() {
        return String.format(
            "--------------------------------------------------%n" +
            "Ticker:      %s%n" +
            "Market:      %s%n" +
            "--------------------------------------------------%n" +
            "Price:       %d¢ (Mkt Prob: %s)%n" +
            "Model Prob:  %s%n" +
            "Edge:        %s%n" +
            "Action:      %s%n" +
            "Kelly Wager: %.2f%%%n" +
            "xG Source:   %s%n",
            ticker, title, kalshiPriceCents, marketProbability,
            modelProbability, edge, recommendation, recommendedWagerPercent, xgDataSource
        );
    }
}