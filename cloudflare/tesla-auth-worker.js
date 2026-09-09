// Deploy this source to the `tesla-auth` Cloudflare Worker.
// Required bindings:
//   TESLA_CLIENT_SECRET  (Secret)
//   TESLA_SESSIONS       (KV namespace)

const CLIENT_ID = "5323b43a-408e-43d8-943b-a9723d1fd4b9";
const ORIGIN = "https://tesla-auth.dndtnekd.workers.dev";
const REDIRECT_URI = `${ORIGIN}/auth/tesla/callback`;
const AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize";
const TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token";
const FLEET_API = "https://fleet-api.prd.na.vn.cloud.tesla.com";
const SCOPES = "openid offline_access user_data vehicle_device_data vehicle_cmds";

function randomId() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
}

function normalizedPublicKey(value) {
  if (!value?.includes("BEGIN PUBLIC KEY")) return null;
  const encoded = value
    .replace(/-----BEGIN PUBLIC KEY-----/g, "")
    .replace(/-----END PUBLIC KEY-----/g, "")
    .replace(/\s+/g, "");
  if (!encoded) return null;
  const lines = encoded.match(/.{1,64}/g) || [];
  return `-----BEGIN PUBLIC KEY-----\n${lines.join("\n")}\n-----END PUBLIC KEY-----\n`;
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function exchange(env, form) {
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, client_secret: env.TESLA_CLIENT_SECRET, audience: FLEET_API, ...form }),
  });
  if (!response.ok) throw new Error(`Tesla token exchange failed (${response.status})`);
  return response.json();
}

async function sessionFor(env, id) {
  const raw = await env.TESLA_SESSIONS.get(`session:${id}`);
  return raw ? JSON.parse(raw) : null;
}

async function fleet(env, session, path, options = {}) {
  const requestOptions = { ...options, headers: { ...(options.headers || {}), authorization: `Bearer ${session.access_token}` } };
  let response = await fetch(`${FLEET_API}${path}`, requestOptions);
  if (response.status === 401 && session.refresh_token) {
    const refreshed = await exchange(env, { grant_type: "refresh_token", refresh_token: session.refresh_token });
    session.access_token = refreshed.access_token;
    session.refresh_token = refreshed.refresh_token || session.refresh_token;
    await env.TESLA_SESSIONS.put(`session:${session.id}`, JSON.stringify(session), { expirationTtl: 60 * 60 * 24 * 89 });
    response = await fetch(`${FLEET_API}${path}`, requestOptions);
  }
  return response;
}

async function ensureFleetRegistration(env) {
  if (await env.TESLA_SESSIONS.get("fleet_registered")) return;
  if (!normalizedPublicKey(env.TESLA_PUBLIC_KEY)) throw new Error("Tesla public key is not configured");
  const partner = await exchange(env, { grant_type: "client_credentials", scope: "openid vehicle_device_data" });
  const response = await fetch(`${FLEET_API}/api/1/partner_accounts`, {
    method: "POST",
    headers: { authorization: `Bearer ${partner.access_token}`, "content-type": "application/json" },
    body: JSON.stringify({ domain: new URL(ORIGIN).hostname }),
  });
  if (response.status < 200 || response.status >= 300) {
    const detail = (await response.text()).slice(0, 500);
    throw new Error(`Tesla Fleet registration failed (${response.status}): ${detail}`);
  }
  await env.TESLA_SESSIONS.put("fleet_registered", "true");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/.well-known/appspecific/com.tesla.3p.public-key.pem") {
      const publicKey = normalizedPublicKey(env.TESLA_PUBLIC_KEY);
      if (!publicKey) return new Response("Public key not configured", { status: 404 });
      return new Response(publicKey, { headers: { "content-type": "application/x-pem-file; charset=utf-8", "cache-control": "public, max-age=3600" } });
    }

    if (url.pathname === "/auth/tesla/login") {
      try { await ensureFleetRegistration(env); }
      catch (error) { return new Response(`Tesla Fleet setup failed: ${error.message}`, { status: 503 }); }
      const state = randomId();
      const authorize = new URL(AUTH_URL);
      authorize.search = new URLSearchParams({
        response_type: "code", client_id: CLIENT_ID, redirect_uri: REDIRECT_URI,
        scope: SCOPES, state, prompt: "login", prompt_missing_scopes: "true", require_requested_scopes: "true",
      }).toString();
      return new Response(null, { status: 302, headers: {
        location: authorize.toString(),
        "set-cookie": `tesla_oauth_state=${state}; Path=/auth/tesla; HttpOnly; Secure; SameSite=Lax; Max-Age=600`,
      }});
    }

    if (url.pathname === "/auth/tesla/callback") {
      const state = url.searchParams.get("state");
      const code = url.searchParams.get("code");
      const cookieState = request.headers.get("cookie")?.match(/(?:^|;\\s*)tesla_oauth_state=([^;]+)/)?.[1];
      if (!code || !state || !cookieState || state !== cookieState) return new Response("Invalid or expired login request.", { status: 400 });
      try {
        const token = await exchange(env, { grant_type: "authorization_code", code, redirect_uri: REDIRECT_URI });
        const id = randomId();
        await env.TESLA_SESSIONS.put(`session:${id}`, JSON.stringify({ id, access_token: token.access_token, refresh_token: token.refresh_token }), { expirationTtl: 60 * 60 * 24 * 89 });
        return new Response(null, { status: 302, headers: {
          location: `woongpilot://oauth/callback?session_id=${id}`,
          "set-cookie": "tesla_oauth_state=; Path=/auth/tesla; HttpOnly; Secure; SameSite=Lax; Max-Age=0",
        }});
      } catch (error) {
        return new Response(`Tesla sign-in failed. ${error.message}`, { status: 502 });
      }
    }

    const vehicleData = url.pathname.match(/^\/api\/session\/([a-f0-9]{64})\/vehicle\/([A-HJ-NPR-Z0-9]{17})\/data$/);
    if (vehicleData && request.method === "GET") {
      const [, id, vin] = vehicleData;
      const session = await sessionFor(env, id);
      if (!session) return json({ error: "session_not_found" }, 401);
      try {
        const response = await fleet(env, session, `/api/1/vehicles/${vin}/vehicle_data`);
        return new Response(response.body, { status: response.status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
      } catch (error) { return json({ error: "fleet_request_failed", detail: error.message }, 502); }
    }

    const wakeVehicle = url.pathname.match(/^\/api\/session\/([a-f0-9]{64})\/vehicle\/([A-HJ-NPR-Z0-9]{17})\/wake$/);
    if (wakeVehicle && request.method === "POST") {
      const [, id, vin] = wakeVehicle;
      const session = await sessionFor(env, id);
      if (!session) return json({ error: "session_not_found" }, 401);
      try {
        const response = await fleet(env, session, `/api/1/vehicles/${vin}/wake_up`, { method: "POST" });
        return new Response(response.body, { status: response.status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
      } catch (error) { return json({ error: "fleet_request_failed", detail: error.message }, 502); }
    }

    const match = url.pathname.match(/^\/api\/session\/([a-f0-9]{64})\/(vehicles|logout)$/);
    if (match) {
      const [, id, action] = match;
      if (action === "logout" && request.method === "POST") {
        await env.TESLA_SESSIONS.delete(`session:${id}`);
        return json({ ok: true });
      }
      if (action === "vehicles" && request.method === "GET") {
        const session = await sessionFor(env, id);
        if (!session) return json({ error: "session_not_found" }, 401);
        try {
          const response = await fleet(env, session, "/api/1/vehicles");
          return new Response(response.body, { status: response.status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
        } catch (error) { return json({ error: "fleet_request_failed", detail: error.message }, 502); }
      }
    }
    return new Response("WoongPilot Tesla authentication service", { headers: { "content-type": "text/plain; charset=utf-8" } });
  },
};
