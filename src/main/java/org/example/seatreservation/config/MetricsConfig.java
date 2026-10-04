package org.example.seatreservation.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class MetricsConfig {
    // Counters are bumped in the service. This gauge is different: it asks the database
    // on every scrape, so it can never drift from the real seat state

    @Bean
    public MeterBinder seatsAvailableGauge (JdbcTemplate template){
        return registry -> Gauge.builder("seats.available", template, j->{
            Long n = j.queryForObject("SELECT COUNT(*) FROM seats WHERE status ='available'", Long.class);
            return  n == null ? 0 :n;
        }).description("Seats currently available across all shows").register(registry);
    }
}
