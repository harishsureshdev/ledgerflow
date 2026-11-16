package com.ledgerflow.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled jobs: the outbox relay (every 500 ms) and the nightly settlement
 * reconciliation. Without this, @Scheduled methods silently never run.
 *
 * <p>On by default. The test profile turns it off so tests drive the relay explicitly and
 * deterministically; {@code OutboxRelaySchedulingIntegrationTest} turns it back on to prove it works.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ledgerflow.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
