# Kalshi EPL Prediction Engine

A Spring Boot app that scans live Kalshi Premier League markets and flags ones where our model disagrees with the market price. It builds its own expected-goals (xG) model from real match data, adjusts for injuries and confirmed lineups, and compares the result against Kalshi's price to surface mispriced markets with a suggested Kelly stake size.

**No API keys or signups needed to run this.** All the data sources are free and don't require accounts.

## Quick start

**Requirements:** Java 21 ([Adoptium](https://adoptium.net/) or any JDK 21+). No need to install Maven separately — the project ships its own wrapper.

```bash
git clone https://github.com/Brady-5300/sportsPredictionAttempt.git
cd sportsPredictionAttempt/kalshi-epl-engine
./mvnw spring-boot:run
```

On Windows (PowerShell/cmd), use `mvnw.cmd spring-boot:run` instead.

Once it's running, open **http://localhost:8080** in a browser. That's the dashboard — it looks like Kalshi's own site and auto-refreshes every 30 seconds.

First run will take a minute or two while Maven downloads dependencies. After that it's fast.

## What you'll see

- **A list of currently active Kalshi EPL markets**, each with our model's probability, Kalshi's implied probability, the edge between them, and a recommendation (buy YES if undervalued, NO if overvalued, or fair value if there's no real edge).
- **A "skipped" section** for any market where we don't have enough data for one of the teams (e.g. a newly promoted club we haven't mapped yet).
- **If a data source goes down**, the dashboard shows a clear red banner and stops evaluating markets entirely — it will never silently fall back to a rough guess and pretend it's real data.

If there are no active EPL markets on Kalshi at the moment (e.g. between matchdays), you'll just see an empty state — that's normal, not a bug.

## How it actually works

1. **[Understat](https://understat.com)** (scraped, no key) — real shot-by-shot data for each team's last 6 games, weighted so recent form matters more than form from a month ago.
2. **Our own xG model** — not Understat's own number, but one we fit ourselves: a logistic regression trained on thousands of real shots (distance, angle, shot type, situation).
3. **[FotMob](https://fotmob.com)** (scraped, no key) — confirmed starting lineups and injury lists, cross-referenced against each missing player's own measured contribution to estimate how much their absence should move the numbers.
4. **A Poisson model** turns both teams' adjusted xG into win/draw/loss probabilities.
5. **Kalshi's own market API** gives us the current price. We compare our probability to theirs (after subtracting Kalshi's trading fee) to compute the edge, and size a suggested bet with a quarter-Kelly formula.

## Project layout

```
kalshi-epl-engine/
  src/main/java/.../kalshi_epl_engine/
    KalshiMarketService.java       # orchestrates a scan, ties everything together
    XgService.java                 # combines live data into a match xG prediction
    UnderstatScraperService.java   # pulls real shot/roster data from Understat
    FotMobClient.java              # pulls lineups/injuries from FotMob
    ShotXgCalculator.java          # the calibrated shot-quality model
    PoissonModel.java              # converts xG into win/draw/loss probabilities
    ScraperHealthMonitor.java      # tracks whether a data source is down
  src/main/resources/
    static/index.html              # the dashboard (single self-contained page)
  src/test/java/                   # ~90 tests, most run offline against real captured data
```

## Running the tests

```bash
./mvnw test
```

Most tests run fully offline (using real API/site responses captured as test fixtures, so they don't need network access). A handful of tests are marked `@Disabled` because they hit live external sites — these are meant to be run manually if you suspect an integration has broken, not as part of normal CI:

- `UnderstatScraperLiveSmokeTest`, `FotMobLiveSmokeTest`, `XgServiceLiveSmokeTest` — quick live sanity checks.
- `XgModelCalibrationTest` — re-fits the xG model against a fresh season of real data (scrapes ~200+ matches, takes a few minutes). Only needed if you want to recalibrate.

To run a disabled test manually:
```bash
./mvnw test -Dtest=FotMobLiveSmokeTest -Djunit.jupiter.conditions.deactivate="org.junit.*DisabledCondition"
```

## Known limitations

This is a research/prototype project, not something to stake real money on as-is:

- **No backtesting yet** — nothing validates whether the overall pipeline is actually profitable historically, just that each individual piece (the xG model, the fee math) is honestly calibrated.
- **Both data sources are unofficial scrapes** of undocumented endpoints. They could break if either site changes its structure — the dashboard will show a clear "offline" banner if that happens rather than failing silently.
- **In-memory caching** — restarting the app clears all cached data, so the first scan after a restart will be slower and re-fetch everything.
- Team-name mapping is Premier League-specific and may not immediately recognize a newly promoted or relegated club until it's added to `TeamNameResolver`.

## A note on the data sources

Understat and FotMob don't offer official public APIs — this project calls their internal endpoints the same way their own websites do. That's scraping, not a sanctioned integration, and it's used here for personal/research use. If either site's structure changes, the relevant integration may need small fixes (see the `*LiveSmokeTest` files above for how to check).
