package com.ledgerflow.common.exception;

import com.ledgerflow.ledger.exception.AccountNotFoundException;
import com.ledgerflow.ledger.exception.InsufficientFundsException;
import com.ledgerflow.ledger.exception.UnbalancedJournalException;
import com.ledgerflow.payments.idempotency.IdempotencyConflictException;
import com.ledgerflow.payments.idempotency.IdempotencyPayloadMismatchException;
import com.ledgerflow.payments.service.PaymentService;
import com.ledgerflow.recon.service.ProposalNotApprovableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/forbidden"));
        problem.setTitle("Forbidden");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(PaymentService.PaymentDeclinedException.class)
    public ProblemDetail handlePaymentDeclined(PaymentService.PaymentDeclinedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/payment-declined"));
        problem.setTitle("Payment Declined");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/idempotency-conflict"));
        problem.setTitle("Idempotency Conflict");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(ProposalNotApprovableException.class)
    public ProblemDetail handleProposalNotApprovable(ProposalNotApprovableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/proposal-not-approvable"));
        problem.setTitle("Proposal Not Approvable");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(com.ledgerflow.recon.batch.SettlementReconciliationRunner.AlreadyReconciledException.class)
    public ProblemDetail handleAlreadyReconciled(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/settlement-already-reconciled"));
        problem.setTitle("Settlement File Already Reconciled");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ProblemDetail handlePayloadMismatch(IdempotencyPayloadMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/idempotency-payload-mismatch"));
        problem.setTitle("Idempotency Payload Mismatch");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/insufficient-funds"));
        problem.setTitle("Insufficient Funds");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(UnbalancedJournalException.class)
    public ProblemDetail handleUnbalancedJournal(UnbalancedJournalException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/unbalanced-journal"));
        problem.setTitle("Unbalanced Journal Transaction");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/account-not-found"));
        problem.setTitle("Account Not Found");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setType(URI.create("https://ledgerflow.io/errors/validation-failed"));
        problem.setTitle("Bad Request");
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("invalidParams", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/missing-header"));
        problem.setTitle("Missing Required Header");
        problem.setProperty("headerName", ex.getHeaderName());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/bad-request"));
        problem.setTitle("Bad Request");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://ledgerflow.io/errors/state-conflict"));
        problem.setTitle("State Conflict");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
