package com.demo.upimesh.controller;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.MeshSimulatorService;
import com.demo.upimesh.service.VirtualDevice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private DemoService demo;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private IdempotencyService idempotency;

    @PostMapping("/demo/send")
    public Map<String, Object> demoSend(@RequestBody DemoSendRequest req) {
        int ttl = req.ttl == null ? 5 : req.ttl;
        MeshPacket packet = demo.createPacket(req.senderVpa, req.receiverVpa, req.amount, ttl);

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        var p = packet.getPayload();
        return Map.of(
                "packetId", packet.getPacketId(),
                "senderVpa", p.getSenderVpa(),
                "receiverVpa", p.getReceiverVpa(),
                "amount", p.getAmount().toPlainString(),
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        );
    }

    public static class DemoSendRequest {
        public String senderVpa;
        public String receiverVpa;
        public BigDecimal amount;
        public Integer ttl;
        public String startDevice;
    }

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            List<Map<String, Object>> packets = new ArrayList<>();
            for (MeshPacket p : d.getHeldPackets()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("packetId", p.getPacketId().substring(0, 8));
                row.put("ttl", p.getTtl());
                if (p.getPayload() != null) {
                    row.put("senderVpa", p.getPayload().getSenderVpa());
                    row.put("receiverVpa", p.getPayload().getReceiverVpa());
                    row.put("amount", p.getPayload().getAmount().toPlainString());
                }
                packets.add(row);
            }
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("deviceId", d.getDeviceId());
            device.put("hasInternet", d.hasInternet());
            device.put("packetCount", d.packetCount());
            device.put("packets", packets);
            deviceData.add(device);
        }
        return Map.of("devices", deviceData);
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of("transfers", r.transfers(), "deviceCounts", r.deviceCounts());
    }

    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        List<Map<String, Object>> results = new ArrayList<>();
        for (MeshSimulatorService.BridgeUpload up : uploads) {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), up.packet().getHopCount());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bridgeNode", up.bridgeNodeId());
            row.put("packetId", up.packet().getPacketId().substring(0, 8));
            row.put("outcome", r.outcome());
            row.put("reason", r.reason() == null ? "" : r.reason());
            row.put("transactionId", r.transactionId() == null ? -1 : r.transactionId());
            results.add(row);
        }
        return Map.of("uploadsAttempted", uploads.size(), "results", results);
    }

    @PostMapping("/mesh/reset")
    public Map<String, String> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh cleared");
    }

    @PostMapping("/bridge/ingest")
    public ResponseEntity<BridgeIngestionService.IngestResult> ingest(
            @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {
        return ResponseEntity.ok(bridge.ingest(packet, bridgeNodeId, hopCount));
    }

    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }
}
