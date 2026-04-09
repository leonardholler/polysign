package com.polysign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PolySignApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolySignApplication.class, args);
    }
}
