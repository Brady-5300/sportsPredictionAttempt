package com.sports.analytics.kalshi_epl_engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KalshiMarket {

    private String ticker;
    private String title;
    private String status;
    private String result;

    @JsonProperty("close_time")
    private String closeTime;

    @JsonProperty("yes_bid")
    private int yesBid;

    @JsonProperty("yes_ask")
    private int yesAsk;

    @JsonProperty("yes_bid_dollars")
    private String yesBidDollars;

    @JsonProperty("yes_ask_dollars")
    private String yesAskDollars;

    @JsonProperty("occurrence_datetime")
    private String occurrenceDatetime;

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

    /** "yes" or "no" once the market is settled; empty/null while still active. */
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    /** ISO-8601 timestamp of when this market stopped trading - around full time, NOT kickoff (markets trade in-play). */
    public String getCloseTime() { return closeTime; }
    public void setCloseTime(String closeTime) { this.closeTime = closeTime; }

    public int getYesBid() { return yesBid; }
    public void setYesBid(int yesBid) { this.yesBid = yesBid; }

    public int getYesAsk() { return yesAsk; }
    public void setYesAsk(int yesAsk) { this.yesAsk = yesAsk; }

    public String getYesBidDollars() { return yesBidDollars; }
    public void setYesBidDollars(String yesBidDollars) { this.yesBidDollars = yesBidDollars; }

    public String getYesAskDollars() { return yesAskDollars; }
    public void setYesAskDollars(String yesAskDollars) { this.yesAskDollars = yesAskDollars; }

    public String getOccurrenceDatetime() { return occurrenceDatetime; }
    public void setOccurrenceDatetime(String occurrenceDatetime) { this.occurrenceDatetime = occurrenceDatetime; }

    /**
     * Kickoff time, derived from occurrence_datetime, which Kalshi sets 3 hours
     * after kickoff for KXEPLGAME (checked against Understat kickoff times on
     * 12 matches, exact every time). Empty if the field is missing or unparseable.
     */
    public Optional<Instant> estimatedKickoff() {
        if (occurrenceDatetime == null) return Optional.empty();
        try {
            return Optional.of(Instant.parse(occurrenceDatetime).minus(Duration.ofHours(3)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Best YES bid in cents, if one is quoted. */
    public Optional<Integer> yesBidCents() {
        return dollarsToCents(yesBidDollars).or(() -> yesBid > 0 ? Optional.of(yesBid) : Optional.empty());
    }

    /** Best YES ask in cents, if one is quoted. */
    public Optional<Integer> yesAskCents() {
        return dollarsToCents(yesAskDollars).or(() -> yesAsk > 0 ? Optional.of(yesAsk) : Optional.empty());
    }

    private static Optional<Integer> dollarsToCents(String dollars) {
        if (dollars == null) return Optional.empty();
        try {
            return Optional.of((int) Math.round(Double.parseDouble(dollars) * 100));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

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
