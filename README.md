# Kalshi EPL Prediction Engine

A Spring Boot app that predicts Premier League matches and compares its probabilities with live Kalshi market prices. It builds its own expected-goals (xG) model from real shot data, rates every team's attack and defence (adjusted for opponent strength), turns that into win/draw/loss probabilities, and flags markets where it disagrees with the price.

**No API keys or signups needed to run this.** All the data sources are free and don't require accounts.

## Quick start

**Requirements:** Java 21 ([Adoptium](https://adoptium.net/) or any JDK 21+). No need to install Maven separately: the project ships its own wrapper.

```bash
git clone https://github.com/Brady-5300/sportsPredictionAttempt.git
cd sportsPredictionAttempt/kalshi-epl-engine
./mvnw spring-boot:run
```

On Windows (PowerShell/cmd), use `mvnw.cmd spring-boot:run` instead.

Once it's running, open **http://localhost:8080** in a browser. The first scan takes a minute or two while it downloads match data; after that it's fast.

## What's in the app

The dashboard has four tabs:

- **Markets:** every open Kalshi EPL market, grouped by day in kickoff order, with the model's probability, the price to buy at, the edge after Kalshi's fee, and a verdict (**YES (Undervalued)**, **FAIR VALUE** or **AVOID**). At the top, the **weekend wager planner** takes a dollar amount and splits it across the weekend's undervalued picks, weighted by edge, showing how many whole contracts each share buys and what it pays.
- **Match previews:** a card for every upcoming match: the model's win/draw/loss bar next to Kalshi's, expected goals, both-teams-to-score and over-2.5-goals chances, and a "More details" section with each team's numbers from this season: record, goals, xG created and conceded with league ranks, recent form and most dangerous attackers.
- **Track record:** every finished match the app has logged: what the model said, what Kalshi said, what happened, how well the model's probabilities match reality, and how its picks would have done.
- **Model vs Kalshi:** a weekend-by-weekend scoreboard of whose forecasts were closer to the results.

Every link is shareable: `#previews`, `#track` and `#scoreboard` open straight to a tab.

## How it works

1. **[Understat](https://understat.com)** provides shot-by-shot data for every Premier League match.
2. **Our own xG model** (not Understat's number) scores each shot: a logistic regression on distance, angle, shot type and situation, fit on thousands of real shots.
3. **Team ratings** average each team's xG created and conceded over its last 30 matches across this season and last, weighting recent games more and pulling small samples toward a sensible prior (promoted teams get their own). Each match's xG is scaled by the strength of the opponent, so chances created against a top defence count for more.
4. **A Poisson model** turns both teams' expected goals into probabilities for every scoreline, and from there win/draw/loss.
5. **[Kalshi's API](https://kalshi.com)** gives the live price. The edge is the model's probability minus the cost of buying YES (the ask plus Kalshi's trading fee), and stake sizes use a quarter-Kelly formula.
6. **[FotMob](https://fotmob.com)** provides injury lists and confirmed lineups. A lineup-adjusted prediction is logged alongside the main one, so its value can be measured over time.
7. **A prediction log** records every prediction and Kalshi's price just before kickoff, then fills in the real result once the market settles. That powers the Track record and Model vs Kalshi tabs.

### How accurate is it?

Tested on about 800 matches from 2024 to 2026 that the model never saw during fitting, it scores a Brier score of **0.1994**. Professional bookmakers' closing odds score **0.1983** on the same matches, a difference within statistical noise. For reference, always predicting the league's average home/draw/away rates scores about 0.215 (lower is better).

## Project layout

```
kalshi-epl-engine/
  src/main/java/.../kalshi_epl_engine/
    KalshiMarketService.java        # runs a market scan and ties everything together
    UnderstatXgProvider.java        # opponent-adjusted team ratings, form, player contributions
    ShotXgCalculator.java           # the shot-quality (xG) model
    XgService.java                  # turns team ratings into expected goals for a match
    PoissonModel.java               # expected goals -> scoreline and win/draw/loss probabilities
    LineupFormAdjustmentService.java # lineup/injury adjustment from FotMob
    WeekendAllocationService.java   # the weekend wager planner
    PreviewService.java             # match previews
    PredictionLogService.java       # logs predictions and results
    TrackRecordService.java         # track record and model-vs-Kalshi scoreboard
  src/main/resources/static/index.html   # the dashboard (single self-contained page)
  src/test/java/                    # 160+ tests
  Dockerfile                        # container build used for hosting
render.yaml                         # one-click deploy config for Render
```

## Running the tests

```bash
./mvnw test
```

A few tests marked `@Disabled` hit live sites or refit the models on fresh data (for example `UnderstatScraperLiveSmokeTest`, `XgModelCalibrationTest`, `MatchXgModelCalibrationTest`). Run one manually with:

```bash
./mvnw test -Dtest=XgModelCalibrationTest -Djunit.jupiter.conditions.deactivate="org.junit.*DisabledCondition"
```

## Hosting it

The repo includes a `Dockerfile` and a `render.yaml`, so it can be deployed for free on [Render](https://render.com): sign in with GitHub, choose **New + → Blueprint**, pick this repo, and click **Apply**. Every push to `main` redeploys automatically.

## Data sources

Match and shot data from [Understat](https://understat.com), lineups and injuries from [FotMob](https://fotmob.com), and market prices from [Kalshi](https://kalshi.com). Understat and FotMob are read through the same internal endpoints their own websites use; this project is for personal and research use.
