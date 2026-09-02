# System Architecture — UPI Offline Mesh

**Status:** Proof of concept  
**Honest name:** Mesh-routed deferred settlement  
**Codebase:** `upi-offline-mesh` (Spring Boot 3.3, Java 17)

This document describes the **system architecture of what has been built**, aligned with the original project design. Where the design intent and the current implementation differ, the difference is called out explicitly rather than papered over.

---

## 1. Problem statement

UPI payments assume the **payer's phone can reach the bank over the Internet** at the moment of payment. That assumption fails in places with no connectivity: basements, rural dead zones, congested events, network outages.

The problem this project addresses:

> How can a payer authorize a UPI-style debit when their own device has no Internet, without broadcasting plaintext payment data, and without settling the same instruction more than once if several nearby devices later deliver it to the bank?

The intended answer is **not** a new real-time payment rail. It is a **deferred settlement path**:

1. The payer's phone encrypts a payment instruction for the bank.
2. The encrypted packet is passed to nearby devices over short-range communication (designed as Bluetooth; implemented as a software mesh).
3. A nearby device that *does* have Internet (a **bridge**) forwards the packet to the bank/backend.
4. The backend decrypts, rejects duplicates and stale packets, then settles once.

Settlement is complete only when the backend processes the packet. Until then, any "₹X sent" indication on a phone is an **IOU**, not a confirmed transfer.

---

## 2. Goals

What this architecture is meant to demonstrate:

| Goal | Why it matters |
|---|---|
| **Offline origination** | The payer does not need Internet at send time. |
| **Store-and-forward mesh** | The request can travel across multiple untrusted devices until one can reach the backend. |
| **Confidentiality on the mesh** | Intermediaries must not read sender, receiver, amount, PIN, or nonce. |
| **Integrity on the mesh** | Tampering with the encrypted payload must be detectable; the backend must refuse to settle a modified packet. |
| **Exactly-once settlement** | If N bridges deliver the same packet, the ledger moves money once. |
| **Replay resistance** | A captured packet must not remain valid forever. |
| **No single intermediary** | Forwarding must not depend on one specific nearby phone. |
| **Demo-ability** | The full path (compose → hop → ingest → settle) must be runnable on one laptop. |

Security and ledger integrity are ranked above convenience. That is a project constraint, not an accidental trade-off.

---

## 3. Non-goals

This is a **PoC**, not a production UPI replacement. The following are out of scope for the current architecture:

- Real NPCI / PSP / bank-core integration
- Real Bluetooth Low Energy, Wi-Fi Direct, or Android/iOS apps
- Instant receiver confirmation of funds (offline double-spend is inherent)
- Hardware-backed offline balance (UPI Lite-style pre-funded wallet)
- Production key management (HSM/KMS); keys are generated in-process
- Authentication of bridge nodes (mTLS, signed device certificates)
- PIN verification against a bank-held credential
- KYC, VPA resolution, mandates, collect requests, or refunds
- Rate limiting, SIEM, or regulatory audit trails
- Multi-instance backend with a shared Redis idempotency store
- A separate "I have Internet, pay the normal UPI way" product path

Calling the system "offline UPI" in a demo is shorthand. Architecturally it is **encrypted mesh delivery of a deferred debit instruction**.

---

## 4. High-level architecture

There are two planes:

1. **Mesh plane** — devices holding and forwarding opaque packets. Designed as phones + Bluetooth. Implemented as in-memory `VirtualDevice`s.
2. **Settlement plane** — the Spring Boot backend that decrypts, deduplicates, and updates a simulated bank ledger.

```
┌─────────────────────────────────────────────────────────────────┐
│                     MESH PLANE (untrusted)                      │
│                                                                 │
│   Sender (offline)     Intermediaries        Bridge (online)    │
│   ┌──────────────┐     ┌─────┐ ┌─────┐     ┌──────────────┐     │
│   │ Encrypt +    │────▶│ hop │─│ hop │────▶│ HTTPS POST   │     │
│   │ inject packet│     └─────┘ └─────┘     │ /bridge/ingest│    │
│   └──────────────┘                         └──────┬───────┘     │
└───────────────────────────────────────────────────┼─────────────┘
                                                    │
                                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                  SETTLEMENT PLANE (trusted)                     │
│                                                                 │
│   hash ciphertext → claim idempotency → decrypt → freshness     │
│                         → transactional debit/credit            │
│                                                                 │
│   HybridCryptoService    IdempotencyService    SettlementService│
│   ServerKeyHolder        H2 accounts + ledger                   │
└─────────────────────────────────────────────────────────────────┘
```

In this repository, **both planes run inside one JVM**. The mesh is a simulator used to drive the real ingest pipeline. The ingest pipeline (`BridgeIngestionService`) is the production-shaped boundary: a real phone with Internet would POST the same `MeshPacket` JSON to `/api/bridge/ingest`.

### 4.1 Design vs implementation

| Layer | Design (transcript) | Current code |
|---|---|---|
| Short-range transport | Bluetooth / nearby devices | `MeshSimulatorService` gossip; all devices treated as in range |
| Payer client | Phone encrypts locally | `DemoService.createPacket()` runs on the server |
| Bank | Separate bank that receives the request | Same Spring Boot process + H2 ledger |
| TTL for replay | Time-to-live so an old packet becomes invalid | Hop-count `ttl` on the mesh; time-based replay uses encrypted `signedAt` |
| Multiple bridges | Many online devices can upload | Default seed has **one** bridge (`phone-bridge`); concurrency is proven in tests |
| Online payment | Implied as "a connected device reaches the bank" | No separate online-UPI API; "online" means a bridge calling ingest |

---

## 5. Components

```
src/main/java/com/demo/upimesh/
├── UpiMeshApplication          Entry point
├── config/AppConfig            Enables scheduling (idempotency eviction)
├── controller/
│   ├── DashboardController     GET /  → dashboard.html
│   └── ApiController           REST surface
├── crypto/
│   ├── ServerKeyHolder         RSA-2048 keypair (regenerated on startup)
│   └── HybridCryptoService     RSA-OAEP + AES-256-GCM; SHA-256 of ciphertext
├── model/
│   ├── PaymentInstruction      Inner payload (after decrypt)
│   ├── MeshPacket              Outer wire format
│   ├── Account / AccountRepository
│   └── Transaction / TransactionRepository
└── service/
    ├── DemoService             Seed accounts; simulate sender phone
    ├── VirtualDevice           One simulated phone
    ├── MeshSimulatorService    Gossip + bridge collection
    ├── IdempotencyService      ConcurrentHashMap claim (≈ Redis SETNX)
    ├── BridgeIngestionService  Ingest pipeline
    └── SettlementService       @Transactional ledger update
```

Supporting runtime pieces:

| Piece | Role |
|---|---|
| Thymeleaf `dashboard.html` | Operator UI for the demo flow |
| H2 `jdbc:h2:mem:upimesh` | In-memory accounts and transaction ledger |
| `application.properties` | Port 8080, idempotency TTL, packet max age |

---

## 6. Component responsibilities

### 6.1 Sender (simulated by `DemoService`)

- Build a `PaymentInstruction`: VPAs, amount, `pinHash`, fresh `nonce` (UUID), `signedAt` (epoch millis).
- Encrypt with the **server public key** using hybrid encryption.
- Wrap ciphertext in a `MeshPacket` (`packetId`, hop `ttl`, `createdAt`, `ciphertext`).
- Inject the packet onto a starting device (`phone-alice` by default).

In a real deployment this logic belongs on the payer's phone, using a public key cached from a previous online session (`GET /api/server-key`).

### 6.2 Virtual device (`VirtualDevice`)

- Identity: `deviceId`.
- Capability: `hasInternet` (bridge vs offline hop).
- State: map of held packets keyed by `packetId` (`putIfAbsent` = local mesh dedup).
- Does **not** decrypt. Does **not** settle.

### 6.3 Mesh simulator (`MeshSimulatorService`)

- Seeds five devices: `phone-alice`, `phone-stranger1..3` (offline), `phone-bridge` (online).
- `inject` — drop a packet onto one device.
- `gossipOnce` — every device forwards packets it held at round start to every other device that does not already hold that `packetId`, decrementing hop TTL. Packets with `ttl <= 0` are not forwarded.
- `collectBridgeUploads` — packets held by devices with `hasInternet=true`.
- `resetMesh` — clear held packets (does not reset ledger balances).

**Not implemented:** BLE discovery, ranging, pairing, or energy/background constraints.

### 6.4 Bridge ingest (`BridgeIngestionService`)

The settlement-plane orchestrator. For each inbound packet:

1. SHA-256 the ciphertext.
2. `IdempotencyService.claim(hash)` — first claimer continues; others return `DUPLICATE_DROPPED`.
3. Decrypt. Failure → `INVALID` / `decryption_failed`.
4. Freshness: `signedAt` must be within `upi.mesh.packet-max-age-seconds` (default 86400) and not more than 300 seconds in the future.
5. `SettlementService.settle(...)`.

Hop `ttl` and outer `createdAt` are **not** consulted here.

### 6.5 Idempotency (`IdempotencyService`)

- In-process `ConcurrentHashMap<String, Instant>`.
- `claim` = `putIfAbsent`; atomic; exactly one winner under concurrent ingest.
- Evicts entries older than `upi.mesh.idempotency-ttl-seconds` every 60 seconds.
- `clear()` is a demo/test helper (`POST /api/mesh/reset`).

Production stand-in: Redis `SET key NX EX 86400`.

### 6.6 Crypto (`HybridCryptoService` + `ServerKeyHolder`)

- RSA-2048 keypair at startup. Private key never leaves the process. Public key via `/api/server-key`.
- Per packet: random AES-256 key, AES-GCM encrypt JSON, RSA-OAEP-SHA256 wrap the AES key.
- Wire blob: `[256-byte wrapped AES key][12-byte IV][ciphertext + 16-byte GCM tag]`, then Base64.
- GCM tag is the integrity check against malicious intermediaries.
- Idempotency key = SHA-256 of the Base64 ciphertext string, not `packetId`.

### 6.7 Settlement (`SettlementService`)

- Load sender and receiver `Account` by VPA.
- Reject non-positive amounts.
- Insufficient funds → persist `Transaction` with status `REJECTED` (packet hash still unique).
- Else debit, credit, persist `SETTLED` in one `@Transactional` unit.
- `Account.@Version` optimistic locking as defense in depth if two threads ever passed the idempotency gate.

This service **is** the simulated bank. There is no downstream payment network.

### 6.8 HTTP (`ApiController` + `DashboardController`)

- Demo/simulator endpoints under `/api/demo/*` and `/api/mesh/*`.
- Production-shaped endpoint: `POST /api/bridge/ingest`.
- Read APIs for dashboard: accounts, transactions, mesh state, server public key.

No authentication or authorization on any endpoint.

---

## 7. Communication paths

| Path | Protocol | Who | What travels | Trust |
|---|---|---|---|---|
| Sender → nearby device | Designed: BLE. Code: in-memory `hold()` | Mesh | Full `MeshPacket` | Untrusted |
| Device → device gossip | Designed: BLE. Code: `gossipOnce()` | Mesh | Copy of packet, hop TTL−1 | Untrusted |
| Bridge → backend | HTTPS POST `/api/bridge/ingest` | Settlement | JSON `MeshPacket` + headers `X-Bridge-Node-Id`, `X-Hop-Count` | Channel is local HTTP in the demo; no mTLS |
| Dashboard → backend | Same origin fetch | Operator | Demo send/gossip/flush/reset | Unauthenticated |
| Phone → public key | Designed: prior online `GET /api/server-key`. Code: `DemoService` uses in-process `ServerKeyHolder` | Crypto | RSA public key (Base64) | Public by design |

There is no device-to-bank path for an offline payer. There is no bank-to-mesh callback. Settlement results are visible only on the backend (dashboard / APIs), not pushed back through the mesh.

---

## 8. Data flow

Two records travel. Intermediaries may read the outer one. Only the backend can read the inner one.

### 8.1 Outer: `MeshPacket` (visible on the mesh)

| Field | Purpose | Tamper note |
|---|---|---|
| `packetId` | UUID for **mesh-local** dedup | A malicious hop can rewrite it. **Not** the server idempotency key. |
| `ttl` | Remaining hops in the simulator | Not enforced at ingest. |
| `createdAt` | Epoch millis when the packet was wrapped | Not used at ingest. |
| `ciphertext` | Base64 hybrid blob | Opaque. Bit-flips fail GCM on decrypt. |

### 8.2 Inner: `PaymentInstruction` (after decrypt)

| Field | Purpose |
|---|---|
| `senderVpa` / `receiverVpa` | Ledger parties |
| `amount` | Debit/credit size |
| `pinHash` | SHA-256 of the PIN string; **recorded, not verified** |
| `nonce` | UUID unique per payment intent so two legitimate ₹100 payments hash differently |
| `signedAt` | Sender time; backend freshness/replay window |

### 8.3 Server-side derived data

| Data | Role |
|---|---|
| `packetHash` | SHA-256 hex of ciphertext; idempotency cache key; unique on `transactions` |
| `bridgeNodeId` / `hopCount` | Provenance on the ledger row; hop count is a header (demo flush uses `5 - remaining ttl`) |
| Account `balance` + `version` | Simulated bank state |
| Transaction `SETTLED` / `REJECTED` | Permanent ledger record |

---

## 9. Offline transaction flow

This is the primary flow the project exists to show.

**Actors:** payer (offline), zero or more offline intermediaries, one or more bridges, backend.

```
Payer phone          Mesh devices              Bridge               Backend
    │                     │                      │                     │
    │ build instruction   │                      │                     │
    │ nonce + signedAt    │                      │                     │
    │ hybrid encrypt      │                      │                     │
    │ wrap MeshPacket     │                      │                     │
    │ TTL=5 (hops)        │                      │                     │
    │                     │                      │                     │
    ├──── inject ────────▶│                      │                     │
    │                     │ gossip (TTL−1)       │                     │
    │                     ├──── hop ────────────▶│                     │
    │                     │                      │  (gets Internet)     │
    │                     │                      ├─ POST /ingest ─────▶│
    │                     │                      │                     │
    │                     │                      │              hash   │
    │                     │                      │              claim  │
    │                     │                      │              decrypt│
    │                     │                      │              age    │
    │                     │                      │              settle │
    │                     │                      │◀── SETTLED/DUP/INV ─┤
```

**Demo mapping (dashboard):**

1. **Inject** — `POST /api/demo/send` creates and encrypts the packet, injects at `phone-alice`.
2. **Gossip** — `POST /api/mesh/gossip` (typically twice so the bridge holds a copy).
3. **Flush** — `POST /api/mesh/flush` collects packets from internet-capable devices and calls `ingest` in parallel.

Until step 3 succeeds with `SETTLED`, balances do not move. The mesh only moves ciphertext.

---

## 10. Online transaction flow

**Design intent:** a nearby device that already has connectivity can forward the request immediately, without waiting for a later "walk outside" event. The backend path is the same as the offline path.

**What the code implements:**

- There is **no** separate online UPI checkout API (no PSP collect, no immediate payer-online debit distinct from mesh ingest).
- "Online" means: a `VirtualDevice` with `hasInternet=true` holds the packet and `POST /api/mesh/flush` (or a client `POST /api/bridge/ingest`) delivers it.
- If the **payer** had Internet, a production app could skip the mesh and POST the same encrypted packet itself. That client is not in this repo; `DemoService` always injects into the mesh first.

So the architecture has **one settlement path** (ingest pipeline) and **two delivery timings** (immediate bridge vs delayed hop-then-bridge). It does not have two different payment products.

---

## 11. Failure scenarios

| Scenario | What happens in code | Settlement? |
|---|---|---|
| No device ever gets Internet | Packet sits on offline devices until hop TTL hits 0 or the process resets | No |
| Hop TTL exhausted in mesh | Packet is kept but not forwarded further | Only if a bridge already holds it |
| Bridge uploads after hop TTL is 0 | Ingest still accepts; hop TTL is not a server check | Possible |
| Multiple bridges, same ciphertext | First `claim` wins; others `DUPLICATE_DROPPED` | Once |
| Concurrent ingest (3 threads) | `putIfAbsent` serializes the claim; test asserts one `SETTLED` | Once |
| Tampered ciphertext | AES-GCM fail → `INVALID` / `decryption_failed` | No |
| Packet older than 24h (`signedAt`) | `INVALID` / `stale_packet` | No |
| `signedAt` > now + 300s | `INVALID` / `future_dated` | No |
| Unknown VPA | `settle` throws; ingest returns `INVALID` / `internal_error` | No (hash already claimed) |
| Amount ≤ 0 | Same as unknown VPA | No (hash already claimed) |
| Insufficient funds | Ledger row `REJECTED`; balances unchanged | No debit; hash consumed |
| Duplicate after reject/settle | `DUPLICATE_DROPPED` | No second row |
| Outer `packetId` rewritten | Mesh may treat it as a new packet; server still keys on ciphertext hash | Still once, if ciphertext identical |
| Idempotency cache miss after eviction (24h) | Cache may accept again; unique index on `packetHash` is the DB fallback | Second insert should fail if the first row still exists. **`existsByPacketHash` is unused; unique-index failure is not specially handled in `settle`.** |
| Claim then decrypt/freshness failure | Hash remains claimed; that exact ciphertext cannot be retried successfully | No. Transient decrypt failure (e.g. key rotation) would brick that packet. |
| Process restart | RSA keypair is new; old ciphertexts will not decrypt. H2 ledger is gone (`create-drop`). Idempotency map is empty. | Demo-only; not durable. |
| Dashboard reset | Clears mesh + idempotency map **only**. Account balances and ledger rows remain. | A previously settled hash can be claimed again after reset; a second `SETTLED` insert can then hit the unique index. |

---

## 12. Security architecture

### 12.1 Trust boundaries

```
  UNTRUSTED MESH                    TRUSTED BACKEND
  ──────────────                    ───────────────
  Any device holding                Process that holds RSA private key
  the MeshPacket                    and the ledger

  Can: store, copy, drop,           Can: decrypt, settle, reject
  delay, rewrite outer fields

  Cannot (if crypto holds):         Must not: leak private key
  read inner payload or             or skip the claim-before-settle gate
  forge a new valid ciphertext
  for a different instruction
```

Bridge nodes are **untrusted for payload content** and **semi-trusted for delivery**. They are not authenticated in this PoC.

### 12.2 Confidentiality

Hybrid encryption to the **server public key**. Intermediaries see only outer routing fields plus opaque ciphertext. AES keys are ephemeral per packet.

### 12.3 Integrity

AES-GCM authentication tag. A one-bit change in the blob causes decrypt to throw. The backend never settles on a MAC failure.

Outer fields (`packetId`, `ttl`, `createdAt`) are **not** authenticated. That is why the idempotency key is the ciphertext hash, not `packetId`.

### 12.4 Duplicate delivery (benign)

Gossip naturally replicates packets. Several bridges may POST the same blob. The claim gate runs **before** decrypt and settle so duplicates are cheap and do not double-debit.

### 12.5 Replay (malicious or delayed)

Two layers, matching the design intent even though TTL is implemented as hops:

| Layer | Mechanism | Where |
|---|---|---|
| Mesh propagation limit | Hop `ttl` decremented on gossip | Simulator only |
| Time freshness | Encrypted `signedAt` vs 24h window | `BridgeIngestionService` |
| Exact-blob replay | Idempotency claim on ciphertext hash | `IdempotencyService` + unique `packetHash` |

A stored packet replayed **inside** the freshness window is caught by idempotency if the original already settled (or was claimed). A packet replayed **after** 24h is `stale_packet` (if it wins the claim) or `DUPLICATE_DROPPED` (if the hash is still cached).

**Transcript mismatch:** the original design described TTL as the primary time-based expiry. In code, time expiry is `signedAt`, not hop `ttl`.

### 12.6 What this architecture does **not** stop

- **Offline double-spend:** two different packets (different nonces) from a payer with ₹500, sent to two receivers before either settles. First ingest to hit a funded account wins; the other is `REJECTED`.
- **False receiver certainty:** the payee cannot cryptographically prove the payer has funds while both are offline.
- **Malicious drop / delay:** intermediaries can refuse to forward or wait until `signedAt` expires.
- **Metadata:** packet existence, size, and `packetId` are visible on the mesh.
- **Unauthenticated ingest:** anyone who can reach `/api/bridge/ingest` can submit well-formed packets (they still cannot forge ciphertexts without the private key, but they can spam or replay captured blobs).
- **PIN:** hashing a PIN into the payload does not authenticate the user; nothing checks it.
- **Private-key compromise:** attacker can decrypt all packets encrypted to that key. Demo keys are regenerated every process start, which also makes old packets undecryptable after restart.

---

## 13. Runtime topology (as built)

Single process, single node:

- Embedded Tomcat `:8080`
- H2 in-memory database
- In-memory mesh and idempotency map
- RSA keypair in heap

This is sufficient to demonstrate the architecture. It is **not** a deployment architecture for a bank. Durable storage, shared idempotency, HSM-backed keys, and authenticated bridges are future/production substitutions, not present components.

---

## 14. How to read the rest of the documentation set

This file is the system architecture. Later documents should go deeper without changing these conclusions:

2. **Detailed design** — packet/encryption/idempotency/TTL/state machines
3. **Diagrams** — sequence and attack scenarios
4. **API / backend** — endpoints and models
5. **Security design** — full threat model
6. **Deployment** — what would change outside a laptop demo
7. **Testing** — what is covered vs what is only described
8. **README / interview story** — narrative form of the same facts

Rule for all of them: **if it is not in the Java sources, it is design intent, not an implemented capability.**
