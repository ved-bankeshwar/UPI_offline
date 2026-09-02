package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Seeds demo accounts and builds a plaintext mesh packet (fake sender phone). */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    @Autowired private AccountRepository accounts;

    @PostConstruct
    public void seedAccounts() {
        if (accounts.count() == 0) {
            accounts.save(new Account("alice@demo", "Alice", new BigDecimal("5000.00")));
            accounts.save(new Account("bob@demo", "Bob", new BigDecimal("1000.00")));
            accounts.save(new Account("carol@demo", "Carol", new BigDecimal("2500.00")));
            accounts.save(new Account("dave@demo", "Dave", new BigDecimal("500.00")));
            log.info("Seeded 4 demo accounts");
        }
    }

    public MeshPacket createPacket(String senderVpa, String receiverVpa,
                                   BigDecimal amount, int ttl) {
        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa, receiverVpa, amount, Instant.now().toEpochMilli());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(ttl);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setHopCount(0);
        packet.setPayload(instruction);
        return packet;
    }
}
