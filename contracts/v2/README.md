# Kontrak Integrasi Tagihan & Pembayaran — v2

Artefak yang dapat dibaca mesin untuk kontrak asinkron antara aplikasi hulu dan Account
Receivable (AR). Spesifikasi prosa (PDF) menjelaskan konsep dan alasannya; berkas di sini adalah
bentuk yang dipakai kode.

| Berkas | Isi |
|---|---|
| `messages.schema.json` | JSON Schema 2020-12 untuk seluruh message: envelope + payload, satu `$defs` per jenis. Validasi satu message lewat `#/$defs/command.<type>` atau `#/$defs/event.<type>`. |
| `asyncapi.yaml` | Topic, arah, dan pemetaan message ke skema (AsyncAPI 3.0). |
| `examples/` | Contoh message, satu direktori per jenis. `valid-*` lolos skema dan diterima AR; `invalid-*` ditolak skema; `rejected-*` lolos skema tetapi ditolak AR saat diproses. |
| `examples/manifest.json` | Daftar setiap contoh beserta hasil yang diharapkan (`schemaValid`, `rejection`). |

## Memvalidasi

Setiap contoh divalidasi oleh `ContractFixturesTest` di suite AR, sehingga skema dan contoh tidak
dapat saling menyimpang tanpa memerahkan build:

```
mvn -q test -Dtest=ContractFixturesTest
```

Untuk memvalidasi message buatan sendiri dari aplikasi hulu, pakai validator JSON Schema 2020-12
apa pun (Java: `com.networknt:json-schema-validator`; JavaScript: `ajv`; Python: `jsonschema`)
dengan `$ref` ke `messages.schema.json#/$defs/command.<type>`.

## Konvensi yang tidak dapat dinyatakan skema

- **Kunci message** (Kafka key) selalu `debtorCode`, pada semua topic.
- **`idempotencyKey`** stabil terhadap pengulangan maksud yang sama: tanpa timestamp, angka acak,
  atau nomor urut percobaan. AR mengembalikan hasil pertama untuk kunci yang sudah pernah diproses.
- **`eventId`** unik per message fisik; consumer memakainya untuk mengabaikan message yang tiba
  dua kali (Kafka menjamin *at-least-once*).
- **Penolakan semantik** (`DEBTOR_NOT_FOUND`, `PLAN_INVALID` karena jumlah, `DUE_DATE_INVALID`,
  dan sebagainya) tidak dapat dinyatakan skema; AR menjawabnya dengan `invoice.rejected`.
  `SCHEMA_INVALID` adalah kode untuk message yang gagal validasi skema.
- **Jenis message yang tidak dikenal** pada sebuah topic adalah error di sisi consumer, bukan
  sesuatu yang dilewati.

## Nilai contoh

Seluruh kode debitur, nomor tagihan, dan nomor VA dalam `examples/` adalah nilai rekaan yang
dipastikan tidak ada di sistem mana pun.
