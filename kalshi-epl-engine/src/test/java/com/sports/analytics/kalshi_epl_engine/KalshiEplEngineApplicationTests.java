package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Keep the full-context test (which runs the live scanner) away from the real prediction log.
@SpringBootTest(properties = "prediction-log.path=target/test-prediction-log.jsonl")
class KalshiEplEngineApplicationTests {

	@Test
	void contextLoads() {
	}

}
