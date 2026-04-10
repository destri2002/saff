'use strict';

/**
 * Saff Location Dashboard — Node.js backend
 *
 * Receives periodic location POSTs from the Android app (LocationReporter.java)
 * and broadcasts them in real-time to browser clients via Socket.IO.
 *
 * Expected POST payload from Android:
 *   { "latitude": number, "longitude": number, "accuracy": number, "timestamp": number, "username": string (optional) }
 *
 * To configure the Android app, set the "Dashboard URL" in General Settings to:
 *   http://<server-ip>:<PORT>/location
 *
 * Environment variables:
 *   PORT          — TCP port to listen on (default: 3000)
 *   HISTORY_LIMIT — max number of location points kept in memory (default: 500)
 */

const express = require('express');
const http = require('http');
const path = require('path');
const { Server: SocketIOServer } = require('socket.io');
const rateLimit = require('express-rate-limit');

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const PORT = parseInt(process.env.PORT || '3000', 10);
const HISTORY_LIMIT = parseInt(process.env.HISTORY_LIMIT || '500', 10);
const REPORT_HISTORY_LIMIT = parseInt(process.env.REPORT_HISTORY_LIMIT || '500', 10);
const REPORT_API_SOURCE = (process.env.REPORT_API_SOURCE || 'https://emsifa.github.io/api-wilayah-indonesia').trim();


// ---------------------------------------------------------------------------
// In-memory store
// ---------------------------------------------------------------------------

/**
 * @typedef {{ latitude: number, longitude: number, accuracy: number, timestamp: number, username: string|undefined, receivedAt: number }} LocationPoint
 */

/** @type {LocationPoint[]} */
const locationHistory = [];

/** @type {LocationPoint|null} */
let latestLocation = null;

function addLocation(point) {
    locationHistory.push(point);
    if (locationHistory.length > HISTORY_LIMIT) {
        locationHistory.shift();
    }
    latestLocation = point;
}

/**
 * @typedef {{ timestamp: number, districtCity: string, category: string, amount: number, description: string, api?: string, namaPelapor?: string, kabKota?: string, kecamatan?: string, kelurahan?: string, receivedAt: number }} ReportRow
 */

/** @type {ReportRow[]} */
const reportHistory = [];

function addReport(report) {
    reportHistory.push(report);
    if (reportHistory.length > REPORT_HISTORY_LIMIT) {
        reportHistory.shift();
    }
}

// ---------------------------------------------------------------------------
// Express app
// ---------------------------------------------------------------------------
const app = express();
app.use(express.json());

// Serve static files from ./public (index.html, etc.)
app.use(express.static(path.join(__dirname, 'public')));

// ---------------------------------------------------------------------------
// Rate limiters
// ---------------------------------------------------------------------------

// Android app posts once every 5 s; allow up to 60 requests per minute per IP
// before rejecting with 429, giving plenty of headroom for multiple devices.
const locationLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many location updates from this IP, please slow down.' },
});

// Dashboard API: generous limit for browser polling / debugging
const apiLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many API requests, please slow down.' },
});

// Report submissions from UI/users.
const reportLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many report submissions from this IP, please slow down.' },
});

/**
 * POST /location
 * Receives a location update from the Android app.
 * Body: { latitude, longitude, accuracy, timestamp }
 */
app.post('/location', locationLimiter, (req, res) => {
    const { latitude, longitude, accuracy, timestamp, username } = req.body;

    // Basic validation
    if (
        typeof latitude !== 'number' ||
        typeof longitude !== 'number' ||
        typeof accuracy !== 'number' ||
        typeof timestamp !== 'number'
    ) {
        return res.status(400).json({ error: 'Invalid payload. Expected {latitude, longitude, accuracy, timestamp} as numbers.' });
    }

    if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
        return res.status(400).json({ error: 'Coordinates out of range.' });
    }

    // username is optional; only include it if it's a non-empty string
    const sanitizedUsername = (typeof username === 'string' && username.trim().length > 0)
        ? username.trim().slice(0, 64)
        : undefined;

    /** @type {LocationPoint} */
    const point = { latitude, longitude, accuracy, timestamp, username: sanitizedUsername, receivedAt: Date.now() };
    addLocation(point);

    // Broadcast to all connected dashboard clients
    io.emit('location', point);

    console.log(`[${new Date().toISOString()}] Location received: ${latitude.toFixed(6)}, ${longitude.toFixed(6)} ±${accuracy.toFixed(0)}m${sanitizedUsername ? ` (${sanitizedUsername})` : ''}`);

    return res.status(200).json({ ok: true });
});

function handleReportPost(req, res) {
    const body = req.body || {};
    const { timestamp } = body;
    const namaPelapor = typeof body.namaPelapor === 'string' ? body.namaPelapor.trim() : '';
    const kabKota = typeof body.kabKota === 'string' ? body.kabKota : undefined;
    const kecamatan = typeof body.kecamatan === 'string' ? body.kecamatan : undefined;
    const kelurahan = typeof body.kelurahan === 'string' ? body.kelurahan : undefined;
    const kategori = typeof body.kategori === 'string' ? body.kategori : undefined;
    const deskripsi = typeof body.deskripsi === 'string' ? body.deskripsi : undefined;
    const districtCity = typeof kabKota === 'string' ? kabKota : body.districtCity;
    const category = typeof kategori === 'string' ? kategori : body.category;
    const description = typeof deskripsi === 'string' ? deskripsi : body.description;
    const amount = (typeof body.amount === 'number' && Number.isFinite(body.amount) && body.amount >= 0)
        ? body.amount
        : 0;
    const hasNewAndroidShape = [kabKota, kecamatan, kelurahan, kategori, deskripsi].some((value) => typeof value === 'string');
    const api = hasNewAndroidShape ? REPORT_API_SOURCE : undefined;

    if (typeof timestamp !== 'number' || !Number.isFinite(timestamp) || timestamp <= 0) {
        return res.status(400).json({ error: 'Invalid timestamp. Expected a positive number (epoch ms).' });
    }
    if (hasNewAndroidShape) {
        if (!namaPelapor) {
            return res.status(400).json({ error: 'Invalid namaPelapor. Expected a non-empty string.' });
        }
        if (typeof kabKota !== 'string' || kabKota.trim().length === 0) {
            return res.status(400).json({ error: 'Invalid kabKota. Expected a non-empty string.' });
        }
        if (typeof kecamatan !== 'string' || kecamatan.trim().length === 0) {
            return res.status(400).json({ error: 'Invalid kecamatan. Expected a non-empty string.' });
        }
        if (typeof kelurahan !== 'string' || kelurahan.trim().length === 0) {
            return res.status(400).json({ error: 'Invalid kelurahan. Expected a non-empty string.' });
        }
    }
    if (typeof districtCity !== 'string' || districtCity.trim().length === 0) {
        return res.status(400).json({ error: 'Invalid district/city. Expected a non-empty string.' });
    }
    if (typeof category !== 'string' || category.trim().length === 0) {
        return res.status(400).json({ error: 'Invalid category. Expected a non-empty string.' });
    }
    if (typeof description !== 'string' || description.trim().length === 0) {
        return res.status(400).json({ error: 'Invalid description. Expected a non-empty string.' });
    }

    /** @type {ReportRow} */
    const report = {
        timestamp,
        districtCity: districtCity.trim().slice(0, 120),
        category: category.trim().slice(0, 80),
        amount,
        description: description.trim().slice(0, 500),
        api,
        namaPelapor: namaPelapor ? namaPelapor.slice(0, 120) : undefined,
        kabKota: typeof kabKota === 'string' ? kabKota.trim().slice(0, 120) : undefined,
        kecamatan: typeof kecamatan === 'string' ? kecamatan.trim().slice(0, 120) : undefined,
        kelurahan: typeof kelurahan === 'string' ? kelurahan.trim().slice(0, 120) : undefined,
        receivedAt: Date.now(),
    };
    addReport(report);

    io.emit('report', report);

    return res.status(200).json({ ok: true, report });
}

/**
 * POST /report
 * POST /api/report
 * Both endpoints are aliases and handled by the same logic.
 * Receives one report row.
 * Body (legacy): { timestamp, districtCity, category, amount, description }
 * Body (android): { timestamp, namaPelapor, kabKota, kecamatan, kelurahan, kategori, deskripsi, api }
 * Field `api` is static on server side and not used for display.
 */
app.post('/report', reportLimiter, handleReportPost);
app.post('/api/report', reportLimiter, handleReportPost);

/**
 * GET /api/latest
 * Returns the most recently received location, or 404 if none yet.
 */
app.get('/api/latest', apiLimiter, (req, res) => {
    if (!latestLocation) {
        return res.status(404).json({ error: 'No location received yet.' });
    }
    return res.json(latestLocation);
});

/**
 * GET /api/locations
 * Returns up to the last HISTORY_LIMIT location points (oldest first).
 * Query param: ?limit=N  — cap at N points (default: all history)
 */
app.get('/api/locations', apiLimiter, (req, res) => {
    const requestedLimit = parseInt(req.query.limit, 10);
    const history = Number.isFinite(requestedLimit) && requestedLimit > 0
        ? locationHistory.slice(-requestedLimit)
        : locationHistory.slice();
    return res.json(history);
});

/**
 * GET /api/reports
 * Returns report history (oldest first).
 * Query param: ?limit=N
 */
app.get('/api/reports', apiLimiter, (req, res) => {
    const requestedLimit = parseInt(req.query.limit, 10);
    const history = Number.isFinite(requestedLimit) && requestedLimit > 0
        ? reportHistory.slice(-requestedLimit)
        : reportHistory.slice();
    return res.json(history);
});

/**
 * GET /api/status
 * Simple health-check endpoint.
 */
app.get('/api/status', apiLimiter, (req, res) => {
    return res.json({
        ok: true,
        pointsInHistory: locationHistory.length,
        reportsInHistory: reportHistory.length,
        historyLimit: HISTORY_LIMIT,
        reportHistoryLimit: REPORT_HISTORY_LIMIT,
        latestReceivedAt: latestLocation ? latestLocation.receivedAt : null,
    });
});

// Catch-all: serve index.html for any unknown GET (SPA-style fallback)
app.get('*', apiLimiter, (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// ---------------------------------------------------------------------------
// HTTP server + Socket.IO
// ---------------------------------------------------------------------------
const server = http.createServer(app);
const io = new SocketIOServer(server, {
    cors: { origin: '*' },
});

io.on('connection', (socket) => {
    console.log(`[${new Date().toISOString()}] Dashboard client connected (id=${socket.id})`);

    // Send the current history so the newly connected client can draw the full track
    socket.emit('history', locationHistory.slice());
    socket.emit('reportHistory', reportHistory.slice());

    socket.on('disconnect', () => {
        console.log(`[${new Date().toISOString()}] Dashboard client disconnected (id=${socket.id})`);
    });
});

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------
server.listen(PORT, () => {
    console.log(`Saff Location Dashboard running on http://0.0.0.0:${PORT}`);
    console.log(`  POST /location       — Android app endpoint`);
    console.log(`  POST /report         — Report row endpoint`);
    console.log(`  POST /api/report     — Report row endpoint (alias)`);
    console.log(`  GET  /               — Live map dashboard`);
    console.log(`  GET  /api/latest     — Latest location (JSON)`);
    console.log(`  GET  /api/locations  — Location history (JSON)`);
    console.log(`  GET  /api/reports    — Report history (JSON)`);
    console.log(`  GET  /api/status     — Health check`);
});
