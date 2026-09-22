package com.sports.analytics.kalshi_epl_engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KalshiEvent {

    private String title;
    private List<KalshiMarket> markets;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<KalshiMarket> getMarkets() { return markets == null ? Collections.emptyList() : markets; }
    public void setMarkets(List<KalshiMarket> markets) { this.markets = markets; }
}
