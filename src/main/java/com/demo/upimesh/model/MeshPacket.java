package com.demo.upimesh.model;

/**
 * What hops between simulated phones.
 * Review 1: {@code payload} is plaintext — any device holding this can read it.
 */
public class MeshPacket {

    private String packetId;
    private int ttl;
    private Long createdAt;
    private int hopCount;
    private PaymentInstruction payload;

    public MeshPacket() {}

    /** Copy for one gossip hop: TTL down, hop count up, same payment. */
    public MeshPacket copyForHop() {
        MeshPacket copy = new MeshPacket();
        copy.setPacketId(packetId);
        copy.setTtl(ttl - 1);
        copy.setCreatedAt(createdAt);
        copy.setHopCount(hopCount + 1);
        copy.setPayload(payload);
        return copy;
    }

    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public int getTtl() { return ttl; }
    public void setTtl(int ttl) { this.ttl = ttl; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }

    public PaymentInstruction getPayload() { return payload; }
    public void setPayload(PaymentInstruction payload) { this.payload = payload; }
}
