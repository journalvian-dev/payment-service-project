# Payment Service Project (Interview Demo)

Backend Java/Spring Boot untuk latihan interview **Back-end Developer (Java)**.
Fokusnya bukan fitur yang banyak, tapi **pola yang disebut di kualifikasi utama**: hexagonal
architecture, idempotency, in-doubt transaction, HMAC, Resilience4j, reconciliation worker,
Flyway, ShedLock, dan automated testing.

> Ini demo yang sengaja dibuat kecil supaya bisa dibaca dan di-debug dalam sekali duduk.
> Bukan production code. Bagian yang disederhanakan dicatat di bagian akhir README.

---

## 1. Peta requirement → implementasi

| Kualifikasi | Ada di kode |
|---|---|
| Java 21 (records, sealed, switch expression) | `Payment`, `PaymentResult`, `IdempotencyDecision`; profile `java21` + virtual threads |
| Spring Boot 3.x | `3.3.5` (Java 17 compatible, siap di-upgrade ke 21) |
| Spring Data JPA | `PaymentJpaEntity`, `PaymentJpaRepository`, `PaymentPersistenceAdapter` |
| Spring Security | `SecurityConfig`, `ApiKeyAuthenticationFilter` |
| Spring WebFlux (WebClient) | `WebClientConfig`, `PartnerHttpClient` |
| Hexagonal / Ports & Adapters | `payment/domain`, `payment/port/in`, `payment/port/out`, `payment/adapter/in`, `payment/adapter/out` |
| Modular monolith | package per module (`payment`, `shared`, `config`, `sandbox`) |
| REST API | `PaymentController` |
| Webhook callback + HMAC SHA-256 | `PartnerWebhookController`, `HmacSigner` |
| Idempotency | `JdbcIdempotencyStore` + unique constraint `idempotency_records` |
| In-doubt transaction | status `IN_DOUBT` + `ReconciliationService` |
| Transactional outbox | `shared/outbox`, `PaymentStateWriter`, `OutboxRelay` |
| Resilience4j | `@Retry` di `PartnerHttpClient`, `@CircuitBreaker` di `PartnerWebClientAdapter` |
| Background reconciliation worker | `ReconciliationJob` (`@Scheduled` + ShedLock) |
| Oracle/PostgreSQL | profile `postgres`; SQL Flyway portable H2/Postgres |
| Flyway migration | `db/migration/V1__init.sql`, `V2__outbox.sql` |
| Redis caching + distributed lock | `application-redis.yml`, `RedisCacheManager`, `RedisLockProvider` |
| JUnit 5 + Mockito | `PaymentApplicationServiceTest` |
| WireMock | `PartnerWebClientAdapterTest`, `PaymentApiIntegrationTest` |
| Testcontainers | `PaymentPersistencePostgresIT` (tag `docker`) |
| ArchUnit | `ArchitectureTest` (domain tidak boleh depend Spring/JPA) |
| Docker | `docker-compose.yml` (Postgres + Redis, opsional) |
| Postman | `postman/Payment-Service-Project.postman_collection.json` |
| CI | `.github/workflows/ci.yml` (Java 17, Java 21, Testcontainers) |

---

## 2. Struktur

```
src/main/java/com/vian/payments
├── PaymentServiceApplication.java
├── config/                 # Security, WebClient, ShedLock, properties, Clock
├── shared/
│   ├── hmac/               # HMAC-SHA256 sign + verify (constant-time)
│   ├── idempotency/        # port + JDBC implementation
│   ├── outbox/             # transactional outbox + relay worker
│   ├── webhook/            # dedupe event webhook
│   └── web/                # RFC 7807 error handler
├── payment/
│   ├── domain/             # Payment, Money, PaymentStatus (pure Java)
│   ├── port/in/            # use case interfaces
│   ├── port/out/           # repository & partner interfaces
│   ├── application/        # orkestrasi use case + reconciliation
│   ├── adapter/in/web/     # controller + DTO request
│   ├── adapter/out/persistence/  # JPA entity + repository + adapter
│   ├── adapter/out/partner/      # WebClient + mapping error
│   └── reconciliation/     # scheduled job + ShedLock
└── sandbox/                # partner palsu untuk demo lokal
```

Aturan dependensi yang dijaga ArchUnit:

```
adapter.in  ->  port.in / application
application ->  port.out / domain
adapter.out ->  port.out / domain
domain      ->  Java only
```

---

## 3. Alur create payment

```
POST /api/v1/payments
  │
  ├─ 1. validasi + hitung hash body
  ├─ 2. Idempotency-Key begin (INSERT ... UNIQUE)
  │      ├─ baru          -> lanjut
  │      ├─ COMPLETED     -> balikan response lama (200, replayed=true)
  │      ├─ IN_PROGRESS   -> 409
  │      └─ hash beda     -> 409
  ├─ 3. simpan Payment(status=PENDING)         (transaksi pendek)
  ├─ 4. panggil partner via WebClient + HMAC   (TANPA menahan transaksi DB)
  │      ├─ 2xx SUCCEEDED -> SUCCEEDED
  │      ├─ 4xx decline   -> FAILED
  │      └─ timeout/5xx   -> IN_DOUBT
  ├─ 5. simpan status akhir + event outbox     (SATU transaksi DB)
  └─ 6. simpan response ke idempotency record
```

Kenapa langkah 4 tidak dibungkus `@Transactional`: menahan koneksi DB selama HTTP call adalah
cara paling cepat menghabiskan connection pool. Ini salah satu hal yang biasanya ditanya interviewer.

### Transactional outbox (langkah 5)

Kalau setelah `payments.save()` kita langsung publish ke Kafka/Rabbit secara terpisah, ada dua
kemungkinan buruk: payment tersimpan tapi event hilang, atau event terkirim tapi payment rollback
(**dual-write problem**). Di demo ini, `PaymentStateWriter.saveAndPublish()` menulis perubahan
payment dan baris `outbox_events` dalam transaksi yang sama.

`OutboxRelay` lalu:

1. Ambil event `PENDING` yang sudah jatuh tempo (batch 50).
2. Publish lewat port `PaymentEventPublisher` (demo: log; production: Kafka/HTTP).
3. Tandai `PUBLISHED`, atau `PENDING` + `next_attempt_at` dengan exponential backoff kalau gagal.
4. Setelah 10 percobaan → `DEAD` (bisa di-alert/requeue manual).
5. Purge event `PUBLISHED` yang lebih tua dari 7 hari.

Relay dilindungi ShedLock (`outbox-relay`) supaya hanya satu node yang jalan. Untuk production
dengan throughput tinggi, ganti `claimBatch` dengan `SELECT ... FOR UPDATE SKIP LOCKED` atau pakai
CDC/Debezium. Consumer tetap harus idempotent karena event bisa terkirim lebih dari sekali
(**at-least-once**).

---

## 4. Cara menjalankan

Prasyarat: **JDK 17** (terpasang di laptop ini) dan Maven.

```powershell
cd D:\VianRepo\payment-service-project
mvn spring-boot:run
```

Aplikasi jalan di `http://localhost:8080`. API key default: `local-dev-key`.

Cek health:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json
```

### Create payment (sukses)

```powershell
$headers = @{
  "X-Api-Key"      = "local-dev-key"
  "Idempotency-Key" = "demo-001"
}

$body = @{
  customerId  = "CUST-1"
  amount      = 100000.00
  currency    = "IDR"
  description = "Invoice #1"
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/payments `
  -Headers $headers -ContentType "application/json" -Body $body | ConvertTo-Json
```

Kirim **request yang sama** dengan `Idempotency-Key` yang sama → response lama dikembalikan,
header `Idempotent-Replayed: true`, dan partner **tidak** dipanggil dua kali.

Ganti body tapi pakai key yang sama → HTTP 409 `IDEMPOTENCY_KEY_REUSED`.

### Skenario bawaan sandbox partner

Partner palsu ada di `sandbox/SandboxPartnerController`. Perilakunya ditentukan 2 digit
terakhir `amount`:

| Amount | Perilaku partner | Hasil di payment |
|---|---|---|
| `100000.77` | delay 3 detik (client timeout 2 detik) | `IN_DOUBT`, nanti di-resolve reconciliation |
| `100000.99` | HTTP 500 | `IN_DOUBT` |
| `100000.55` | HTTP 402 decline | `FAILED` |
| lainnya | HTTP 200 | `SUCCEEDED` |

Contoh in-doubt:

```powershell
$body = @{ customerId="CUST-2"; amount=100000.77; currency="IDR"; description="Timeout" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/payments `
  -Headers @{"X-Api-Key"="local-dev-key"; "Idempotency-Key"="demo-timeout-1"} `
  -ContentType "application/json" -Body $body | ConvertTo-Json
```

Tunggu ±35 detik (job jalan tiap 30 detik, `in-doubt-after=5s`), lalu GET by id.
Status berubah dari `IN_DOUBT` → `SUCCEEDED` lewat reconciliation, bukan retry charge buta.

### Webhook partner

Webhook wajib signature:

```
X-Timestamp: <epoch seconds>
X-Signature: v1=<hex HMAC>
canonical   : POST \n /api/v1/webhooks/partner \n <timestamp> \n SHA256_HEX(body)
```

Ada contoh test yang menghasilkan signature di `PaymentApiIntegrationTest.webhook_...`.
Untuk coba manual, pakai `HmacSigner` lewat test, atau hit endpoint dari test.

---

## 5. Testing

```powershell
mvn test
```

Yang jalan:

- **Unit** — `PaymentApplicationServiceTest` (Mockito): sukses, decline, replay, hash mismatch,
  in-progress, webhook sukses, webhook duplikat.
- **Outbox** — `PaymentStateWriterTest` (payment + event ditulis bersama) dan `OutboxRelayTest`
  (publish sukses, retry + backoff, batch kosong).
- **HMAC** — `HmacSignerTest`: deterministik, tamper body, timestamp kedaluwarsa.
- **WireMock** — `PartnerWebClientAdapterTest`: signature & Idempotency-Key yang dikirim,
  mapping 402 → declined, 500 → exception (di aplikasi jadi UNKNOWN).
- **Integration** — `PaymentApiIntegrationTest`: Spring Security, REST, WebClient, Flyway, H2,
  idempotency, webhook, reconciliation, dan bukti baris outbox ikut tersimpan. Partner diganti WireMock.
- **ArchUnit** — `ArchitectureTest`: domain bebas framework, application tidak import adapter.

Testcontainers (butuh Docker, di-skip otomatis kalau tidak ada Docker):

```powershell
mvn test -Pdockertests
```

`PaymentPersistencePostgresIT` menjalankan Flyway + JPA terhadap PostgreSQL 16 asli.

### CI

`.github/workflows/ci.yml` menjalankan tiga job:

| Job | Isi |
|---|---|
| `test-java17` | `mvn -B verify` (sesuai laptop lokal) |
| `test-java21` | `mvn -B -Pjava21 verify` (virtual threads aktif) |
| `testcontainers-postgres` | `mvn -B -Pjava21,dockertests verify` (PostgreSQL 16 asli) |

---

## 6. Postman collection

Import dua file ini ke Postman:

- `postman/Payment-Service-Project.postman_collection.json`
- `postman/local.postman_environment.json`

Jalankan lewat **Collection Runner** (bukan satu-satu) karena request saling bergantung:

1. Create sukses + replay + conflict (key yang sama).
2. Timeout `.77` → `IN_DOUBT`, simpan `inDoubtId`.
3. Decline `.55` → `FAILED`.
4. Webhook folder menghitung HMAC-SHA256 dari raw body, kirim `X-Timestamp` + `X-Signature`,
   lalu GET payment untuk memastikan `IN_DOUBT` → `SUCCEEDED`.

Script HMAC di Postman memakai `CryptoJS` dan canonical string yang sama dengan `HmacSigner`:
`METHOD \n PATH \n TIMESTAMP \n SHA256_HEX(BODY)`.

---

## 7. PostgreSQL & Redis (opsional)

`docker-compose.yml` menyediakan Postgres + Redis:

```powershell
docker compose up -d
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres,redis"
```

- `postgres` → ganti H2 ke PostgreSQL (`application-postgres.yml`)
- `redis` → cache pakai Redis + ShedLock pakai `RedisLockProvider` (`application-redis.yml`)

Tanpa Docker, default H2 + `ConcurrentMapCacheManager` + ShedLock JDBC tetap jalan.
Itu sengaja supaya demo bisa langsung dijalankan di laptop ini.

---

## 8. Java 21

Laptop ini masih JDK 17, jadi `pom.xml` default ke 17. Kode sudah memakai records, sealed
interface, pattern matching `instanceof`, dan switch expression (semuanya aman di 17).

Kalau JDK 21 sudah terpasang:

```powershell
mvn -Pjava21 spring-boot:run "-Dspring-boot.run.profiles=java21"
```

Profile `java21` menaikkan `release` ke 21; `application-java21.yml` mengaktifkan virtual threads
(`spring.threads.virtual.enabled=true`). Di interview, ini bisa jadi bahan diskusi:
virtual threads vs WebFlux, kapan masing-masing dipakai.

---

## 9. Keputusan desain yang bisa dibahas di interview

1. **Idempotency disimpan di DB, bukan hanya Redis.**
   Unique constraint memberi jaminan atomik lintas node; Redis hanya cache di depannya.
   Response asli tetap ada walau Redis restart/TTL habis.

2. **Hash body disimpan bersama key.**
   Key yang sama dengan body berbeda ditolak (409), bukan dieksekusi ulang. Ini mencegah
   client bug mengubah nominal tanpa mengganti key.

3. **In-doubt bukan retry.**
   Timeout setelah request terkirim berarti status tidak diketahui. Retry charge bisa
   double-charge. Yang benar: query status by reference (yang juga dikirim sebagai
   Idempotency-Key ke partner), lalu reconciliation.

4. **Retry hanya untuk error yang aman.**
   `PartnerHttpClient` retry pada `WebClientRequestException`/timeout. Karena partner diberi
   Idempotency-Key, retry masih aman. Circuit breaker memutus koneksi kalau partner terus gagal.

5. **Tidak ada transaksi DB yang menahan HTTP call.**
   Save PENDING → commit → HTTP → save final. Selain itu, `saveAndFlush` per operasi membuat
   transaksi pendek.

6. **ShedLock, bukan `@Scheduled` polos.**
   Di multi-node, tanpa lock job jalan N kali. `usingDbTime()` menghindari selisih jam antar node.

7. **Webhook diverifikasi dari raw body.**
   Kalau body di-deserialize lalu di-serialize ulang, signature bisa berubah. Dedupe pakai
   `event_id` (primary key) supaya webhook yang dikirim ulang tidak double-apply.

8. **HMAC memakai timestamp + constant-time compare.**
   Timestamp mencegah replay (toleransi 5 menit); `MessageDigest.isEqual` mencegah timing attack.
   Secret di production harus dari vault/KMS, bukan YAML.

---

## 10. Yang sengaja disederhanakan (next step)

- Outbox memakai polling sederhana; production bisa `SKIP LOCKED`, batch lebih besar, atau CDC.
- Belum ada **state machine table/audit trail** (sekarang status ada di kolom payment).
- Belum ada **key rotation** HMAC (butuh key id di header).
- Belum ada **rate limiting**, mTLS, OAuth2 client credentials.
- Idempotency record di-purge setelah 24 jam; production perlu strategi retensi yang eksplisit.
- Sandbox partner hanya untuk demo, bukan pengganti integrasi LOS/ESB/PSP.
- Cache `payment-by-id` memakai `allEntries=true` saat berubah; production bisa evict per key
  atau memakai versi/ETag.
- Registrasi dedupe webhook dan perubahan status + outbox belum satu transaksi; production bisa
  memakai `INSERT ... ON CONFLICT DO NOTHING` + transaksi yang sama.

---

## 11. OpenAPI

Kontrak request/response ada di [`openapi/payment-service.yaml`](openapi/payment-service.yaml).
