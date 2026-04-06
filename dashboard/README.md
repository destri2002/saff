# Saff Location Dashboard

A lightweight Node.js backend and real-time web dashboard that receives periodic GPS location
updates from the **Saff / Mumla** Android app and displays them live on an interactive map.

---

## How it works

```
Android app (LocationReporter.java)
        │
        │  POST /location  every 5 s
        │  { latitude, longitude, accuracy, timestamp }
        ▼
Node.js server (server.js)
        │
        ├── stores location points in memory (ring buffer, 500 points)
        ├── stores report rows in memory (ring buffer, 500 rows)
        │
        └── broadcasts via Socket.IO ──► Browser dashboard (index.html)
                                               ├── Leaflet map, live updates
                                               └── Report form + report table
```

---

## Quick start

### 1 — Install dependencies

```bash
cd dashboard
npm install
```

### 2 — Start the server

```bash
npm start
# or, with auto-reload during development:
npm run dev
```

The server starts on **http://0.0.0.0:3000** by default.

### 3 — Configure the Android app

Open **Mumla → Settings → General → Dashboard URL** and enter:

```
http://<your-server-ip>:3000/location
```

Replace `<your-server-ip>` with the LAN IP or public hostname of the machine
running this server.  The app will start posting location every **5 seconds**
once it connects to a Mumble server.

### 4 — Open the dashboard

Navigate to **http://\<server-ip\>:3000** in any browser.

---

## Environment variables

| Variable        | Default | Description                                    |
|-----------------|---------|------------------------------------------------|
| `PORT`          | `3000`  | TCP port the server listens on                 |
| `HISTORY_LIMIT` | `500`   | Maximum location points kept in memory         |
| `REPORT_HISTORY_LIMIT` | `500` | Maximum report rows kept in memory       |

Example — listen on port 8080 and keep 1000 points:

```bash
PORT=8080 HISTORY_LIMIT=1000 npm start
```

---

## API reference

| Method | Path               | Description                                         |
|--------|--------------------|-----------------------------------------------------|
| `POST` | `/location`        | Receive a location update from the Android app      |
| `POST` | `/report`          | Receive a report row                                |
| `GET`  | `/`                | Serve the live map dashboard                        |
| `GET`  | `/api/latest`      | Return the most recently received point (JSON)      |
| `GET`  | `/api/locations`   | Return location history array (JSON); `?limit=N`    |
| `GET`  | `/api/reports`     | Return report history array (JSON); `?limit=N`      |
| `GET`  | `/api/status`      | Health check / stats                                |

### POST `/location` payload

```json
{
  "latitude":  -6.200000,
  "longitude": 106.816666,
  "accuracy":  12.5,
  "timestamp": 1712394000000
}
```

### GET `/api/locations` response

```json
[
  { "latitude": -6.2, "longitude": 106.8, "accuracy": 12.5, "timestamp": 1712394000000, "receivedAt": 1712394000123 },
  ...
]
```

### POST `/report` payload

```json
{
  "timestamp": 1712394000000,
  "districtCity": "South Jakarta",
  "category": "Food",
  "amount": 120000,
  "description": "Dinner supplies"
}
```

### GET `/api/reports` response

```json
[
  {
    "timestamp": 1712394000000,
    "districtCity": "South Jakarta",
    "category": "Food",
    "amount": 120000,
    "description": "Dinner supplies",
    "receivedAt": 1712394000123
  }
]
```

---

## Dashboard features

- **Live map** powered by [Leaflet](https://leafletjs.com/) with OpenStreetMap tiles
- **Real-time updates** via Socket.IO (no page refresh needed)
- **Track line** — the full travelled path since the server started
- **Accuracy circle** — visualises GPS accuracy around the current position
- **Pulsing marker** — always shows the latest position
- **Follow mode** — auto-pans/zooms to the latest position (toggle button)
- **History replay** — new browser tabs receive the complete track on connect
- **Dark UI** — easy on the eyes
- **Report form + table** — submit and view report rows with:
  - timestamp
  - district/city
  - category
  - amount
  - description

---

## Adjusting the update interval

The 5-second interval is defined in the Android app as a constant:

```java
// app/src/main/java/se/lublin/mumla/Settings.java
public static final long LOCATION_SEND_INTERVAL_MS = 5000L;
```

Change the value and rebuild the Android app to use a different interval.
The dashboard adapts automatically regardless of the interval used.

---

## Production deployment notes

- For internet-facing deployments, put the server behind a reverse proxy (nginx, Caddy)
  and enable HTTPS so location data is encrypted in transit.
- To persist history across restarts, replace the in-memory array in `server.js` with
  a database (SQLite, PostgreSQL, etc.).
