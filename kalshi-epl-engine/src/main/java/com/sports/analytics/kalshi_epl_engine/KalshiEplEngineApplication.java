package com.sports.analytics.kalshi_epl_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KalshiEplEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(KalshiEplEngineApplication.class, args);
    }
}