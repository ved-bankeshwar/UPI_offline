# Review 1 — what is built

**Claim:** A payer with no Internet can start a UPI-style payment. Nearby phones carry the packet. A phone that has Internet uploads it to a simulated bank. Money moves only then.

**Honest name:** mesh-routed deferred settlement (PoC). Until flush succeeds, it is an **IOU**, not a confirmed transfer.

**Status of the repo:** this *is* Review 1. Packets are plaintext. There is no encryption in the code.

Dashboard: `http://localhost:8080` (use `http://`, not `https://`).

---

## The problem this review shows

Normal UPI needs the **payer’s phone online** at send time.

This demo shows a different path:

1. Alice is offline (`phone-alice`).
2. She still creates a payment packet.
3. The packet is copied to nearby phones (gossip).
4. `phone-bridge` has Internet and uploads it.
5. The bank debits Alice and credits Bob.

Bluetooth is **not real**. The five “phones” are Java objects in one Spring Boot process. The bank is **not NPCI** — it is an H2 table in the same process.

---

## What you can demo (5 minutes)

1. Open the dashboard. Five devices: alice, stranger1–3 (offline), bridge (4G).
2. Alice → Bob, ₹500. Click **Inject into Mesh**. Packet appears only on `phone-alice`. Balances unchanged.
3. Click **Run Gossip Round**. Same readable payment appears on the other phones, including `phone-bridge`.
4. Click **Bridges Upload to Backend**. Alice −500, Bob +500. A `SETTLED` row in the ledger.
5. Say: any hop can read who paid whom. That is on purpose. Encryption is Review 2.

If you flush **before** gossip, the bridge has no packet → nothing settles.

---

## What is in Review 1 (inventory)

### Running app

| Piece | What it is |
|---|---|
| One Spring Boot process | Java 17, port 8080 |
| Dashboard | `dashboard.html` — HTML + JS, not React |
| Mesh | 5 `VirtualDevice`s in memory |
| Bank | H2 in-memory: accounts + transactions |

Seeded accounts: `alice@demo` ₹5000, `bob@demo` ₹1000, `carol@demo` ₹2500, `dave@demo` ₹500.

### Demo spine (the only flow)

**Inject → Gossip → Flush → Settle**

| Step | Button | What the code does |
|---|---|---|
| Inject | Inject into Mesh | Build a plaintext packet, put it on `phone-alice` |
| Gossip | Run Gossip Round | Copy packets to phones that do not already have that `packetId`; TTL − 1 |
| Flush | Bridges Upload | Only devices with `hasInternet=true` (`phone-bridge`) send packets to ingest |
| Settle | (inside flush) | Debit sender, credit receiver, write ledger |

### Two objects on the mesh

**`MeshPacket`** — envelope that hops

- `packetId` — identity (gossip dedup + “already ingested?”)
- `ttl` — remaining gossip hops (starts at 5)
- `createdAt` — when it was created
- `hopCount` — how many hops so far (0 at inject)
- `payload` — the payment, **readable**

**`PaymentInstruction`** — the payment

- `senderVpa`, `receiverVpa`, `amount`
- `signedAt` — timestamp stored on the ledger row

No PIN. No ciphertext. No nonce.

### Simulated phones

| Device | Internet |
|---|---|
| `phone-alice` | no |
| `phone-stranger1` | no |
| `phone-stranger2` | no |
| `phone-stranger3` | no |
| `phone-bridge` | yes |

A phone is a map of packets keyed by `packetId`. Gossip uses `MeshPacket.copyForHop()` (TTL down, hop count up, same payload).

### Bank behavior

`BridgeIngestionService.ingest`:

1. No payload → `INVALID`
2. No packetId → `INVALID`
3. Same packetId already ingested → `DUPLICATE_DROPPED` (so a second Flush in the demo does not charge twice — do not present this as Review 3)
4. Else `SettlementService.settle`

Settle:

- Unknown VPA or amount ≤ 0 → error
- Not enough balance → ledger row `REJECTED`, balances unchanged
- Else debit, credit, row `SETTLED` (one DB transaction)

Reset Mesh clears packets and the “already seen” ids. **It does not reset balances.**

### Dashboard surfaces

- Device list with plaintext cards (from → to ₹amount)
- Account balances
- Transaction ledger
- Activity log

### HTTP endpoints that exist

| Method | Path | Used by |
|---|---|---|
| GET | `/` | Browser — dashboard page |
| POST | `/api/demo/send` | Inject |
| POST | `/api/mesh/gossip` | Gossip |
| POST | `/api/mesh/flush` | Flush |
| POST | `/api/mesh/reset` | Reset Mesh |
| GET | `/api/mesh/state` | Device cards |
| GET | `/api/accounts` | Balances table |
| GET | `/api/transactions` | Ledger table |
| POST | `/api/bridge/ingest` | Same settle logic, callable as HTTP |
| GET | `/h2-console` | Optional DB UI |

Flush does **not** HTTP-call ingest. It calls `bridge.ingest(...)` in the same JVM. `/api/bridge/ingest` is the same method exposed for a future real bridge.

### Source files that are Review 1

```
src/main/java/com/demo/upimesh/
  UpiMeshApplication.java          start
  config/AppConfig.java            scheduling (claim-map cleanup)
  controller/
    DashboardController.java       GET /
    ApiController.java             /api/*
  service/
    DemoService.java               seed accounts + create packet
    VirtualDevice.java             one fake phone
    MeshSimulatorService.java      5 phones, inject, gossip, flush list
    BridgeIngestionService.java    ingest
    SettlementService.java         money
    IdempotencyService.java        remember packetIds
  model/
    MeshPacket.java
    PaymentInstruction.java
    Account.java + AccountRepository.java
    Transaction.java + TransactionRepository.java

src/main/resources/
  templates/dashboard.html
  application.properties

src/test/java/com/demo/upimesh/Review1MeshTest.java
```

Tests cover: readable packet, gossip copies payload, ingest settles, missing payload rejected, second ingest dropped.

---

## What is **not** in Review 1

Do not claim these. They are not in the current code (or not a Review 1 topic):

| Not included | Notes |
|---|---|
| Encryption | No `crypto/` package. Strangers can read the packet. |
| Tamper detection | Changing amount on a hop would settle the changed amount. |
| Replay / time expiry | `signedAt` is stored, not checked. |
| Three bridges at once | Default seed has **one** bridge. |
| Real Bluetooth / Android | In-memory gossip; all devices treated as in range. |
| Real bank / NPCI / UPI PIN | Simulated ledger; no PIN field. |
| Receiver proof of funds | Offline, Bob cannot know Alice can pay. |
| Offline double-spend | Two different packets can both be created; first to settle wins if funds remain. |
| Durable storage | H2 is in-memory. Restart wipes money and history, then re-seeds accounts. |
| Login / HTTPS | Open HTTP APIs. |

`docs/architecture.md` describes the **later full design** (crypto, ciphertext hash, freshness). Do not present that file as “what Review 1 implements.”

---

## How to run

```text
.\mvnw.cmd spring-boot:run
```

Then `http://localhost:8080`.

```text
.\mvnw.cmd test
```

---

## What to say if they ask “is this UPI?”

No. It is a demo that a payment **instruction** can start offline and settle later when **some** device is online. Security and exactly-once delivery under many bridges are later reviews.

## What to say if they ask “why plaintext?”

So you can **see** the mesh: the same payment on stranger phones. Review 2 is encrypting that payload so hops cannot read or change it.
