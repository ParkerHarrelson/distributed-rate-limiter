package dev.parkerharrelson.ratelimiter.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One replica of the rate-limited API. Run several against one Redis and they enforce the same
 * limits collectively.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
