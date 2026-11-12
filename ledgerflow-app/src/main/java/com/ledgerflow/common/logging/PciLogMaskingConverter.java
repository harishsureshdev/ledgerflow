package com.ledgerflow.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback converter that scrubs PAN (credit card numbers) and CVV from application logs
 * to ensure strict PCI-DSS compliance.
 */
public class PciLogMaskingConverter extends MessageConverter {

    // Regex matching 13 to 19 digit PAN strings with optional spaces or dashes
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11}|\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4})\\b");

    // Regex matching CVV / CVC parameters preserving delimiter
    private static final Pattern CVV_PATTERN = Pattern.compile("(?i)(cvv[2]?|cvc[2]?|security[_-]?code)(\\s*[:=]\\s*)['\"]?\\d{3,4}['\"]?");

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) {
            return message;
        }

        // Mask PANs
        Matcher panMatcher = PAN_PATTERN.matcher(message);
        if (panMatcher.find()) {
            message = panMatcher.replaceAll("[REDACTED_PAN]");
        }

        // Mask CVVs
        Matcher cvvMatcher = CVV_PATTERN.matcher(message);
        if (cvvMatcher.find()) {
            message = cvvMatcher.replaceAll("$1$2***");
        }

        return message;
    }
}
