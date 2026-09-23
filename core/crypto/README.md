# core/crypto

Identity keys, the handshake, frame encryption and the QR payload (architecture §6, §7.1, §13, as changed by
N1–N3, N11, S3 and S7 in `docs/implementation-plan.md`; the notes at the top of architecture §6 and §13 give the
wire format and key schedule).

Package `com.constrivo.drop.core.crypto`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this.

| Package | What | Main types |
| --- | --- | --- |
| `crypto` | Primitives and identity | `CryptoProvider` (+ `JcaCryptoProvider`), `Aead`, `AeadAlgorithm`, `IdentityKey`, `SoftwareIdentityKeyStore`, `SecretStorage`, `InMemorySecretStorage`, `deviceId()`, `CryptoException` |
| `crypto.handshake` | Commit-then-reveal handshake as pure state machines | `HandshakeInitiator`, `HandshakeResponder`, `HandshakeResult`, `LocalPeerInfo`, `ExpectedPeer`, `TrustedPeerLookup`, `HandshakeException` / `HandshakeFailure` |
| `crypto.frame` | Per-stream AEAD with counter nonces | `FrameCipher`, `FrameLimitException` |
| `crypto.qr` | Signed "scan to send" payload | `QrPayloadCodec`, `QrPayload`, `QrLink`, `QrPayloadException` / `QrFailure` |
| `crypto.trust` | Pairing proofs and the shared advertising secret | `TrustedProof`, `AdvertisingSecret`, `AdvertisingSecretStore` |
| `crypto.cbor` | Deterministic CBOR for signed messages | `DeterministicCbor`, `MalformedCborException` |

A session in five lines:

```kotlin
val a = HandshakeInitiator(crypto, identity, LocalPeerInfo.forDevice(crypto, caps, "Pixel", platform = 0))
val hello = a.start()                       // → peer
val reveal = a.receiveHelloAck(helloAck)    // ← peer's HelloAck, → reveal
val session = a.result                      // keys, SAS, verified peer info
val control = session.frameSender(0)        // first frame: control.seal(session.localFinished(), header)
```

Golden vectors for every message, the key schedule and the QR payload are in `src/jvmTest`; change them only
together with the architecture notes.
