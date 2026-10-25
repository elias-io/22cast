# 22Cast website and release endpoint

Single-page Astro website at **https://22cast.elias.dev**, deployed as Cloudflare
Workers Static Assets with a small `/api/version` Worker. Public APKs and matching
source archives are served directly as static assets; no R2 bucket is required.
The current downloads fit the 25 MiB per-file asset limit.

## Develop and validate

Use pnpm and the version of Node required by the pinned Astro release.

```sh
pnpm install --frozen-lockfile
pnpm types
pnpm check
pnpm build
pnpm test
pnpm deploy:check
pnpm preview
```

The Worker preview is at `http://127.0.0.1:2246`. `pnpm dev` runs Astro-only development;
use the Worker preview to check the API, download headers and content security policy.

## Publish a release

1. Update the Android `../version.properties` and release notes in `release.json`.
2. Build the Android native libraries if changed, then run
   `python3 scripts/package-android.py --release` from the parent app directory.
3. Run `pnpm build`. It verifies APK identity/version/signature, regenerates matching
   source, copies both downloads, and derives the public manifest and checksums.
4. Run the validation commands above, then `pnpm deploy`.

`signing-certificate.sha256` pins the official APK certificate and prevents a
development-signed APK being published accidentally. Preserve the private release
keystore and signing configuration described in the parent README; neither belongs
in source control or public assets. Increase the version for every changed APK;
versioned download URLs are served with immutable cache headers. Retain older public
download files when publishing subsequent versions.

The custom-domain route in `wrangler.jsonc` creates the hostname/certificate through
Cloudflare. Deployment requires an authorized Cloudflare account with access to the
`elias.dev` zone. No account identifiers or credentials are needed by the website.

## Privacy and store listing

There are no cookies, browser storage, analytics, remote fonts, accounts, forms or
external browser resources. Worker application logging/observability is disabled.
Cloudflare still handles IP/connection data for hosting and security, as the short
policy explains. Do not enable Cloudflare analytics injection, cookie-based bot
challenges or other tracking features for this host without updating that policy.

The app-store button is visibly unavailable until a real Google Play listing exists.
Set `storeUrl` in `release.json` when that listing has been published.

The website’s **Source code** link opens the public
[GitHub repository](https://github.com/elias-io/22cast). Versioned source archives
remain available alongside the matching APK downloads.

Legal contact and operator information are in `src/components/Legal.astro`, using the
owner-supplied business details. Reference sources for the short legal sections:

- [Polish electronic-services law, Articles 5 and 8](https://eli.gov.pl/api/acts/DU/2024/1513/text.html)
- [GDPR, especially Articles 6 and 13](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng)
- [Cloudflare privacy policy](https://www.cloudflare.com/privacypolicy/)
- [Cloudflare cookie documentation](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/cloudflare-cookies/)
- [GPLv3 corresponding-source distribution](https://www.gnu.org/licenses/gpl-3.0.html#section6)

No third-party business-directory scripts or copied directory content are included.
