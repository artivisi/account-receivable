# Panduan Pengguna — Accounts Receivable

Panduan operasional aplikasi **Accounts Receivable (AR)**: subledger piutang yang mencatat siapa
berutang apa dan menagihnya lewat payment gateway.

Seluruh tangkapan layar (screenshot) di panduan ini dihasilkan otomatis oleh
`ScreenshotCaptureTest` dari data contoh yang di-*seed* melalui API aplikasi — jadi tampilan di
panduan ini persis sama dengan aplikasi yang berjalan. Untuk memperbarui gambar setelah UI berubah:

```
mvn -Dtest=ScreenshotCaptureTest test
```

Gambar tersimpan di `docs/user-manual/screenshots/`.

## Daftar isi

1. [Pendahuluan & Navigasi](01-pendahuluan-dan-navigasi.md) — login, peran, dashboard umur piutang
2. [Master Data](02-master-data.md) — debitur dan tipe faktur
3. [Faktur & Siklus Piutang](03-faktur.md) — terbitkan, cicilan, write-off, nota kredit, unggah massal
4. [Penagihan & Pembayaran](04-penagihan.md) — tagihan VA dan aplikasi kas
5. [Pengingat Tunggakan (Dunning)](05-dunning.md) — menjalankan dan meninjau pengingat
6. [Laporan](06-laporan.md) — umur piutang, rekap per tipe, rekening koran debitur
7. [Audit & Operator](07-audit-operator.md) — log audit dan manajemen pengguna

## Data contoh yang digunakan di panduan ini

**Debitur**

| Kode | Nama | Status | Email |
|------|------|--------|-------|
| DBT-001 | Budi Santoso | ACTIVE | budi.santoso@example.com |
| DBT-002 | Citra Lestari | ACTIVE | citra.lestari@example.com |
| DBT-003 | Dewi Anggraini | ACTIVE | dewi.anggraini@example.com |
| DBT-004 | Koperasi Sejahtera | ACTIVE | admin@koperasi-sejahtera.example.com |
| DBT-005 | Eko Prasetyo | ACTIVE | *(tanpa email)* |
| DBT-006 | Fitri Handayani | INACTIVE | fitri.handayani@example.com |

**Tipe faktur**

| Kode | Nama | Aktif |
|------|------|-------|
| IUR | Iuran Bulanan | ya |
| REG | Biaya Registrasi | ya |
| SEWA | Sewa Fasilitas | ya |
| ARSIP | Tipe Nonaktif | tidak |

**Faktur** (nomor urut otomatis `INV000001`…)

| Nomor | Debitur | Tipe | Nilai | Status | Keterangan |
|-------|---------|------|-------|--------|------------|
| INV000001 | DBT-001 | IUR | 1.500.000 | OPEN | belum jatuh tempo |
| INV000002 | DBT-002 | REG | 2.000.000 | OPEN | menunggak (overdue) |
| INV000003 | DBT-003 | IUR | 1.500.000 | PARTIALLY_PAID | dibayar 400.000, sisa 1.100.000, overdue |
| INV000004 | DBT-004 | SEWA | 3.000.000 | PAID | lunas via webhook |
| INV000005 | DBT-002 | IUR | 500.000 | WRITTEN_OFF | dihapusbukukan |
| INV000006 | DBT-001 | SEWA | 6.000.000 | PARTIALLY_PAID | cicilan 3×2.000.000, cicilan-1 lunas |
| INV000007 | DBT-003 | REG | 1.200.000 | PARTIALLY_PAID | nota kredit 200.000, sisa 1.000.000 |
| INV000008 | DBT-005 | REG | 750.000 | OPEN | menunggak, debitur tanpa email |

Data ini bersifat generik (tanpa nama/klien nyata) sesuai aturan tata kelola engine. Nilai dalam
rupiah penuh; tanggal ditambatkan ke tanggal berjalan agar bucket umur piutang stabil.
</content>
