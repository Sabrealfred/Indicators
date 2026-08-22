# The shared brain

A small endpoint that lets NeoPal think without every player having to make an account first.

The app can reach a model two ways. A player can paste their own key, which is the honest default
and costs you nothing — but it asks somebody to sign up for a service, generate a key and find a
settings screen *before* they have seen the feature do anything at all. Almost nobody does that
for a tamagotchi. This is the other way: one key, yours, behind an endpoint that will only spend
it on one thing.

Neither route is required. With no key and no endpoint the creature still lives its whole life,
decides for itself, learns from its parents and grows a personality — all of that is local and
always has been. This only adds the talking.

## Deploying it

```
npm install -g wrangler
wrangler secret put OPENROUTER_KEY     # paste a key from openrouter.ai
wrangler deploy
```

Paste the URL it prints into the app: **Settings → Let it think → Shared service**.

Cloudflare rather than AWS because it is the shortest path to a working endpoint — no account
structure, no roles, a free tier that covers this, one file and no build step. Nothing in
`worker.js` depends on Cloudflare beyond the KV binding; it is standard `fetch` handling and
would port to a Lambda behind a function URL, or to anything else, in an afternoon.

## What it will and will not do

It is not a general-purpose proxy and it must not become one. A general-purpose proxy in front of
your key is a way of giving your key away slowly. It forwards exactly one shape of request:

- **One path, one method.** `POST /v1/chat/completions`. Everything else is 404 or 405, and never
  reaches the upstream.
- **It picks the model, not the caller.** The request's `model` field is ignored outright rather
  than checked against a list. Checking races reality: a model leaves the free tier, the list does
  not notice, and the first you hear about it is a bill. Choosing means this endpoint can only
  ever spend what `MODELS` costs, which today is nothing.
- **It sets the ceiling.** `max_tokens` is capped whatever was asked for.
- **The body is rebuilt field by field**, not forwarded with edits. Anything the caller added — a
  tool definition, an unexpected parameter, a `system` role that was not a system role — is
  dropped rather than passed along.
- **Nothing about your account travels back.** Only the reply text is returned. Not the upstream
  error body, which can quote the request and sometimes names the account; not credit headers;
  not rate-limit headers. The app discards non-2xx bodies anyway, so relaying them could only
  ever leak something.
- **Size is measured in bytes**, not characters. These prompts are full of multi-byte punctuation
  and a character count would let roughly three times as much through.

## Rate limiting, and being honest about it

Rate limiting only works if you bind a KV namespace:

```
wrangler kv namespace create RATE
```

then uncomment the `kv_namespaces` block in `wrangler.toml` and paste the id.

**Without it the endpoint is unlimited.** That is deliberate and it is not a safe default — it is
a choice between failing open and failing shut, and an endpoint that locks every player out
because a storage backend had a bad minute is worse than one that is briefly generous. The same
reasoning applies inside the limiter: a KV error is treated as "not limited" rather than
"limited".

If you share the URL publicly, bind the namespace. The limit is per IP per hour and both numbers
are constants at the top of `worker.js`.

One thing a rate limit cannot fix: a shared endpoint is a shared quota. A free tier is a small
number of requests a day across everyone using it, so a popular shared brain runs out and every
player sees the same thing — a creature that has gone quiet. That is why the app treats a failed
call as completely ordinary and why the local brain is the default rather than the fallback.

## Tests

```
node worker.test.mjs
```

Twelve tests, no key and no network needed — the upstream `fetch` is stubbed. They cover the
things that cannot be checked by reading: that the key goes up and never comes back, that an
upstream error naming the account does not reach a player's screen, that a caller cannot choose
the model or the ceiling or smuggle a field through, and that a refused request costs nothing.

The first test asserts the response is byte-for-byte the shape the Kotlin client parses, which is
what makes the app's own socket tests (`RemoteMindOverTheWireTest`) evidence about this worker and
not only about a stub.

These tests are not in CI — CI builds an Android APK and has no Node step. Run them by hand when
you change the worker.

## What has never been done

Nobody has deployed this. It has never been run by `wrangler`, never reached OpenRouter, and
never answered a real device. What is tested is its logic against a stubbed upstream. The parts
that remain unproven are the ones only a deploy can prove: whether `wrangler.toml`'s
compatibility date suits your account, whether OpenRouter accepts the referer header from
Cloudflare's egress, and what the free tier actually allows in a day.
