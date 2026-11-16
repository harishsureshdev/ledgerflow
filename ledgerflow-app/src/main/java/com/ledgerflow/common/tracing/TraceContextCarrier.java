package com.ledgerflow.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Saves and restores trace context across an asynchronous hop that goes through the database
 * (the transactional outbox): capture the W3C {@code traceparent} when the event is written,
 * and later run the publish inside a span that continues that trace.
 *
 * <p>Falls back to no-ops when tracing is not configured (e.g. most tests).
 */
@Component
public class TraceContextCarrier {

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContextCarrier(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    /** The current span's W3C traceparent, or null if there is no active trace. */
    public String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get("traceparent");
    }

    /** Runs {@code work} in a new span that is a child of {@code traceParent} (or a fresh trace if null). */
    public <T> T continueTrace(String traceParent, String spanName, Supplier<T> work) {
        Span span = traceParent == null
                ? tracer.nextSpan().name(spanName).start()
                : propagator.extract(Map.of("traceparent", traceParent), Map::get).name(spanName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
