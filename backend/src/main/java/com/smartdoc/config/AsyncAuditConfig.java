package com.smartdoc.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncAuditConfig {

    @Bean("asyncAuditExecutor")
    public ThreadPoolTaskExecutor asyncAuditExecutor(
            @Value("${smartdoc.async-audit.core-pool-size:4}") int corePoolSize,
            @Value("${smartdoc.async-audit.max-pool-size:8}") int maxPoolSize,
            @Value("${smartdoc.async-audit.queue-capacity:100}") int queueCapacity,
            @Value("${smartdoc.async-audit.await-termination-seconds:30}") int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("async-audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
