package com.sports.analytics.kalshi_epl_engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KalshiMarketResponse {
    private List<KalshiEvent> events;

    public List<KalshiEvent> getEvents() { return events == null ? Collections.emptyList() : events; }
    public void setEvents(List<KalshiEvent> events) { this.events = events; }
}
