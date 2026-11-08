# API Gateway

Spring Cloud Gateway untuk `payment-service-project`.

## Kenapa dipisah?

Payment service tetap bisa jalan sendiri di `8080`. Gateway adalah edge layer di `8081`, jadi
kita bisa belajar routing, API key, correlation id, dan CORS tanpa mengubah domain payment.

## Jalankan

```powershell
cd api-gateway
mvn spring-boot:run
```

Pastikan payment service sudah jalan di `http://localhost:8080`.

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health | ConvertTo-Json
```

## Route

| Path | Downstream | Catatan |
|---|---|---|
| `/api/v1/payments/**` | payment service | wajib `X-Api-Key` |
| `/api/v1/webhooks/**` | payment service | bypass API key, tetap HMAC |
| `/sandbox/**` | payment service | demo lokal |

## Filter

- `ApiKeyGatewayFilter` — menolak request tanpa `X-Api-Key` yang valid.
- `CorrelationIdFilter` — membuat/meneruskan `X-Request-Id` dan mencatat durasi request.
- `DedupeResponseHeader` + `globalcors` — CORS untuk frontend lokal.

Payment service tetap memvalidasi API key sendiri. Ini defense in depth, bukan pengganti
security di service.

## Konfigurasi

| Env | Default | Fungsi |
|---|---|---|
| `API_KEY` | `local-dev-key` | API key yang diterima gateway |
| `PAYMENT_SERVICE_URL` | `http://localhost:8080` | alamat downstream |
| `SERVER_PORT` | `8081` | port gateway |

## Test

```powershell
mvn test
```

Test memakai WireMock sebagai downstream dan memverifikasi routing, 401 tanpa API key,
bypass webhook, dan `X-Request-Id`.

## Docker

```powershell
docker build -t payment-api-gateway .
docker run --rm -p 8081:8081 `
  -e PAYMENT_SERVICE_URL=http://host.docker.internal:8080 `
  -e API_KEY=local-dev-key `
  payment-api-gateway
```

Di Linux, `host.docker.internal` bisa diganti dengan IP host atau network yang sesuai.
