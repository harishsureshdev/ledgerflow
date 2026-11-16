package com.ledgerflow.investigator.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The tools the language model may call while investigating a mismatch.
 *
 * <p>Core Principle 4: AI NEVER MOVES MONEY. Every method here is a read-only lookup that
 * delegates to {@link InvestigatorReadOnlyTools}. There is deliberately no tool that writes,
 * approves or posts anything; a test asserts this class exposes no mutating methods.
 */
@Component
public class InvestigatorAiTools {

    private final InvestigatorReadOnlyTools readOnlyTools;

    public InvestigatorAiTools(InvestigatorReadOnlyTools readOnlyTools) {
        this.readOnlyTools = readOnlyTools;
    }

    @Tool(description = "Look up a payment by its id/reference. Returns its status, amount (minor units), "
            + "currency, merchant and creation time, or null if no such payment exists.")
    public InvestigatorReadOnlyTools.PaymentTrail lookupPayment(
            @ToolParam(description = "Payment id or reference from the settlement line") String paymentReference) {
        return readOnlyTools.getPaymentTrail(paymentReference).orElse(null);
    }

    @Tool(description = "List the double-entry journal entries posted for a payment reference. "
            + "Amounts are signed minor units; the entries of one transaction sum to zero. Empty if none.")
    public List<InvestigatorReadOnlyTools.LedgerEntryView> lookupLedgerEntries(
            @ToolParam(description = "Payment reference") String paymentReference) {
        return readOnlyTools.getLedgerEntries(paymentReference);
    }

    @Tool(description = "List webhook events received from the card processor that mention a payment reference. "
            + "Duplicate processor events are expected and are deduplicated by processor event id.")
    public List<InvestigatorReadOnlyTools.ProcessorEventView> lookupProcessorEvents(
            @ToolParam(description = "Payment reference") String paymentReference) {
        return readOnlyTools.getProcessorEvents(paymentReference);
    }

    @Tool(description = "Fetch the reconciliation item (bank settlement line vs ledger) by its id.")
    public InvestigatorReadOnlyTools.SettlementLineView lookupSettlementLine(
            @ToolParam(description = "Reconciliation item id (UUID)") String reconItemId) {
        try {
            return readOnlyTools.getSettlementLine(UUID.fromString(reconItemId)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
