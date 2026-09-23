# web-receive

The browser receive page (F‑D6, F‑H4 browser path; design §10) and the embedded Ktor CIO server that the phone, or a
desktop for the no-Bluetooth PC path, runs on the link interface (architecture §10.3 as changed by spec change N15).
WP9. Plain JVM library; it depends only on `core:discovery` (for `AppIdentity` and the clock interfaces), as an `api`
dependency because the clocks appear in public constructors.

## Pieces

| Type | Role |
| --- | --- |
| `SharedFile` | What is served: name, size, MIME type, `open(offset)`. `PathSharedFile` for desktops; Android backs it with a content URI. |
| `ReceiveOffer` | Sender name, files, the header summary ("12 photos · 48 MB"); sanitised, unique display names. |
| `ReceiveToken` | The secret in `/t/<token>/`: 12 characters of Crockford base32, typeable. |
| `BrowserApprover` | The phone's "Allow this computer?" (N15), asked once per new browser. |
| `ReceiveSession` | The HTTP contract (routes, approval gate, downloads, zip, download progress, upload, idle tracking). Install with `Application.receiveModule(session)`. |
| `ReceiveServer` | CIO engine bound to one address; stops 60 s after the last transfer (`awaitStopped()` says why). |
| `UploadSink` / `UploadSettings` | "Send files back" (P1) with a session-wide byte cap. `DirectoryUploadSink` for desktops. |
| `zip.StoredZipLayout` / `StoredZipWriter` | The streamed STORED zip with data descriptors and ZIP64 when needed. |
| `mdns.MdnsResponder` | Answers `drop.local` A queries on the link interface; `DnsCodec` is the wire format. |
| `src/main/resources/com/constrivo/drop/web/index.html` | The page: one file, no frameworks, under 30 KB. |

Typical use on the phone:

```kotlin
val session = ReceiveSession(ReceiveToken.generate(), ReceiveOffer(nickname, files), approver, upload)
val server = ReceiveServer(session, linkAddress, port = 8080)
server.start()
val mdns = MdnsResponder(linkAddress, linkInterface).also { it.start() }
showQr(server.url())            // http://drop.local:8080/t/<token>/ ; server.ipUrl() is the fallback line
server.awaitStopped()           // IDLE 60 s after the last transfer, or STOPPED
mdns.close()
```

## Tests

`./gradlew :web-receive:test` runs the unit and Ktor test-host suites: every route, token refusal, the upper-case link
redirect, the approval gate (approved, denied, pending, expired and asked again, second browser, single-use token,
forged cookie), range requests, download progress, the zip read back with
`java.util.zip` (multiple files, an empty file, Unicode names, forced ZIP64 and 65,540 entries), upload cap, idle
shutdown on a fake clock against the real CIO server, the page size gate and AA contrast of every text token, the
DNS codec on real packet bytes plus a fuzz run, the once-per-second multicast limit, a scan of the compiled classes
for fields Android 12 lacks, and a responder test over real multicast sockets (skipped when multicast does not loop
back).

The ZIP64 test with a real 4 GiB entry writes about 4.3 GB and only runs in the nightly job:
`./gradlew :web-receive:test -Pdrop.nightly=true`.

## Browser end-to-end test (Playwright, not in CI)

`e2e/receive.e2e.mjs` starts the server with sample files (`./gradlew :web-receive:e2eServer`, a `JavaExec` task on the
test classpath running `src/test/kotlin/.../web/e2e/E2eServer.kt`, which serves the 12 MB sample at about 3 MB/s;
stop it by writing `stop` to its stdin), then drives headless Chromium: the link typed in upper case, waiting for
approval, the file list and glyphs, a download through `fetch` + `ReadableStream` with the progress bar, downloads the
browser saves itself with the bar fed by the server's `progress` count, the zip checked entry by entry (bytes and
CRC-32), light and dark colours, "Send files back", a second browser being refused, a wrong token, and no console or
CSP errors. Screenshots go to `web-receive/build/e2e/`.

Needs Node 18+ and Playwright with Chromium installed globally. From the repository root:

```sh
NODE_PATH="$(npm root -g)" PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node web-receive/e2e/receive.e2e.mjs
```

`PLAYWRIGHT_BROWSERS_PATH` is wherever the Chromium build lives (the script defaults to `/opt/pw-browsers` when that
exists, and finds a global Playwright without `NODE_PATH`). It prints one line per check and ends with `E2E PASSED`
(exit 0) or `E2E FAILED` (exit 1). Safari, Edge and Firefox (T‑21) and 1 GB at 20 MB/s over a real hotspot (T‑20) are
lab runs.
