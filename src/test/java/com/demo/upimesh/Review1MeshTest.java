package com.demo.upimesh;

import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.MeshSimulatorService;
import com.demo.upimesh.service.VirtualDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Review1MeshTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private MeshSimulatorService mesh;

    @BeforeEach
    void clear() {
        idempotency.clear();
        mesh.resetMesh();
    }

    @Test
    void createdPacketCarriesReadablePayment() {
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("500.00"), 5);

        assertNotNull(packet.getPayload());
        assertEquals("alice@demo", packet.getPayload().getSenderVpa());
        assertEquals("bob@demo", packet.getPayload().getReceiverVpa());
        assertEquals(0, new BigDecimal("500.00").compareTo(packet.getPayload().getAmount()));
    }

    @Test
    void gossipCopiesPlaintextPayloadToOtherDevices() {
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("500.00"), 5);
        mesh.inject("phone-alice", packet);
        mesh.gossipOnce();

        VirtualDevice stranger = mesh.getDevice("phone-stranger1");
        assertTrue(stranger.holds(packet.getPacketId()));
        PaymentInstruction seen = stranger.getHeldPackets().iterator().next().getPayload();
        assertEquals("alice@demo", seen.getSenderVpa());
        assertEquals("bob@demo", seen.getReceiverVpa());
        assertEquals(1, stranger.getHeldPackets().iterator().next().getHopCount());
    }

    @Test
    void bridgeIngestSettlesPlaintextPayload() {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();

        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("100.00"), 5);

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, "phone-bridge", 1);
        assertEquals("SETTLED", r.outcome());

        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), aliceAfter);
        assertEquals(bobBefore.add(new BigDecimal("100.00")), bobAfter);
    }

    @Test
    void missingPayloadIsRejected() {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId("no-payload");
        packet.setTtl(5);
        packet.setCreatedAt(System.currentTimeMillis());

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, "phone-bridge", 1);
        assertEquals("INVALID", r.outcome());
        assertEquals("missing_payload", r.reason());
    }

    @Test
    void secondIngestOfSamePacketIsDropped() {
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), 5);

        assertEquals("SETTLED", bridge.ingest(packet, "phone-bridge", 1).outcome());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "phone-bridge", 1).outcome());
    }
}
