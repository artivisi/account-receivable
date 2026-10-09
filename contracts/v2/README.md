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

## Nota kredit: `invoice.credited`

Pengurangan tagihan ada dua jenis, dan keduanya tidak boleh tertukar:

| Yang terjadi | Perintah | Akibat pada tagihan |
|---|---|---|
| Harganya berubah (potongan, koreksi harga) | `invoice.amended` | Nominal tagihan berubah |
| Kewajibannya tetap, ditutup tanpa uang (beasiswa) | `invoice.credited` | Nominal tagihan tetap; sisa tagihan berkurang |

`reasonCode` wajib: `SCHOLARSHIP`, `DISCOUNT`, atau `CORRECTION`. `SCHOLARSHIP` juga wajib membawa
`reference`, yaitu keputusan yang mendasarinya — beasiswa yang tidak dapat ditelusuri ke sebuah SK
tidak dapat dibedakan dari kesalahan input.

VA-nya menyesuaikan dengan sendirinya: sisa tagihan nol maka charge dibatalkan (`charge.cancelled`),
masih ada sisa maka charge diturunkan nominalnya (`charge.repriced` dengan `reason: CREDIT_NOTE`).
Tanpa itu, tagihan yang sudah ditutup beasiswa tetap ditagih VA-nya dan dibayar untuk kedua kalinya.

Sesudah nota kredit sebagian, `invoiceStatus` pada event bernilai `PARTIALLY_PAID` walaupun tidak ada
uang yang masuk: status menyatakan sisa kewajiban, bukan kas yang diterima. Laporan penerimaan kas
membaca pembayaran, bukan status tagihan.

## Pembayaran di luar gateway: `payment.recorded`

Uang yang tidak melewati Payment Gateway hanya diketahui aplikasi yang menerimanya: tunai di loket,
transfer langsung ke rekening kampus, QRIS, dan kartu di mesin EDC. Sebelum perintah ini ada,
tagihannya tetap terbuka selamanya, dan VA-nya tetap menagih jumlah yang sudah dibayar.

`reference` adalah nomor bukti milik aplikasi pengirim: nomor kuitansi, nomor transaksi QRIS, atau
nomor jurnal transfer. Nomor tersebut menjadi identitas pembayaran di AR pada ruang nama `RECORDED`,
sehingga **boleh sama** dengan nomor jurnal bank. Keunikan dihitung per sumber, tidak secara global.
Nomor bukti yang berulang dengan `idempotencyKey` berbeda ditolak `REFERENCE_DUPLICATE`: pengulangan
perintah sudah dijawab dari penyimpanan idempotensi, sehingga nomor berulang dengan kunci baru
berarti pernyataan berbeda tentang uang yang sama.

`paidAt` menyatakan kapan uangnya diterima. Kuitansi yang dicatat tiga hari kemudian tetap masuk ke
penerimaan kas pada hari uangnya diterima.

VA-nya ikut disesuaikan, lewat jalur yang sama seperti nota kredit. Sisa tagihan nol: charge-nya
dibatalkan (`charge.cancelled`). Masih ada sisa: nominal charge-nya diturunkan sampai sisa tersebut
(`charge.repriced` dengan `reason: PAYMENT_RECORDED`). Tanpa langkah tersebut, tagihan yang sudah
dilunasi di loket masih dibayar untuk kedua kalinya oleh pembayar yang menuruti VA-nya.

Jawabannya adalah **`payment.received` di topic `payment-event-v2`** dengan `source: RECORDED`. Satu
jenis event melayani semua jalur pembayaran, karena aplikasi hulu menaikkan status pembayarnya dari
event tersebut saja. Jenis event kedua hanya akan dibaca aplikasi yang sempat menambahkannya.
Aplikasi lain melewatkannya, dan pembayaran tanpa tindak lanjut adalah kegagalan yang hendak
diakhiri perintah ini. Penolakan tetap `invoice.rejected` di `invoice-event-v2`, seperti pada semua perintah lain.

Kelebihan bayar ditolak `AMOUNT_INVALID`. Jalur gateway menitipkan kelebihan bayar karena bank tidak
dapat diminta mengirim lebih sedikit; di loket, angka yang melebihi utang adalah salah input yang
diperbaiki di tempat.

## Pembayaran yang ditarik kembali: `payment.reversed`

AR sudah dapat membatalkan pembayaran sejak awal, tanpa pernah mengabarkannya. Akibatnya pembayar
yang pembayarannya dibatalkan tetap terbaca lunas di aplikasi hulu, selamanya. Dua jalur sama-sama
membutuhkan kabar ini: banknya membatalkan, atau keuangan membatalkan karena uangnya tercatat pada
tagihan yang salah. Consumer tidak dapat menghitung sendiri kabar tersebut, karena status
pembayarnya dinaikkan oleh `payment.received`, dan tidak ada apa pun di datanya sendiri yang
membantahnya.

`cumulativePaid`, `outstanding`, dan `invoiceStatus` adalah keadaan tagihan **sesudah** pembatalan,
sehingga consumer menimpa nilai simpanannya. `amount` adalah nominal yang ditarik kembali. Seluruh
nilai `invoiceStatus` diizinkan, termasuk `WRITTEN_OFF` dan `CANCELLED`: pembayaran dapat dibatalkan
jauh setelah tagihannya ditutup, dan skema yang menolak keadaan tersebut justru akan menghilangkan
kabarnya.

## Pembayaran membawa `source` sejak revisi 4

`payment.received` kini wajib membawa `source`, dengan nilai `GATEWAY` atau `RECORDED`. Field yang
hanya dimiliki satu jalur **tidak ada** pada jalur lain, dan tidak pernah diisi nilai buatan:

| `source` | Wajib | Tidak ada |
|---|---|---|
| `GATEWAY` | `vaNumber`, `bank` | — |
| `RECORDED` | `channel` | `vaNumber` |

**Yang perlu diperiksa sebelum AR versi ini dipasang.** Aplikasi hulu yang memvalidasi event masuk
terhadap salinan skema sendiri perlu memperbarui salinannya lebih dahulu. Seluruh payload event
memakai `additionalProperties: false`, sehingga field baru pada salinan lama menjadi penolakan, dan
event pembayaran yang ditolak validator berarti pembayaran yang tidak pernah diterapkan. Hal yang
sama berlaku untuk `payment.reversed`: jenis message yang belum dikenal adalah error di sisi
consumer, dan tidak boleh dilewati.

## Nilai contoh

Seluruh kode debitur, nomor tagihan, dan nomor VA dalam `examples/` adalah nilai rekaan yang
dipastikan tidak ada di sistem mana pun.
