package com.ledgerflow.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PciLogMaskingTest {

    private final PciLogMaskingConverter converter = new PciLogMaskingConverter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PciLogMaskingTest.class);

    private ILoggingEvent createEvent(String message) {
        return new LoggingEvent(
                "com.ledgerflow.TestClass",
                logger,
                Level.INFO,
                message,
                null,
                null
        );
    }

    @Test
    @DisplayName("Scrub 16-digit card numbers from log message")
    void masksCardNumbers() {
        ILoggingEvent event = createEvent("Processing payment with card 4111111111111111 for user");

        String scrubbed = converter.convert(event);
        assertThat(scrubbed).doesNotContain("4111111111111111");
        assertThat(scrubbed).contains("[REDACTED_PAN]");
    }

    @Test
    @DisplayName("Scrub formatted card numbers with spaces or dashes")
    void masksFormattedCardNumbers() {
        ILoggingEvent event = createEvent("Received card: 4111-2222-3333-4444 and cvv: 123");

        String scrubbed = converter.convert(event);
        assertThat(scrubbed).doesNotContain("4111-2222-3333-4444");
        assertThat(scrubbed).contains("[REDACTED_PAN]");
        assertThat(scrubbed).contains("cvv: ***");
    }

    @Test
    @DisplayName("Leaves valid tokens untouched")
    void leavesTokensUntouched() {
        ILoggingEvent event = createEvent("Charging token tok_visa_4242 for customer cust_123");

        String scrubbed = converter.convert(event);
        assertThat(scrubbed).isEqualTo("Charging token tok_visa_4242 for customer cust_123");
    }
}
