'use strict';

/**
 * Saff Location Dashboard — Node.js backend
 *
 * Receives periodic location POSTs from the Android app (LocationReporter.java)
 * and broadcasts them in real-time to browser clients via Socket.IO.
 *
 * Expected POST payload from Android:
 *   { "latitude": number, "longitude": number, "accuracy": number, "timestamp": number }
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

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const PORT = parseInt(process.env.PORT || '3000', 10);
const HISTORY_LIMIT = parseInt(process.env.HISTORY_LIMIT || '500', 10);

// ---------------------------------------------------------------------------
// In-memory store
// ---------------------------------------------------------------------------

/**
 * @typedef {{ latitude: number, longitude: number, accuracy: number, timestamp: number, receivedAt: number }} LocationPoint
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

// ---------------------------------------------------------------------------
// Express app
// ---------------------------------------------------------------------------
const app = express();
app.use(express.json());

// Serve static files from ./public (index.html, etc.)
app.use(express.static(path.join(__dirname, 'public')));

/**
 * POST /location
 * Receives a location update from the Android app.
 * Body: { latitude, longitude, accuracy, timestamp }
 */
app.post('/location', (req, res) => {
    const { latitude, longitude, accuracy, timestamp } = req.body;

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

    /** @type {LocationPoint} */
    const point = { latitude, longitude, accuracy, timestamp, receivedAt: Date.now() };
    addLocation(point);

    // Broadcast to all connected dashboard clients
    io.emit('location', point);

    console.log(`[${new Date().toISOString()}] Location received: ${latitude.toFixed(6)}, ${longitude.toFixed(6)} ±${accuracy.toFixed(0)}m`);

    return res.status(200).json({ ok: true });
});

/**
 * GET /api/latest
 * Returns the most recently received location, or 404 if none yet.
 */
app.get('/api/latest', (req, res) => {
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
app.get('/api/locations', (req, res) => {
    const requestedLimit = parseInt(req.query.limit, 10);
    const history = Number.isFinite(requestedLimit) && requestedLimit > 0
        ? locationHistory.slice(-requestedLimit)
        : locationHistory.slice();
    return res.json(history);
});

/**
 * GET /api/status
 * Simple health-check endpoint.
 */
app.get('/api/status', (req, res) => {
    return res.json({
        ok: true,
        pointsInHistory: locationHistory.length,
        historyLimit: HISTORY_LIMIT,
        latestReceivedAt: latestLocation ? latestLocation.receivedAt : null,
    });
});

// Catch-all: serve index.html for any unknown GET (SPA-style fallback)
app.get('*', (req, res) => {
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
    console.log(`  GET  /               — Live map dashboard`);
    console.log(`  GET  /api/latest     — Latest location (JSON)`);
    console.log(`  GET  /api/locations  — Location history (JSON)`);
    console.log(`  GET  /api/status     — Health check`);
});
