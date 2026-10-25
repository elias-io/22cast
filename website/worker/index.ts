import release from '../release.json' with { type: 'json' };

const apiHeaders = {
  'Content-Type': 'application/json; charset=utf-8',
  'Cache-Control': 'public, max-age=300',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Access-Control-Allow-Origin': '*',
  'X-Robots-Tag': 'noindex',
};

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path === '/api/version') {
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        return Response.json({ error: 'Method not allowed' }, {
          status: 405,
          headers: { ...apiHeaders, 'Cache-Control': 'no-store', Allow: 'GET, HEAD' },
        });
      }
      return new Response(request.method === 'HEAD' ? null : JSON.stringify(release), {
        headers: apiHeaders,
      });
    }
    if (path.startsWith('/api/')) {
      return Response.json({ error: 'Not found' }, {
        status: 404, headers: { ...apiHeaders, 'Cache-Control': 'no-store' },
      });
    }
    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<Env>;
