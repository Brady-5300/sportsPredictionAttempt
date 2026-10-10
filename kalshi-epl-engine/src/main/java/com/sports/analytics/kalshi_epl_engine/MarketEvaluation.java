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
    private String kickoff;
    private String marketType;
    private Integer yesBidCents;
    private Integer yesAskCents;
    private boolean live;

    public MarketEvaluation(String ticker, String title, int kalshiPriceCents,
                            String modelProbability, String marketProbability,
                            String edge, String recommendation, double recommendedWagerPercent,
                            String xgDataSource, String kickoff,
                            String marketType, Integer yesBidCents, Integer yesAskCents, boolean live) {
        this.ticker = ticker;
        this.title = title;
        this.kalshiPriceCents = kalshiPriceCents;
        this.modelProbability = modelProbability;
        this.marketProbability = marketProbability;
        this.edge = edge;
        this.recommendation = recommendation;
        this.recommendedWagerPercent = recommendedWagerPercent;
        this.xgDataSource = xgDataSource;
        this.kickoff = kickoff;
        this.marketType = marketType;
        this.yesBidCents = yesBidCents;
        this.yesAskCents = yesAskCents;
        this.live = live;
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
    /** ISO-8601 kickoff time (UTC), or null if Kalshi didn't provide one. */
    public String getKickoff() { return kickoff; }
    /** "HOME", "TIE" or "AWAY". */
    public String getMarketType() { return marketType; }
    /** Best YES bid/ask in cents, or null if not quoted - their midpoint is the market's own probability. */
    public Integer getYesBidCents() { return yesBidCents; }
    public Integer getYesAskCents() { return yesAskCents; }
    /**
     * True once the match has kicked off: the model is pre-match only (it knows
     * nothing about the score or time left), so live markets carry Kalshi's
     * price but no model number, verdict or stake, and must never be bet on.
     */
    public boolean isLive() { return live; }

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