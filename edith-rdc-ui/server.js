const express = require('express');
const session = require('express-session');
const bodyParser = require('body-parser');
const multer = require('multer');
const path = require('path');

const app = express();
const PORT = 4000;
const BACKEND_URL = 'http://localhost:9091';

// Multer config for check uploads
const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, path.join(__dirname, 'uploads')),
  filename: (req, file, cb) => {
    const timestamp = Date.now();
    const ext = path.extname(file.originalname);
    cb(null, `check-${timestamp}-${file.fieldname}${ext}`);
  }
});
const upload = multer({
  storage,
  limits: { fileSize: 10 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    const allowed = /jpeg|jpg|png|tiff|pdf/;
    const ext = allowed.test(path.extname(file.originalname).toLowerCase());
    const mime = allowed.test(file.mimetype);
    cb(null, ext || mime);
  }
});

// Middleware
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(bodyParser.urlencoded({ extended: true }));
app.use(bodyParser.json());
app.use(session({
  secret: 'fiserv-sco-rdc-secret',
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
    res.redirect('/upload');
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

// Check Upload Page
app.get('/upload', requireSAMLAuth, (req, res) => {
  res.render('upload', { user: req.session.samlUser, error: null });
});

// Handle Check Upload
app.post('/upload', requireSAMLAuth, upload.fields([
  { name: 'checkFront', maxCount: 1 },
  { name: 'checkBack', maxCount: 1 }
]), (req, res) => {
  if (!req.files || !req.files.checkFront) {
    return res.render('upload', {
      user: req.session.samlUser,
      error: 'Please upload at least the front of the check.'
    });
  }

  const checkData = {
    amount: req.body.amount,
    accountNumber: req.body.accountNumber,
    routingNumber: req.body.routingNumber,
    frontImage: req.files.checkFront[0].filename,
    backImage: req.files.checkBack ? req.files.checkBack[0].filename : null,
    uploadedBy: req.session.samlUser.email,
    uploadedAt: new Date().toISOString()
  };

  res.render('success', { user: req.session.samlUser, checkData });
});

// Home redirect
app.get('/', (req, res) => {
  if (req.session && req.session.samlUser) return res.redirect('/upload');
  res.render('error', {
    title: 'Edith - Remote Deposit Capture',
    message: 'Please sign in through Edith Core to access this application.',
    backUrl: 'http://localhost:3000'
  });
});

app.get('/logout', (req, res) => {
  req.session.destroy();
  res.redirect('http://localhost:3000');
});

app.listen(PORT, () => {
  console.log(`edith-rdc (UI) running at http://localhost:${PORT}`);
  console.log(`  -> Backend: ${BACKEND_URL}`);
});
