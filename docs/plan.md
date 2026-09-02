# Review plan — three staged demos

This project is presented as **one vertical slice that gets harder**, not as “frontend first, security later.”

The same demo path is used in every review:

**Inject into mesh → gossip hops → bridge upload → ledger update**

What changes across reviews is the **guarantee** we claim, not the screen.

The dashboard at `http://localhost:8080` is the presentation surface for all three reviews. It is not the Review 1 deliverable.

---

## How to talk about it

| Review | One-line claim |
|---|---|
| **1** | A payer with no Internet can start a payment; nearby devices carry it; a connected device reaches the bank. |
| **2** | Those nearby devices cannot read or tamper with the payment. |
| **3** | If many devices deliver the same packet, or someone replays an old one, the bank still settles correctly at most once. |

Honest name throughout: **mesh-routed deferred settlement** (PoC, not production UPI).

Until the backend accepts the packet, any “₹X sent” indication is an **IOU**, not a confirmed transfer. Say this in Review 1 so later security work does not look like a surprise.

---

## Review 1 — Visible system (insecure on purpose)

**Goal:** Reviewers understand the problem and can *see* the mesh. Encryption is out of scope for this talk.

### What this review includes

- Problem: UPI needs the payer online; this path does not.
- Actors: offline sender, offline hops, bridge with Internet, simulated bank.
- Dashboard: devices, packet counts, balances, ledger, activity log.
- Working flow: compose payment → inject at `phone-alice` → gossip → flush bridge → Alice debited, Bob credited.
- Clear statement: packets on the mesh are **not** protected yet; a stranger could read or change them. That is the next review.

### What to show (demo script)

1. Open the dashboard. Point at 4 offline phones + 1 bridge.
2. Send ₹500 Alice → Bob. Packet appears on `phone-alice`.
3. Run gossip until `phone-bridge` holds it.
4. Flush bridges. Balances and ledger change.
5. Stop. Do not deep-dive RSA, hashes, or idempotency.

### What this review does **not** include

- Hybrid encryption as a topic (code for it still lives under `crypto/` for Review 2).
- Duplicate-storm / three-bridges-at-once.
- Replay, TTL-vs-time, threat model.
- Module split, Android, real Bluetooth.

### Code vs talk (important)

The **running path is Review 1**: `DemoService` puts a plaintext `PaymentInstruction` on `MeshPacket.payload`. The dashboard shows sender, receiver, and amount on every device that holds the packet. `BridgeIngestionService` settles that payload with no decrypt and no freshness check.

`HybridCryptoService` / `ServerKeyHolder` are **not deleted** — they are unused on the live path so Review 2 can wire them back. A packetId claim still prevents a double Flush from double-debiting in a live demo; do not present that as Review 3.

Do not rip out the backend to make a frontend-only Review 1.

### Suggested artifacts

- Running dashboard demo
- Short architecture sketch (problem, two planes: mesh vs bank)
- This plan + `docs/architecture.md` available if asked; do not present the full architecture doc

---

## Review 2 — Cryptography (confidentiality + integrity)

**Goal:** Untrusted intermediates are safe to use as carriers. Same UI, new guarantee.

### What this review includes

- Why plaintext on the mesh is unacceptable (Review 1 leftover).
- Hybrid encryption: per-packet AES-256-GCM, AES key wrapped with RSA-OAEP (server public key).
- Only the bank holds the private key (`ServerKeyHolder`).
- Intermediaries see outer `MeshPacket` fields (`packetId`, hop `ttl`, `createdAt`) plus opaque `ciphertext`.
- Tamper: flip a bit → GCM fails → ingest returns `INVALID` / `decryption_failed`.
- PIN is hashed into the payload for realism; **it is not verified** (say so if asked).

### What to show (demo script)

1. Repeat Review 1 flow. Point at truncated ciphertext in the log.
2. Explain: strangers can forward this; they cannot read amount/VPAs.
3. Optional: run `tamperedCiphertextIsRejected` (or a dashboard “tamper” control if we add one).
4. Show `GET /api/server-key` as “what the phone cached when it last had Internet.”

### What this review does **not** include (save for Review 3)

- Exactly-once settlement under concurrent bridges
- Replay / freshness window as the main topic
- Production key management (HSM) except as a one-line “what would change”

### Already in the code

- `HybridCryptoService`, `ServerKeyHolder`
- Ingest decrypt-before-settle
- Test: `tamperedCiphertextIsRejected`, `encryptDecryptRoundTrip`

---

## Review 3 — Duplicates, replay, and honesty

**Goal:** Distributed delivery does not corrupt the ledger. Close with limitations.

### What this review includes

- **Idempotency:** SHA-256 of ciphertext claimed with `putIfAbsent` before decrypt/settle. Same blob from N bridges → one `SETTLED`, rest `DUPLICATE_DROPPED`.
- Why the key is ciphertext hash, not `packetId` (outer fields are forgeable).
- Unique index on `transactions.packetHash` as defense in depth.
- **Replay:** encrypted `signedAt` vs 24h window; hop `ttl` only limits mesh spread (server does not reject TTL=0). Call this mismatch out if they read the architecture doc.
- Concurrent test: `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce`.
- **Non-goals / limits:** no real BLE, no NPCI, receiver cannot verify funds offline, offline double-spend with two different nonces, demo keys/H2 are not durable.
- Point to `docs/architecture.md` as the written design.

### What to show (demo script)

1. Inject once, gossip, flush. Settled.
2. Flush again (or reset mesh only if you must re-gossip): duplicate dropped, balances unchanged.
3. Mention the 3-thread test for the “all bridges walk outside at once” story.
4. End on limitations so the project is framed as a PoC.

### Already in the code

- `IdempotencyService`, ingest claim-before-work
- Freshness checks in `BridgeIngestionService`
- Concurrency test

Default dashboard seed has **one** bridge. The three-bridge story is the test (or a future extra-bridge seed), not the default UI.

---

## Mapping to implementation work

| Item | Needed for | Status |
|---|---|---|
| Dashboard + mesh sim + ingest + ledger | All reviews (demo spine) | **Done** |
| Plaintext payload on mesh + visible on dashboard | Review 1 | **Done (current running path)** |
| Hybrid encryption + tamper reject on ingest | Review 2 | **Code present, not wired** |
| Idempotency-on-hash + freshness + concurrency test | Review 3 | **Not on live path** (packetId claim only, for demo hygiene) |
| `docs/architecture.md` | Review 3 / questions | **Done** (describes the *full* design, not Review 1 code) |
| Extra bridges in the default seed | Stronger Review 3 live demo | **Not built** |
| Tamper button on the dashboard | Stronger Review 2 live demo | **Not built** |
| Maven module split (protocol / crypto / bank / mesh) | After Review 3, or never | **Not started** — separate from reviews |

The live demo is Review 1. Reviews 2–3 are re-wiring and tests, not a new UI.

---

## Suggested review checklist

**Before Review 1**

- [ ] App runs; open `http://localhost:8080` (not `https://`)
- [ ] Can complete inject → gossip → flush → balances move
- [ ] Can explain IOU vs settled, and that Bluetooth is simulated

**Before Review 2**

- [ ] Can explain hybrid crypto in 60 seconds
- [ ] Can point at ciphertext in the log
- [ ] Tamper test passes (`.\mvnw.cmd test`)

**Before Review 3**

- [ ] Can explain claim-on-hash and why not `packetId`
- [ ] Concurrency test passes
- [ ] Can name two inherent limits (offline funds proof, double-spend)
- [ ] Architecture doc at hand

---

## What we will not do for these reviews

- Delete the crypto classes (they stay unwired until Review 2)
- Present a frontend-only mock as the system
- Claim production UPI, real BLE, or PIN verification
- Split modules as part of the review story unless a reviewer asks how it would ship
