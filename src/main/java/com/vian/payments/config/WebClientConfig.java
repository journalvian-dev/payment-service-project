package com.vian.payments.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    /**
     * WebClient dipakai untuk outbound call ke partner (non-blocking secara API,
     * tapi di service layer kita .block() karena flow-nya sinkron).
     * Timeout di sini adalah lapisan pertahanan pertama; Resilience4j menangani
     * retry & circuit breaker.
     */
    @Bean
    public WebClient partnerWebClient(AppProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000)
                .responseTimeout(properties.partner().timeout());

        return WebClient.builder()
                .baseUrl(properties.partner().baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
