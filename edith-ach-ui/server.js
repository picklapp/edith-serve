const express = require('express');
const session = require('express-session');
const bodyParser = require('body-parser');
const path = require('path');

const app = express();
const PORT = 4001;
const BACKEND_URL = 'http://localhost:9092';

// Middleware
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(bodyParser.urlencoded({ extended: true }));
app.use(bodyParser.json());
app.use(session({
  secret: 'edith-ach-secret',
  resave: false,
  saveUninitialized: false,
  cookie: { maxAge: 30 * 60 * 1000 }
}));

// Auth middleware
function requireSAMLAuth(req, res, next) {
  if (req.session && req.session.samlUser) return next();
  res.status(401).render('error', {
    title: 'Authentication Required',
    message: 'You must sign in through Edith Core to access this application.',
    backUrl: 'http://localhost:3000'
  });
}

// SAML Assertion Consumer Service — forwards to Spring Boot backend for validation
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

    req.session.samlUser = {
      nameID: data.nameID,
      email: data.email,
      displayName: data.displayName,
      attributes: data.attributes || {}
    };
    res.redirect('/payment');
  } catch (err) {
    console.error('SAML ACS Error:', err);
    res.status(400).render('error', {
      title: 'SSO Authentication Failed',
      message: 'Could not verify your identity. Please try signing in again from Edith Core.',
      backUrl: 'http://localhost:3000'
    });
  }
});

// SP Metadata — proxy from Spring Boot backend
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

// ACH Payment Page
app.get('/payment', requireSAMLAuth, (req, res) => {
  res.render('payment', { user: req.session.samlUser, error: null });
});

// Handle ACH Payment Submission
app.post('/payment', requireSAMLAuth, (req, res) => {
  const paymentData = {
    recipientName: req.body.recipientName,
    recipientBank: req.body.recipientBank,
    routingNumber: req.body.routingNumber,
    accountNumber: req.body.accountNumber,
    amount: req.body.amount,
    paymentType: req.body.paymentType,
    memo: req.body.memo || '',
    submittedBy: req.session.samlUser.email,
    submittedAt: new Date().toISOString(),
    referenceId: 'ACH-' + Date.now()
  };

  res.render('success', { user: req.session.samlUser, paymentData });
});

// Home redirect
app.get('/', (req, res) => {
  if (req.session && req.session.samlUser) return res.redirect('/payment');
  res.render('error', {
    title: 'Edith - ACH Payments',
    message: 'Please sign in through Edith Core to access this application.',
    backUrl: 'http://localhost:3000'
  });
});

app.get('/logout', (req, res) => {
  req.session.destroy();
  res.redirect('http://localhost:3000');
});

app.listen(PORT, () => {
  console.log(`edith-ach (UI) running at http://localhost:${PORT}`);
  console.log(`  -> Backend: ${BACKEND_URL}`);
});
