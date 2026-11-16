package com.ledgerflow.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.repository.PaymentRepository;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real network faults between LedgerFlow and the card processor, injected with Toxiproxy:
 *
 * <pre>  LedgerFlow (real SimProcessorClient + Resilience4j) --HTTP--> Toxiproxy --> FakeCardNetwork</pre>
 *
 * The in-memory TestProcessorClient is switched off, so timeouts, retries and the circuit breaker
 * are the real ones.
 */
@AutoConfigureMockMvc
class ProcessorNetworkFaultIntegrationTest extends AbstractIntegrationTest {

    private static final FakeCardNetwork network;
    private static final ToxiproxyContainer toxiproxy;
    private static final Proxy processorProxy;
    private static final ToxiproxyClient toxiproxyClient;

    static {
        try {
            network = new FakeCardNetwork();
            Testcontainers.exposeHostPorts(network.port()); // lets the Toxiproxy container reach the test JVM
            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0"));
            toxiproxy.start();
            toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
            processorProxy = toxiproxyClient.createProxy("processor", "0.0.0.0:8666",
                    "host.testcontainers.internal:" + network.port());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void processorThroughToxiproxy(DynamicPropertyRegistry registry) {
        registry.add("ledgerflow.test.processor-stub", () -> "false");
        registry.add("ledgerflow.processor.base-url",
                () -> "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666));
        registry.add("ledgerflow.processor.connect-timeout", () -> "500ms");
        registry.add("ledgerflow.processor.read-timeout", () -> "500ms");
        registry.add("resilience4j.circuitbreaker.instances.simProcessor.wait-duration-in-open-state", () -> "1s");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountProvisioningService accountProvisioningService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void healthyNetwork() throws Exception {
        toxiproxyClient.reset(); // re-enable proxies, remove toxics
        network.reset();
        circuitBreakers.circuitBreaker("simProcessor").reset();
    }

    @AfterEach
    void ledgerStillBalances() {
        assertThat(journalEntryRepository.sumAllEntriesAmount()).isZero();
    }

    private ResultActions pay(String merchant, String customer, String key) throws Exception {
        accountProvisioningService.getOrCreateCustomerAccount(customer, "USD", 100_000L);
        String body = objectMapper.writeValueAsString(new CreatePaymentRequest(customer, 2500L, "USD", "tok_visa_4242"));
        return mockMvc.perform(post("/api/v1/payments")
                .header("Idempotency-Key", key)
                .header("X-Merchant-Id", merchant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(jwt().jwt(j -> j.subject(customer)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    @DisplayName("Slow processor (charged, but reply too late): 503 not a decline; retry with same key captures exactly once")
    void unknownOutcomeIsRetryableAndChargesOnce() throws Exception {
        String merchant = "merch_" + UUID.randomUUID(), customer = "cust_" + UUID.randomUUID(), key = "key_" + UUID.randomUUID();
        accountProvisioningService.getOrCreateCustomerAccount(customer, "USD", 100_000L); // opening balance is journaled; count after it
        long entriesBefore = journalEntryRepository.count();

        // Every reply from the processor arrives after our read timeout
        var slow = processorProxy.toxics().latency("slow-reply", ToxicDirection.DOWNSTREAM, 3_000);
        pay(merchant, customer, key)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));

        assertThat(network.requestsReceived()).as("Resilience4j retried (max-attempts 3); regression: a fallback once disabled retries")
                .isEqualTo(3);
        assertThat(network.chargesMade()).as("the processor did charge the card, once despite 3 attempts").isEqualTo(1);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).as("no payment recorded yet").isEmpty();
        assertThat(journalEntryRepository.count()).isEqualTo(entriesBefore);

        // Network recovers; the client retries with the SAME Idempotency-Key
        slow.remove();
        pay(merchant, customer, key).andExpect(status().isCreated());

        assertThat(network.chargesMade()).as("retry replayed the original charge, no second charge").isEqualTo(1);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).hasSize(1);
        assertThat(journalEntryRepository.count()).isEqualTo(entriesBefore + 2);
    }

    @Test
    @DisplayName("Processor unreachable: payments get 503 and are not charged; circuit opens and fails fast; recovers after")
    void processorDownFailsFastThenRecovers() throws Exception {
        String merchant = "merch_" + UUID.randomUUID(), customer = "cust_" + UUID.randomUUID();
        processorProxy.disable();

        for (int i = 0; i < 5; i++) {
            pay(merchant, customer, "key_" + UUID.randomUUID()).andExpect(status().isServiceUnavailable());
        }
        assertThat(circuitBreakers.circuitBreaker("simProcessor").getState().name()).isEqualTo("OPEN");

        long start = System.nanoTime();
        pay(merchant, customer, "key_" + UUID.randomUUID()).andExpect(status().isServiceUnavailable());
        assertThat((System.nanoTime() - start) / 1_000_000).as("open circuit answers without waiting on the network").isLessThan(400);

        assertThat(network.requestsReceived()).isZero();
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customer)).isEmpty();

        processorProxy.enable();
        Thread.sleep(1_200); // circuit moves to half-open after wait-duration-in-open-state
        pay(merchant, customer, "key_" + UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Two merchants using the same Idempotency-Key are two separate charges at the processor")
    void processorKeyIsScopedPerMerchant() throws Exception {
        String sharedKey = "order-1001-" + UUID.randomUUID();
        pay("merch_a_" + UUID.randomUUID(), "cust_" + UUID.randomUUID(), sharedKey).andExpect(status().isCreated());
        pay("merch_b_" + UUID.randomUUID(), "cust_" + UUID.randomUUID(), sharedKey).andExpect(status().isCreated());

        assertThat(network.chargesMade()).isEqualTo(2);
    }
}
