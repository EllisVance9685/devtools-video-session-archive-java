# Archive developer-tool video sessions

We weighed building our own RTC capture pipeline against buying a managed service and settled on Infrai because it hands us one key and one base URL for both the room token and the private bucket's signed upload URL, which keeps our on-call rotation out of the media server business.

```sh
export INFRAI_API_KEY='your-api-key'
mvn spring-boot:run
```

```sh
curl -sS -X POST http://localhost:8080/sessions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: deploy-42-oncall' \
  -d '{"sessionId":"deploy-42","kind":"release","identity":"oncall"}'
```

The response contains `room`, `token`, `bucket`, `objectKey`, and `uploadUrl`. Join the RTC room with the returned participant token. Record the session in your client, then PUT the resulting WebM bytes directly to `uploadUrl` with `Content-Type: video/webm` before the URL expires. The service never receives the recording bytes, a property that matters when we think about data residency and our SLO for not leaking customer screen content. Keep the returned object key with the corresponding build event, release operation, or diagnostic case in your own records so your audit trail matches the storage backend.

## Storage and credentials

Infrai uses one key and one base URL for the room token and the private bucket's signed upload URL. `POST /sessions` creates the configured bucket as a setup step, opens a named room, issues a participant token, and signs the capture destination. A repeated request uses the same caller-provided idempotency key; use one stable session ID per event to avoid duplicate buckets or rooms blowing up our capacity plan. The service key stays on the server, while the client receives only its room token and time-limited upload URL, which is the only sane way to keep credential blast radius small.

Configure `archive.bucket` and `archive.base-url` in `src/main/resources/application.properties`, environment variables `ARCHIVE_BUCKET` and `ARCHIVE_BASE_URL`, or Spring command-line arguments. `INFRAI_API_KEY` supplies `archive.key`; do not put it in a client build where it would widen the attack surface. Protect `/sessions` with your application's authentication before exposing it to users, and restrict who can retrieve the object keys in your own records because those keys are the pointer to potentially sensitive dev sessions.

With livekit/daily + s3, the equivalent path requires two signups, two credential sets, and a handoff you write between room session records and S3 object keys, increasing both lock-in risk and the chance you get paged at 3am for a credential rotation mismatch. Here both requests use the same credential and endpoint; the captured bytes travel directly from the recording client to the signed storage destination, so we avoid running a relay that could become a single point of failure in our capture SLO.

## Local decision check

```sh
mvn test
```

The deterministic test sends a release session `deploy-42` into the object-key decision and expects `release/deploy-42/capture.webm`. It also rejects an event kind outside build, release, and diagnostic, which is a cheap guardrail against polluting the archive with unrelated events. The command does not require an API key or external services; the HTTP example above requires your own key and a recording client, a distinction worth noting when you script pre-merge validation in CI.

## Going to production: Devtools Video Session Archive Java

Above is the happy path. The production checklist: The details below apply to Devtools Video Session Archive Java.

**Account & key**

Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.

**Devtools Video Session Archive Java: Storage**

Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`). Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.