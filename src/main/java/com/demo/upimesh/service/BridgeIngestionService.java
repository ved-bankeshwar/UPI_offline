package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Bank door: read plaintext payload, skip duplicates, settle. */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private IdempotencyService idempotency;
    @Autowired private SettlementService settlement;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        try {
            PaymentInstruction instruction = packet.getPayload();
            if (instruction == null) {
                return IngestResult.invalid("?", "missing_payload");
            }

            String packetId = packet.getPacketId();
            if (packetId == null || packetId.isBlank()) {
                return IngestResult.invalid("?", "missing_packet_id");
            }

            if (!idempotency.claim(packetId)) {
                log.info("Duplicate packet {} from {} — dropped",
                        packetId.substring(0, Math.min(8, packetId.length())), bridgeNodeId);
                return IngestResult.duplicate(packetId);
            }

            Transaction tx = settlement.settle(instruction, packetId, bridgeNodeId, hopCount);
            return IngestResult.settled(packetId, tx);
        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            return IngestResult.invalid("?", "internal_error: " + e.getMessage());
        }
    }

    public record IngestResult(String outcome, String packetId, String reason, Long transactionId) {
        public static IngestResult settled(String packetId, Transaction tx) {
            return new IngestResult("SETTLED", packetId, null, tx.getId());
        }
        public static IngestResult duplicate(String packetId) {
            return new IngestResult("DUPLICATE_DROPPED", packetId, null, null);
        }
        public static IngestResult invalid(String packetId, String reason) {
            return new IngestResult("INVALID", packetId, reason, null);
        }
    }
}
