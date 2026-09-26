package edu.jala.ygocards.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client used for every outbound call to the upstream API.
 *
 * <p>Both timeouts come from configuration rather than from the library default, which is no
 * timeout at all. A call that never returns holds a request thread for as long as the upstream
 * misbehaves, so an unbounded client turns an upstream slowdown into an outage here. Bounding
 * it is what lets this process stay disposable (factor IX): it can always finish or fail within
 * a known time.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient upstreamRestClient(UpstreamProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
