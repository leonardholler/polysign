package com.polysign.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling configuration.
 * @EnableScheduling is already on PolySignApplication; this class exists as
 * the conventional place to tune the task executor (thread pool size, etc.)
 * once pollers are added in Phases 2-7.
 */
@Configuration
public class SchedulingConfig {
    // TODO Phase 2: configure a ThreadPoolTaskScheduler with an appropriate pool size
}
