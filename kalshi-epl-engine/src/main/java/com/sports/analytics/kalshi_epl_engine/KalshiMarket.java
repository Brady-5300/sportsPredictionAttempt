package com.sports.analytics.kalshi_epl_engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KalshiMarket {

    private String ticker;
    private String title;
    private String status;

    @JsonProperty("yes_bid")
    private int yesBid;

    @JsonProperty("yes_ask")
    private int yesAsk;

    @JsonProperty("yes_bid_dollars")
    private String yesBidDollars;

    @JsonProperty("last_price_dollars")
    private String lastPriceDollars;

    @JsonProperty("last_price")
    private Integer lastPrice;

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getYesBid() { return yesBid; }
    public void setYesBid(int yesBid) { this.yesBid = yesBid; }

    public int getYesAsk() { return yesAsk; }
    public void setYesAsk(int yesAsk) { this.yesAsk = yesAsk; }

    public String getYesBidDollars() { return yesBidDollars; }
    public void setYesBidDollars(String yesBidDollars) { this.yesBidDollars = yesBidDollars; }

    public String getLastPriceDollars() { return lastPriceDollars; }
    public void setLastPriceDollars(String lastPriceDollars) { this.lastPriceDollars = lastPriceDollars; }

    public Integer getLastPrice() { return lastPrice; }
    public void setLastPrice(Integer lastPrice) { this.lastPrice = lastPrice; }

    public double getImpliedProbability() {
        return (yesBid + yesAsk) / 200.0;
    }

    /**
     * Resolves the current price in cents, preferring the dollar-denominated
     * bid/last fields (source of truth) and falling back to the legacy
     * integer-cent fields.
     */
    public int resolvePriceCents() {
        if (yesBidDollars != null) {
            try { return (int) Math.round(Double.parseDouble(yesBidDollars) * 100); } catch (NumberFormatException ignored) {}
        }
        if (lastPriceDollars != null) {
            try { return (int) Math.round(Double.parseDouble(lastPriceDollars) * 100); } catch (NumberFormatException ignored) {}
        }
        if (yesBid > 0) return yesBid;
        if (lastPrice != null) return lastPrice;
        return 0;
    }
}
