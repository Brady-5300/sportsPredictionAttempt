package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TeamNameResolverTest {

    private final TeamNameResolver resolver = new TeamNameResolver();

    @Test
    void resolvesUnderstatSlugsForKnownTeams() {
        assertEquals("Manchester_United", resolver.getUnderstatSlug("Manchester United"));
        assertEquals("Manchester_United", resolver.getUnderstatSlug("MUN"));
        assertEquals("Arsenal", resolver.getUnderstatSlug("arsenal"));
        assertEquals("Nottingham_Forest", resolver.getUnderstatSlug("Nottingham Forest"));
    }

    @Test
    void returnsNullSlugForUnmappedTeam() {
        assertNull(resolver.getUnderstatSlug("Some Newly Promoted Club"));
    }

    @Test
    void teamCodeAndSlugAgreeOnNormalization() {
        assertEquals("FUL", resolver.getTeamCode("ful"));
        assertEquals("Fulham", resolver.getUnderstatSlug("ful"));
    }

    @Test
    void usesKalshisOwnTickerCodes() {
        assertEquals("CFC", resolver.getTeamCode("Chelsea"));
        assertEquals("LFC", resolver.getTeamCode("Liverpool"));
    }

    @Test
    void resolvesNamesAsKalshiAndUnderstatActuallyWriteThem() {
        assertEquals("Nottingham_Forest", resolver.getUnderstatSlug("Nottingham"));
        assertEquals("Hull", resolver.getUnderstatSlug("Hull City"));
        assertEquals("Ipswich", resolver.getUnderstatSlug("Ipswich Town"));
        assertEquals("Coventry", resolver.getUnderstatSlug("Coventry"));
        assertEquals("Wolverhampton_Wanderers", resolver.getUnderstatSlug("Wolverhampton"));
        assertEquals("Newcastle_United", resolver.getUnderstatSlug("Newcastle United"));
        assertEquals("Leeds", resolver.getUnderstatSlug("Leeds"));
    }
}
