package com.polysign;

import com.polysign.config.RssProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RssProperties.class)
public class PolySignApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolySignApplication.class, args);
    }
}
