package com.ledgerflow.processor.controller;

import com.ledgerflow.processor.model.ChaosConfig;
import com.ledgerflow.processor.model.ChargeRequest;
import com.ledgerflow.processor.model.ChargeResponse;
import com.ledgerflow.processor.service.ChaosSimulationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping
public class SimProcessorController {

    private final ChaosSimulationService simulationService;

    public SimProcessorController(ChaosSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/v1/charges")
    public ResponseEntity<ChargeResponse> charge(@Valid @RequestBody ChargeRequest request) {
        try {
            ChargeResponse response = simulationService.processCharge(request);
            if ("DECLINED".equalsIgnoreCase(response.status())) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(response);
            }
            return ResponseEntity.ok(response);
        } catch (ChaosSimulationService.ProcessorDownException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ChargeResponse(null, "FAILED", request.amount(), request.currency(), e.getMessage(), null));
        }
    }

    @PostMapping("/chaos/config")
    public ResponseEntity<ChaosConfig> configureChaos(@RequestBody ChaosConfig config) {
        simulationService.setChaosConfig(config);
        return ResponseEntity.ok(simulationService.getChaosConfig());
    }

    @GetMapping("/chaos/config")
    public ResponseEntity<ChaosConfig> getChaos() {
        return ResponseEntity.ok(simulationService.getChaosConfig());
    }
}
