package com.polysign.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Provides a dedicated thread pool for {@code @Scheduled} tasks.
 *
 * <p>Spring's default scheduler uses a single thread, which would serialize all pollers
 * and cause cascading delays whenever a poll takes longer than expected.
 * Pool size 4 covers: MarketPoller, PricePoller, plus two spares for future pollers or detector tasks.
 *
 * <p>{@code waitForTasksToCompleteOnShutdown} + {@code awaitTerminationSeconds} ensures
 * in-flight polls complete gracefully before the Spring context shuts down.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("polysign-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
