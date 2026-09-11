# Native protocol: pairing v2 and clipboard transport

This document describes the current Mac and Android code. “v2” versions the pairing link; clipboard JSON has no new version, origin or sequence field. It supersedes insecure pairing assumptions in the older [protocol overview](architecture/protocol.md). It is not a cloud or offline synchronization protocol.

## Trust and pairing

Every request uses TLS with the Mac's independently verified SPKI SHA-256 pin. The fingerprint is the canonical unpadded base64url encoding of 32 digest bytes (43 characters). Discovery supplies an address, not trust: an mDNS `fp` value cannot authorize pairing. Android requires explicit review and comparison of the complete fingerprint displayed on the trusted Mac, including for a received link. Opening a link does not silently replace the current peer.

The Mac displays a link of this shape; angle-bracket values below are placeholders:

```text
clipsync://pair?v=2&host=<host>&port=<port>&fp=<fingerprint>&secret=<qr-secret>
```

URL query values are percent-encoded. The QR secret is 32 cryptographically random bytes, encoded as canonical unpadded base64url (43 characters). It is sent in a JSON body over the already pinned TLS connection:

```http
POST /pair
Content-Type: application/json

{"secret":"<qr-secret>"}
```

Manual pairing remains compatible with `GET /pair?code=<six-ASCII-digits>`, but only after independently verifying the full pin. There is no permissive first-connection trust fallback. Avoid recording pairing URLs or query strings: both the QR secret and manual code authorize enrollment.

The Mac session lasts at most 300 seconds. Refresh replaces its code and secret, and closing the active window cancels its session. A successful attempt consumes the session for both paths. Ten session attempts are permitted; separate socket-address rate limits apply (five pairing requests per minute per address, twenty globally). Forwarding headers do not establish client identity.

Both paths return JSON string fields `token`, `secret`, and `sig`. Each is canonical standard padded base64 representing 32 bytes (44 characters). `token` is random bearer material. `secret` is the Mac's shared HMAC secret, not the one-time QR secret. `sig` is HMAC-SHA256 of the **decoded raw token bytes**, keyed by that shared secret. The signature checks response consistency; identity comes from the verified TLS pin. Android bounds the response to 4096 bytes and rejects malformed lengths, encodings and signatures. Pairing POST bodies are limited to 1024 bytes.

Host, port (1–65535), pin and code/secret are validated before enrollment. TLS identity, shared-secret and token-store initialization fail closed. Token mutations persist before publication; a persistence failure does not authorize an in-memory-only peer. Revocation invalidates authorization and closes corresponding WebSocket sessions. The shared HMAC secret is not rotated separately for each revoked peer; bearer authorization remains required.

## Authenticated clipboard traffic

Android sends `POST /inject` with `Authorization: Bearer <token>` and:

```text
X-ClipSync-Signature: t=<unix-seconds>, v1=<64-hex-HMAC>
```

The signed bytes are the decimal timestamp, an ASCII period, then the exact UTF-8 request body. HMAC-SHA256 uses the decoded shared secret. The timestamp must be nonnegative and differ from the Mac clock by strictly less than 60 seconds. Duplicate signature parameters, malformed integers and non-64-digit hexadecimal signatures are rejected.

Mac-to-Android events use the pinned, bearer-authenticated `wss://<host>:<port>/ws` connection. Client application messages on that socket are rejected; Android-to-Mac content uses `/inject`. The Mac's 16 KiB inbound WebSocket frame limit does not bound an image frame sent by the Mac. Android validates received payloads, but OkHttp can allocate a WebSocket message before that validation.

Clipboard JSON fields:

| Field | Contract |
| --- | --- |
| `type` | `text` or `image`; generic `file` is rejected |
| `mime` | `text/plain` for text; `image/png` or `image/jpeg` for images |
| `data` | Canonical standard padded base64, no whitespace |
| `ts` | Nonnegative integer Unix milliseconds; age or future offset strictly less than 300,000 ms |
| `nonce` | 1–128 ASCII letters, digits, `_` or `-` |
| `name` | Optional nonempty string, at most 255 UTF-16 units; no slash, backslash, control characters, `.` or `..`; omit when absent |

Decoded UTF-8 text is capped at 1 MiB. Encoded image content is capped at 8 MiB; supported image bytes and declared MIME must agree and decode successfully. Width and height are each 1–8192 pixels, with at most 24,000,000 pixels. The JSON envelope cap is `((8 * 1024 * 1024 + 2) / 3) * 4 + 4096` bytes on the Mac. Timestamp arithmetic is checked without signed subtraction overflow. Local Mac TIFF conversion produces bounded PNG; TIFF is not a wire MIME. On Android 9+, automatic clipboard HEIC/HEIF input is decoded locally with orientation applied and converted to bounded JPEG; HEIC/HEIF are not wire MIME types. The same 8 MiB input/output, 8192-pixel side and 24-megapixel limits apply. Images are applied to the clipboard, not saved as received files in Documents.

Both devices need reasonably synchronized clocks. The HMAC's 60-second window is stricter than payload freshness. Clock rollback can also make the durable replay journal unavailable; do not silently clear the journal to bypass an error.

## Acceptance, effects and replay

Before applying an authenticated payload, the Mac durably records a hash of peer identity plus nonce and a digest of the exact request bytes. Records are retained for 600 seconds, with a capacity of 4096. Unexpired records are not evicted to admit new content. Storage corruption or persistence failure rejects new work before clipboard effects. The journal stores hashes and metadata, not plaintext clipboard bytes or tokens.

A new accepted request proceeds to a fresh authorization/pause/staleness check and clipboard write. Success returns:

```json
{"ok":true,"nonce":"event-id","applied":true}
```

The same peer, nonce and exact body return HTTP 200 with `applied:false`, without applying again. The same peer and nonce with different body bytes return HTTP 409, even if the JSON is semantically equivalent after reformatting. Journal unavailability returns 503. Unauthorized, paused or superseded requests can fail after durable acceptance.

**Accepted is not the same as applied.** A crash or clipboard failure between journal persistence and the write leaves an accepted event that will not be applied by a duplicate request. `applied:false` does not prove an earlier write succeeded. This prevents duplicate effects; it does not provide exactly-once successful delivery or crash recovery of pending content. The current Android sender treats a successful HTTP status as success and does not expose the `applied` distinction to the UI.

Android records outbound nonce identity before dispatch and checks inbound echoes before applying them. Sends are serialized and cancellable, with a fresh current-peer/sync check before execution. There are no automatic HTTP delivery retries. Cancellation or a lost response cannot undo an event already accepted by the Mac, so failure can mean delivery is unconfirmed. Reconnecting a socket is not replaying a failed send.

Android's short-lived latest pending clipboard intent is not an offline queue. Delivery requires a reachable, awake peer; there is no cloud relay, durable outgoing recovery or cross-device total ordering. Long-running interoperability, real provider behavior and physical-device checks remain separate from unit validation; see [native verification](development/native-verification.md).
