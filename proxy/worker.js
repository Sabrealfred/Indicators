/**
 * A shared brain for NeoPal, small enough to read in one sitting.
 *
 * The app can talk to a model two ways: with the player's own key, or through something like
 * this. The second route exists because the first one asks a person to make an account, generate
 * a key and paste it into a settings box before they have seen the feature do anything — and
 * almost nobody does that for a tamagotchi. A shared endpoint is the only version of "let it
 * think" that most people will ever actually try.
 *
 * What makes it awkward is the same thing that makes it useful: the key is yours, the traffic is
 * everyone's. Everything below is about that. This is not a general-purpose proxy and it should
 * not become one — it forwards one shape of request, to one host, with a model it chooses itself
 * and a ceiling it sets itself. A general-purpose proxy in front of your key is a way of giving
 * your key away slowly.
 *
 * Deploy:
 *   npm install -g wrangler
 *   wrangler secret put OPENROUTER_KEY     # paste an OpenRouter key; it never enters this file
 *   wrangler deploy
 *
 * Then paste the URL it prints into the app under Settings, "Shared service".
 *
 * Cloudflare rather than AWS purely because it is the shortest path: no account structure to set
 * up, no roles, a free tier that covers this, and a single file with no build step. Nothing here
 * depends on Cloudflare; it is about eighty lines of standard fetch handling and would port.
 */

/** The only path the app ever calls. Anything else is somebody exploring. */
const PATH = '/v1/chat/completions';

const UPSTREAM = 'https://openrouter.ai/api/v1/chat/completions';

/**
 * Models this endpoint is willing to pay for — free tiers only.
 *
 * The request's own `model` field is ignored rather than checked against this list. Checking
 * invites a race with reality: a model leaves the free tier, the allowlist does not notice, and
 * the first anyone knows is a bill. Choosing here means the endpoint can only ever spend what
 * this list costs, which today is nothing.
 */
const MODELS = [
  'meta-llama/llama-3.3-70b-instruct:free',
  'mistralai/mistral-small-3.2-24b-instruct:free',
  'google/gemma-3-27b-it:free',
];

/** Hard ceiling on a reply, whatever the app asked for. */
const MAX_TOKENS = 320;

/** Bodies larger than this are not briefs about an imaginary animal. */
const MAX_BODY_BYTES = 24_000;

/** Requests per IP per window, when a KV namespace called RATE is bound. */
const RATE_LIMIT = 40;
const RATE_WINDOW_SECONDS = 3600;

export default {
  async fetch(request, env) {
    if (request.method !== 'POST') return deny(405, 'post only');
    if (new URL(request.url).pathname !== PATH) return deny(404, 'no');

    // Counted before anything else, so a flood of malformed requests costs the attacker the
    // same as a flood of well-formed ones. Rejecting first and counting afterwards let someone
    // burn this Worker's own request quota for free without ever touching a counter.
    if (await overRate(request, env)) return deny(429, 'slow down');

    // The declared length is checked *before* the body is read. Reading first meant a 40MB post
    // was fully buffered — and then encoded a second time just to measure it — for about 250MB
    // of heap before the 413 came back, which is past the per-isolate limit. One upload, one
    // crashed isolate, repeatable.
    const declared = Number(request.headers.get('Content-Length'));
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) return deny(413, 'too long');

    const raw = await request.text();
    // Still measured after the fact, in bytes rather than characters, because Content-Length can
    // be absent or lie: these prompts are full of multi-byte punctuation and a character count
    // would let roughly three times as much through as intended.
    if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) return deny(413, 'too long');

    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      return deny(400, 'not json');
    }
    // `JSON.parse('null')` succeeds and returns null, and reading a field off it throws out of
    // the handler — which Cloudflare turns into a 1101 error page rather than the clean 400
    // intended here, and counts against the script's error rate.
    if (!body || typeof body !== 'object') return deny(400, 'not json');
    if (!Array.isArray(body.messages) || body.messages.length === 0) return deny(400, 'no messages');

    // Rebuilt field by field rather than forwarded with edits. A pass-through would carry
    // anything the caller added — a tool definition, a longer ceiling, a different upstream
    // parameter — and the point of this endpoint is that it forwards exactly one known shape.
    const forwarded = {
      model: MODELS[0],
      messages: body.messages.map((m) => ({
        role: m.role === 'system' || m.role === 'assistant' ? m.role : 'user',
        content: String(m.content ?? '').slice(0, 8000),
      })),
      // Clamped at both ends. `Number(-1) || 220` is -1, and `Math.min(-1, 320)` is -1, which
      // the upstream simply rejects — a 502 for the caller instead of an answer.
      max_tokens: Math.min(Math.max(Math.trunc(Number(body.max_tokens)) || 220, 1), MAX_TOKENS),
      temperature: 0.8,
      stream: false,
    };

    let upstream;
    try {
      upstream = await fetch(UPSTREAM, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${env.OPENROUTER_KEY}`,
          'HTTP-Referer': 'https://github.com/Sabrealfred/Indicators',
          'X-Title': 'NeoPal',
        },
        body: JSON.stringify(forwarded),
        signal: AbortSignal.timeout(20_000),
      });
    } catch {
      return deny(504, 'upstream did not answer');
    }

    if (!upstream.ok) {
      // The upstream error text is dropped rather than relayed. It can quote the request back,
      // it sometimes names the account, and the app discards non-2xx bodies anyway — so passing
      // it on could only ever leak something.
      return deny(upstream.status === 429 ? 429 : 502, 'upstream refused');
    }

    // Likewise only the fields the app reads are passed back, so nothing about the account —
    // credits, rate-limit headers, the key's own id — travels to a player's device.
    const answer = await upstream.json();
    const content = answer?.choices?.[0]?.message?.content;
    if (typeof content !== 'string') return deny(502, 'nothing usable');

    return json(200, {
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    });
  },
};

/**
 * True when this caller has had enough for now.
 *
 * Two backings, and they are not equivalent. If a `LIMITER` binding is present it is used, and
 * it is the only one that actually works: Cloudflare's rate limiter is an atomic counter, so a
 * burst is counted once per request.
 *
 * The KV path is a fallback and it is **advisory only**. Its read-modify-write is not atomic, so
 * a concurrent burst all reads zero, all passes, and the counter lands on one — it fails exactly
 * in the case it exists for. Real KV is worse than that description suggests, being eventually
 * consistent across colos with up to a minute of propagation, so even serial requests from
 * different regions will not see each other. It is kept because it does slow a naive serial
 * loop from one address, which is the commonest abuse, and because some accounts have KV and not
 * the limiter. It is not a defence and the README says so.
 *
 * Both fail open on error rather than locking everyone out when a store has a bad minute.
 */
async function overRate(request, env) {
  const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';

  if (env.LIMITER) {
    try {
      const { success } = await env.LIMITER.limit({ key: ip });
      return !success;
    } catch {
      return false;
    }
  }

  if (!env.RATE) return false;
  const window = Math.floor(Date.now() / 1000 / RATE_WINDOW_SECONDS);
  const key = `${window}:${ip}`;
  try {
    const used = Number((await env.RATE.get(key)) ?? 0);
    if (used >= RATE_LIMIT) return true;
    await env.RATE.put(key, String(used + 1), { expirationTtl: RATE_WINDOW_SECONDS * 2 });
    return false;
  } catch {
    return false;
  }
}

/** Errors carry no detail. The app treats every non-2xx the same, and detail only helps a prober. */
function deny(status, note) {
  return json(status, { error: note });
}

function json(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}
