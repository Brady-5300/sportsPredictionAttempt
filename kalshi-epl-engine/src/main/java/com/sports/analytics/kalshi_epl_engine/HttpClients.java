package com.sports.analytics.kalshi_epl_engine;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Web clients for calling outside sites. Without timeouts, a request that
 * starts while the internet connection is dropping can wait forever and
 * freeze every scan after it, so every call gives up after these limits and
 * the scan reports a failure instead.
 */
final class HttpClients {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private HttpClients() {
    }

    static RestTemplate withTimeouts() {
        return withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    static RestTemplate withTimeouts(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}
