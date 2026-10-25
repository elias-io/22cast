import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const site = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const project = resolve(site, '..');
const version = Object.fromEntries((await readFile(join(project, 'version.properties'), 'utf8'))
  .trim().split('\n').map((line) => line.trim().split('=')));
if (!/^\d+\.\d+\.\d+$/.test(version.name) || !/^\d+$/.test(version.code)) {
  throw new Error('Invalid Android release version.');
}

const apk = join(project, 'dist', '22Cast.apk');
const sdk = process.env.ANDROID_HOME || join(homedir(), 'Library/Android/sdk');
const tools = join(sdk, 'build-tools/36.1.0');
const badging = execFileSync(join(tools, 'aapt2'), ['dump', 'badging', apk], { encoding: 'utf8' });
const metadata = badging.match(/package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/);
if (!metadata || metadata[1] !== 'dev.elias.cast22' || metadata[2] !== version.code || metadata[3] !== version.name) {
  throw new Error('APK identity/version does not match the release. Rebuild it first.');
}
const certificate = execFileSync(join(tools, 'apksigner'), ['verify', '--print-certs', apk], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const signerHash = certificate.match(/Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})/)?.[1];
const expectedSigner = (await readFile(join(site, 'signing-certificate.sha256'), 'utf8')).trim();
if (signerHash !== expectedSigner) throw new Error('APK is not signed by the official distribution key. Build with --release first.');
execFileSync('python3', ['scripts/package-source.py'], { cwd: project, stdio: 'pipe' });

const destination = join(site, 'public', 'downloads');
await mkdir(destination, { recursive: true });
const files = [
  ['22Cast.apk', `22Cast-${version.name}.apk`],
  ['22Cast-source.zip', `22Cast-${version.name}-source.zip`],
];
const hashes = {};
for (const [input, output] of files) {
  const path = join(project, 'dist', input);
  if ((await stat(path)).size > 25 * 1024 * 1024) {
    throw new Error(`${input} exceeds Cloudflare's static asset limit.`);
  }
  await copyFile(path, join(destination, output));
  hashes[input] = createHash('sha256').update(await readFile(path)).digest('hex');
  await writeFile(join(destination, `${output}.sha256`), `${hashes[input]}  ${output}\n`);
}
const prior = JSON.parse(await readFile(join(site, 'release.json'), 'utf8'));
const release = {
  schemaVersion: 1,
  packageId: metadata[1],
  versionName: version.name,
  versionCode: Number(version.code),
  minSdk: Number(badging.match(/sdkVersion:'(\d+)'/)?.[1] || 24),
  apkUrl: `https://22cast.elias.dev/downloads/22Cast-${version.name}.apk`,
  sha256: hashes['22Cast.apk'],
  signingCertificateSha256: signerHash,
  sourceUrl: `https://22cast.elias.dev/downloads/22Cast-${version.name}-source.zip`,
  sourceSha256: hashes['22Cast-source.zip'],
  releaseNotes: prior.releaseNotes,
  storeUrl: prior.storeUrl || null,
};
await writeFile(join(site, 'release.json'), `${JSON.stringify(release, null, 2)}\n`);
console.log(`Prepared ${release.versionName}: APK, matching source and SHA-256 checksums.`);
