const express = require('express');
const session = require('express-session');
const bodyParser = require('body-parser');
const path = require('path');

const app = express();
const PORT = 3001; // Internal port — gateway on :3000 proxies to here
const BACKEND_URL = 'http://localhost:9090';

// Middleware
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(bodyParser.urlencoded({ extended: true }));
app.use(bodyParser.json());
app.use(session({
  secret: 'cc-core-secret-key',
  resave: false,
  saveUninitialized: false,
  cookie: { maxAge: 30 * 60 * 1000 }
}));

// Auth middleware
function requireAuth(req, res, next) {
  if (req.session && req.session.user) return next();
  res.redirect('/login');
}

// Routes
app.get('/', (req, res) => {
  res.redirect(req.session && req.session.user ? '/dashboard' : '/login');
});

app.get('/login', (req, res) => {
  if (req.session && req.session.user) return res.redirect('/dashboard');
  res.render('login', { error: null });
});

// Login via Spring Boot backend
app.post('/login', async (req, res) => {
  try {
    const response = await fetch(`${BACKEND_URL}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: req.body.username, password: req.body.password })
    });
    const data = await response.json();

    if (!response.ok) {
      return res.render('login', { error: data.error || 'Invalid username or password' });
    }

    req.session.user = { username: data.username, email: data.email, displayName: data.displayName };
    res.redirect('/dashboard');
  } catch (err) {
    console.error('Login error:', err);
    res.render('login', { error: 'Backend service unavailable. Is edith-core-backend running on port 9090?' });
  }
});

app.get('/dashboard', requireAuth, (req, res) => {
  res.render('dashboard', { user: req.session.user });
});

// SAML ACS — edith-core acting as SP (receives SSO from edith-bank)
app.post('/saml/acs', async (req, res) => {
  try {
    const samlResponse = req.body.SAMLResponse;
    if (!samlResponse) {
      throw new Error('Missing SAMLResponse');
    }

    const response = await fetch(`${BACKEND_URL}/api/saml/acs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ samlResponse })
    });
    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.error || 'SAML validation failed');
    }

    req.session.user = {
      email: data.email,
      displayName: data.displayName,
      username: data.nameID
    };
    res.redirect('/dashboard');
  } catch (err) {
    console.error('SAML ACS Error:', err);
    res.status(400).send('SSO Error: ' + err.message);
  }
});

// Service display names
const SP_NAMES = {
  rdc: 'Edith RDC',
  ach: 'Edith ACH'
};

// IdP-initiated SSO — calls Spring Boot backend to generate SAML Response
app.get('/saml/sso/:spName', requireAuth, async (req, res) => {
  try {
    const spName = req.params.spName;
    const user = req.session.user;
    const response = await fetch(`${BACKEND_URL}/api/saml/sso`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: user.email, displayName: user.displayName, spName })
    });
    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.error || 'Failed to generate SAML response');
    }

    // Render auto-submit form that POSTs SAMLResponse to SP ACS
    res.render('sso-post', {
      acsUrl: data.acsUrl,
      samlResponse: data.samlResponse,
      serviceName: SP_NAMES[spName] || spName
    });
  } catch (err) {
    console.error('SAML SSO Error:', err);
    res.status(500).send('SSO Error: ' + err.message);
  }
});

// IdP Metadata — proxy from Spring Boot backend
app.get('/saml/metadata', async (req, res) => {
  try {
    const response = await fetch(`${BACKEND_URL}/api/saml/metadata`);
    const xml = await response.text();
    res.type('application/xml');
    res.send(xml);
  } catch (err) {
    res.status(500).send('Metadata unavailable');
  }
});

app.get('/logout', (req, res) => {
  req.session.destroy();
  res.redirect('/login');
});

app.listen(PORT, () => {
  console.log(`edith-core (UI) running at http://localhost:${PORT}`);
  console.log(`  -> Backend: ${BACKEND_URL}`);
});
