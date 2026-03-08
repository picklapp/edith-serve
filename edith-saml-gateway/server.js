const express = require('express');
const session = require('express-session');
const bodyParser = require('body-parser');
const { createProxyMiddleware } = require('http-proxy-middleware');
const path = require('path');
const fs = require('fs');

// ============================================================
// Configuration
// ============================================================
const PROXY_PORT = 3000;                          // Takes over edith-core-ui's port
const ADMIN_PORT = 6000;                          // Admin dashboard
const CORE_UI_URL = 'http://localhost:3001';      // edith-core-ui (moved internally)
const CORE_BACKEND_URL = 'http://localhost:9090'; // edith-core-backend

// Load provider configuration
const config = JSON.parse(fs.readFileSync(path.join(__dirname, 'config', 'providers.json'), 'utf8'));

// In-memory assertion log
const assertionLog = [];
const MAX_LOG_ENTRIES = 200;

function logAssertion(entry) {
  assertionLog.unshift({
    ...entry,
    timestamp: new Date().toISOString(),
    id: `alog-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  });
  if (assertionLog.length > MAX_LOG_ENTRIES) {
    assertionLog.length = MAX_LOG_ENTRIES;
  }
}

function findSp(id) {
  return config.spProviders.find(p => p.id === id);
}

// Helper: check health of a service
async function checkHealth(url) {
  if (!url) return 'unknown';
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 2000);
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timeout);
    return res.ok ? 'up' : 'down';
  } catch {
    return 'down';
  }
}

// ============================================================
// PROXY SERVER (port 3000)
// Transparent reverse proxy in front of edith-core-ui.
// External systems (banks, RDC, ACH) continue to POST to
// localhost:3000 — they see no difference.
// ============================================================
const proxy = express();

// --- Inbound SAML: Bank IdP -> Gateway (port 3000) -> Core ---
// Banks POST SAMLResponse to localhost:3000/saml/acs (unchanged).
// Gateway validates, logs, then relays to core-ui to establish session.
proxy.post('/saml/acs',
  bodyParser.urlencoded({ extended: true, limit: '5mb' }),
  async (req, res) => {
    try {
      const samlResponse = req.body.SAMLResponse;
      if (!samlResponse) {
        throw new Error('Missing SAMLResponse');
      }

      // Validate assertion via core backend
      const validateRes = await fetch(`${CORE_BACKEND_URL}/api/saml/acs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ samlResponse })
      });
      const data = await validateRes.json();

      if (!validateRes.ok) {
        logAssertion({
          direction: 'inbound',
          from: 'unknown-idp',
          to: 'edith-core',
          status: 'failed',
          error: data.error || 'Validation failed',
          user: null
        });
        throw new Error(data.error || 'SAML validation failed');
      }

      logAssertion({
        direction: 'inbound',
        from: data.attributes?.sourceIdp || 'external-idp',
        to: 'edith-core',
        status: 'success',
        user: data.email,
        displayName: data.displayName
      });

      // Relay to core-ui (internal port) to establish session
      const coreRes = await fetch(`${CORE_UI_URL}/saml/acs`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          cookie: req.headers.cookie || ''
        },
        body: `SAMLResponse=${encodeURIComponent(samlResponse)}`,
        redirect: 'manual'
      });

      // Forward Set-Cookie from core-ui so the browser gets the session
      const setCookies = coreRes.headers.getSetCookie ? coreRes.headers.getSetCookie() : [];
      for (const cookie of setCookies) {
        res.append('Set-Cookie', cookie);
      }

      // Core-ui redirects to /dashboard — follow that redirect through the gateway
      const location = coreRes.headers.get('location') || '/dashboard';
      res.redirect(location);
    } catch (err) {
      console.error('Gateway ACS Error:', err);
      res.status(400).send('Gateway SSO Error: ' + err.message);
    }
  }
);

// --- Outbound SAML: Core -> Gateway -> SP ---
// User clicks "Open RDC" on the dashboard (GET /saml/sso/rdc).
// Gateway logs the event, then proxies to core-ui which handles
// SAML generation and renders the auto-post form to the SP.
proxy.get('/saml/sso/:spName', (req, res, next) => {
  const spName = req.params.spName;
  const sp = findSp(spName);

  logAssertion({
    direction: 'outbound',
    from: 'edith-core',
    to: spName,
    status: 'initiated',
    description: sp ? `SSO to ${sp.name}` : `SSO to ${spName}`
  });

  // Fall through to proxy middleware — core-ui handles the SAML generation
  next();
});

// --- Proxy everything else to core-ui ---
// Login, dashboard, logout, static assets, and the outbound SSO
// (after logging above) all pass through transparently.
proxy.use(createProxyMiddleware({
  target: CORE_UI_URL,
  changeOrigin: true,
  ws: true
}));

proxy.listen(PROXY_PORT, () => {
  console.log(`\n========================================`);
  console.log(`  SAML Gateway Proxy on port ${PROXY_PORT}`);
  console.log(`  (transparent proxy for edith-core)`);
  console.log(`  Core UI (internal): ${CORE_UI_URL}`);
  console.log(`  Core Backend:       ${CORE_BACKEND_URL}`);
  console.log(`========================================\n`);
});

// ============================================================
// ADMIN DASHBOARD (port 6000)
// Separate admin interface for monitoring SAML traffic.
// ============================================================
const admin = express();
admin.set('view engine', 'ejs');
admin.set('views', path.join(__dirname, 'views'));
admin.use(express.static(path.join(__dirname, 'public')));
admin.use(session({
  secret: 'saml-gateway-secret-key',
  resave: false,
  saveUninitialized: false,
  cookie: { maxAge: 60 * 60 * 1000 }
}));

admin.get('/', (req, res) => {
  res.redirect('/dashboard');
});

admin.get('/dashboard', (req, res) => {
  const activeIdps = config.idpProviders.filter(p => p.status === 'active').length;
  const activeSpps = config.spProviders.filter(p => p.status === 'active').length;
  const configuredSps = config.spProviders.filter(p => p.status === 'configured').length;
  const totalRoutes = config.routes.length;
  const inboundRoutes = config.routes.filter(r => r.direction === 'inbound').length;
  const outboundRoutes = config.routes.filter(r => r.direction === 'outbound').length;
  const recentLogs = assertionLog.slice(0, 10);

  res.render('dashboard', {
    config,
    stats: { activeIdps, activeSpps, configuredSps, totalRoutes, inboundRoutes, outboundRoutes },
    recentLogs
  });
});

admin.get('/providers', (req, res) => {
  const tab = req.query.tab || 'idp';
  res.render('providers', { config, tab });
});

admin.get('/routes', (req, res) => {
  const filter = req.query.filter || 'all';
  let routes = config.routes;
  if (filter === 'inbound') routes = routes.filter(r => r.direction === 'inbound');
  if (filter === 'outbound') routes = routes.filter(r => r.direction === 'outbound');
  res.render('routes', { config, routes, filter });
});

admin.get('/topology', (req, res) => {
  res.render('topology', { config });
});

admin.get('/logs', (req, res) => {
  const direction = req.query.direction || 'all';
  let logs = assertionLog;
  if (direction === 'inbound') logs = logs.filter(l => l.direction === 'inbound');
  if (direction === 'outbound') logs = logs.filter(l => l.direction === 'outbound');
  res.render('logs', { logs, direction });
});

// API routes for dashboard data refresh
admin.get('/api/stats', (req, res) => {
  res.json({
    idpCount: config.idpProviders.length,
    spCount: config.spProviders.length,
    routeCount: config.routes.length,
    activeIdps: config.idpProviders.filter(p => p.status === 'active').length,
    activeSps: config.spProviders.filter(p => p.status === 'active').length,
    assertionCount: assertionLog.length,
    recentAssertions: assertionLog.slice(0, 20)
  });
});

admin.get('/api/providers', (req, res) => {
  res.json({
    idpProviders: config.idpProviders,
    spProviders: config.spProviders
  });
});

admin.get('/api/routes', (req, res) => {
  res.json({ routes: config.routes });
});

admin.get('/api/logs', (req, res) => {
  const limit = parseInt(req.query.limit) || 50;
  res.json({ logs: assertionLog.slice(0, limit) });
});

admin.get('/api/health', async (req, res) => {
  const checks = [];
  for (const idp of config.idpProviders) {
    if (idp.uiUrl) {
      checks.push(
        checkHealth(idp.uiUrl).then(status => ({ id: idp.id, name: idp.name, type: 'idp', status }))
      );
    }
  }
  for (const sp of config.spProviders) {
    if (sp.uiUrl) {
      checks.push(
        checkHealth(sp.uiUrl).then(status => ({ id: sp.id, name: sp.name, type: 'sp', status }))
      );
    }
  }
  const results = await Promise.all(checks);
  res.json({ services: results });
});

admin.listen(ADMIN_PORT, () => {
  console.log(`========================================`);
  console.log(`  SAML Gateway Admin on port ${ADMIN_PORT}`);
  console.log(`  Dashboard: http://localhost:${ADMIN_PORT}`);
  console.log(`========================================`);
  console.log(`  IdP providers: ${config.idpProviders.length}`);
  console.log(`  SP providers:  ${config.spProviders.length}`);
  console.log(`  Routes:        ${config.routes.length}`);
  console.log(`========================================\n`);
});
