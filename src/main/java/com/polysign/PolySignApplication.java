package com.polysign;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@OpenAPIDefinition(
    info = @Info(
        title       = "PolySign API",
        description = "Real-time prediction market signal feed with measured precision. " +
                      "Authenticate via X-API-Key header. Rate limits apply per key tier.",
        version     = "1.0"
    )
)
@SpringBootApplication
@EnableScheduling
public class PolySignApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolySignApplication.class, args);
    }
}
