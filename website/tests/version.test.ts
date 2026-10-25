import assert from 'node:assert/strict';
import test from 'node:test';
import worker from '../worker/index.ts';
import release from '../release.json' with { type: 'json' };

const noAssets = { ASSETS: { fetch() { throw new Error('Unexpected static-asset access'); } } };

test('the public version endpoint returns the release without reflecting visitor data', async () => {
  const response = await worker.fetch(new Request('https://22cast.elias.dev/api/version?device=ignored', {
    headers: { 'User-Agent': 'test-visitor', Cookie: 'not-used=1', 'X-Device-Id': 'not-used' },
  }), noAssets);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), release);
  assert.equal(response.headers.get('Set-Cookie'), null);
  assert.match(response.headers.get('Cache-Control') || '', /max-age=300/);
  assert.equal(release.packageId, 'dev.elias.cast22');
  assert.match(release.sha256, /^[a-f0-9]{64}$/);
});

test('HEAD provides metadata without a body', async () => {
  const response = await worker.fetch(new Request('https://22cast.elias.dev/api/version', { method: 'HEAD' }), noAssets);
  assert.equal(response.status, 200);
  assert.equal(await response.text(), '');
  assert.match(response.headers.get('Content-Type') || '', /application\/json/);
});

test('unsupported methods and unknown API routes fail explicitly', async () => {
  const post = await worker.fetch(new Request('https://22cast.elias.dev/api/version', { method: 'POST' }), noAssets);
  assert.equal(post.status, 405);
  assert.equal(post.headers.get('Allow'), 'GET, HEAD');
  const missing = await worker.fetch(new Request('https://22cast.elias.dev/api/missing'), noAssets);
  assert.equal(missing.status, 404);
});

test('non-API requests are passed to static assets unchanged', async () => {
  const request = new Request('https://22cast.elias.dev/');
  const assets = { ASSETS: { async fetch(value: Request) {
    assert.equal(value, request);
    return new Response('static');
  } } };
  assert.equal(await (await worker.fetch(request, assets)).text(), 'static');
});
