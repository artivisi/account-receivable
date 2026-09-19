# Admin UI (Thymeleaf + HTMX + Tailwind)

Server-rendered admin UI under `/admin/**`, protected by Spring Security form login.
API (`/api/**`) and the gateway webhook stay open here (API auth is the separate OAuth2 phase).

Layout: a dark navy **sidebar rail** (`fragments :: sidebar(active)`) + light content area. Pages
set `md:pl-60` on `<body>` so content clears the fixed rail. Palette is navy (`#28527a` primary /
`#141926` rail) with an IBM Plex Sans/Mono type family — its own theme, intentionally diverging from
the payment-gateway sibling's indigo/green (a deliberate product decision, not an oversight; each
product keeps its own palette). The rail groups nav links into **Piutang** (Faktur, Unggah Massal),
**Penagihan** (Tagihan VA, Aplikasi Kas, Dunning), **Master Data** (Debitur, Tipe Faktur), and
**Lainnya** (Laporan, Log Audit, Operator). Admin UI labels — nav, page copy, buttons — are in
Indonesian; raw codes/enums (invoice status values, gateway charge types, etc.) stay in their
English/Latin form.

## Deployment branding (keep the engine generic)

Per governance, no client logo/palette is committed here. A deployment re-brands **without a rebuild**
by shadowing two files from an external static location listed before `classpath:/static/`:

```
spring.web.resources.static-locations=file:/opt/appname/branding/,classpath:/static/
```

- `/img/logo.svg` — the brand mark (shown in a light chip in the rail + on login, so any color works).
- `/css/branding.css` — redefine Tailwind theme vars (`--color-primary-600`, …) to re-theme.

The engine ships a neutral generic `logo.svg` and an empty `branding.css` (documented inline).
"Developed by ArtiVisi" stays in the rail footer + login.

## Tailwind CSS

Source: `src/main/tailwind/app.css` (theme + component classes). Output (committed):
`src/main/resources/static/static`… → `src/main/resources/static/css/app.css`.

Regenerate after changing templates or the input CSS (uses the standalone Tailwind v4 CLI; the
binary is downloaded, not committed — `.tools/` is gitignored):

```
tailwindcss -i src/main/tailwind/app.css -o src/main/resources/static/css/app.css --minify
```

The generated `app.css` IS committed so the build/runtime needs no Node/Tailwind toolchain.

## Auth bootstrap

`ar.admin.username` / `ar.admin.password` (required env config) seed an ADMIN user on first start
if absent. Additional users are managed under `/admin/operators` (ADMIN only).

## UI tests & locators

Playwright UI tests live in `src/test/java/.../ui` (`PlaywrightTestBase` + one `*UiTest` per
screen), booting the full app on Testcontainers PostgreSQL with the gateway/GL stubs. **Locator
rule: address elements by stable `id`** (e.g. `#invoice-form`, `#debtor-row-DBT-001`) — no
CSS-class or positional-xpath locators. Every template carries ids for this reason; keep them stable
when editing markup. Row ids are data-derived (`th:id="'debtor-row-' + ${d.code}"`).

Preconditions are set up through the open `/api/**` endpoints via `support/ApiClient` rather than by
clicking through the UI.

## User manual + screenshots

`docs/user-manual/` is an Indonesian operator manual whose images come from `ScreenshotCaptureTest`:
it truncates the DB, seeds a deterministic demo dataset (`support/ScreenshotSeedData`), then walks
every screen writing PNGs into `docs/user-manual/screenshots/`. Regenerate after UI changes:

```
mvn -Dtest=ScreenshotCaptureTest test
```
