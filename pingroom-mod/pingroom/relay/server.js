// Ping Room - serwer relay.
// Nie zna Minecrafta: tylko trzyma pokoje (kod -> lista połączeń) i przekazuje pingi między członkami.
// Uruchomienie:  npm install  &&  npm start        (domyślnie ws://0.0.0.0:8080)

import { WebSocketServer } from 'ws';
import { randomInt } from 'node:crypto';

const PORT = Number(process.env.PORT) || 8080;
const TRUST_PROXY = process.env.TRUST_PROXY === '1'; // ustaw na 1, jeśli stoisz za nginx/Caddy (czyta X-Forwarded-For)

const CODE_LENGTH = 6;
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // bez 0/O/1/I - łatwiej przepisać
const MAX_ROOM_SIZE = 16;
const MAX_MESSAGE_BYTES = 2048;
const MIN_PING_INTERVAL_MS = 250;
const MAX_JOIN_ATTEMPTS_PER_MINUTE = 20;
const MAX_CONNECTIONS_PER_IP = 8;
const NAME_RE = /^[A-Za-z0-9_.]{1,24}$/;

/** @type {Map<string, {server: string, members: Set<any>}>} */
const rooms = new Map();
/** @type {Map<string, {count: number, resetAt: number}>} */
const joinAttempts = new Map();
/** @type {Map<string, number>} */
const connectionsPerIp = new Map();

const wss = new WebSocketServer({ port: PORT, maxPayload: MAX_MESSAGE_BYTES });

// ---------------------------------------------------------------- pomocnicze

function clientIp(req) {
  if (TRUST_PROXY) {
    const fwd = req.headers['x-forwarded-for'];
    if (typeof fwd === 'string' && fwd.length > 0) return fwd.split(',')[0].trim();
  }
  return req.socket.remoteAddress || 'unknown';
}

function allowJoinAttempt(ip) {
  const now = Date.now();
  let entry = joinAttempts.get(ip);
  if (!entry || now > entry.resetAt) {
    entry = { count: 0, resetAt: now + 60_000 };
    joinAttempts.set(ip, entry);
  }
  entry.count += 1;
  return entry.count <= MAX_JOIN_ATTEMPTS_PER_MINUTE;
}

function makeCode() {
  for (;;) {
    let code = '';
    for (let i = 0; i < CODE_LENGTH; i++) code += ALPHABET[randomInt(ALPHABET.length)];
    if (!rooms.has(code)) return code;
  }
}

const validName = (s) => typeof s === 'string' && NAME_RE.test(s);
const validServer = (s) => typeof s === 'string' && s.length > 0 && s.length <= 120;

function send(ws, obj) {
  if (ws.readyState === 1 /* OPEN */) ws.send(JSON.stringify(obj));
}

function broadcast(room, obj, except) {
  for (const member of room.members) {
    if (member !== except) send(member, obj);
  }
}

function leaveRoom(ws) {
  const code = ws.roomCode;
  if (!code) return;
  ws.roomCode = null;
  const room = rooms.get(code);
  if (!room) return;
  room.members.delete(ws);
  if (room.members.size === 0) {
    rooms.delete(code); // pusty pokój znika
  } else {
    broadcast(room, { t: 'member_leave', name: ws.playerName });
  }
}

// ---------------------------------------------------------------- obsługa wiadomości

function handle(ws, msg) {
  switch (msg.t) {
    case 'create': {
      if (ws.roomCode) return send(ws, { t: 'error', msg: 'Już jesteś w pokoju.' });
      if (!validName(msg.name) || !validServer(msg.server)) {
        return send(ws, { t: 'error', msg: 'Nieprawidłowe dane.' });
      }
      const code = makeCode();
      rooms.set(code, { server: msg.server.toLowerCase(), members: new Set([ws]) });
      ws.roomCode = code;
      ws.playerName = msg.name;
      send(ws, { t: 'created', code });
      break;
    }

    case 'join': {
      if (ws.roomCode) return send(ws, { t: 'error', msg: 'Już jesteś w pokoju.' });
      if (!allowJoinAttempt(ws.ip)) {
        return send(ws, { t: 'error', msg: 'Za dużo prób. Spróbuj za chwilę.' });
      }
      if (!validName(msg.name) || !validServer(msg.server) || typeof msg.code !== 'string') {
        return send(ws, { t: 'error', msg: 'Nieprawidłowe dane.' });
      }
      const room = rooms.get(msg.code.trim().toUpperCase());
      // ta sama odpowiedź dla "brak pokoju" i "inny serwer" - nie zdradzamy, czy kod istnieje
      if (!room || room.server !== msg.server.toLowerCase()) {
        return send(ws, { t: 'error', msg: 'Nieprawidłowy kod (albo pokój jest na innym serwerze).' });
      }
      if (room.members.size >= MAX_ROOM_SIZE) {
        return send(ws, { t: 'error', msg: 'Pokój jest pełny.' });
      }
      const lower = msg.name.toLowerCase();
      for (const m of room.members) {
        if (m.playerName.toLowerCase() === lower) {
          return send(ws, { t: 'error', msg: 'Gracz o tym nicku jest już w pokoju.' });
        }
      }
      const others = [...room.members].map((m) => m.playerName);
      broadcast(room, { t: 'member_join', name: msg.name });
      room.members.add(ws);
      ws.roomCode = msg.code.trim().toUpperCase();
      ws.playerName = msg.name;
      send(ws, { t: 'joined', code: ws.roomCode, members: others });
      break;
    }

    case 'leave':
      leaveRoom(ws);
      break;

    case 'ping': {
      const room = ws.roomCode ? rooms.get(ws.roomCode) : null;
      if (!room) return;
      const now = Date.now();
      if (now - ws.lastPingAt < MIN_PING_INTERVAL_MS) return;
      ws.lastPingAt = now;
      const { x, y, z, dim, kind } = msg;
      if (![x, y, z].every((n) => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) < 3e7)) return;
      if (typeof dim !== 'string' || dim.length === 0 || dim.length > 120) return;
      broadcast(room, { t: 'ping', from: ws.playerName, x, y, z, dim, kind: kind === 'danger' ? 'danger' : 'here' }, ws);
      break;
    }

    default:
      break;
  }
}

// ---------------------------------------------------------------- połączenia

wss.on('connection', (ws, req) => {
  ws.ip = clientIp(req);
  ws.isAlive = true;
  ws.roomCode = null;
  ws.playerName = null;
  ws.lastPingAt = 0;

  const open = (connectionsPerIp.get(ws.ip) || 0) + 1;
  if (open > MAX_CONNECTIONS_PER_IP) {
    ws.close(1013, 'Za dużo połączeń z tego adresu');
    return;
  }
  connectionsPerIp.set(ws.ip, open);

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data, isBinary) => {
    if (isBinary) return;
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return;
    }
    if (typeof msg !== 'object' || msg === null) return;
    handle(ws, msg);
  });

  ws.on('close', () => {
    leaveRoom(ws);
    const left = (connectionsPerIp.get(ws.ip) || 1) - 1;
    if (left <= 0) connectionsPerIp.delete(ws.ip);
    else connectionsPerIp.set(ws.ip, left);
  });

  ws.on('error', () => {});
});

// zrywamy martwe połączenia (ping/pong co 30 s) i sprzątamy liczniki prób
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
  const now = Date.now();
  for (const [ip, entry] of joinAttempts) if (now > entry.resetAt) joinAttempts.delete(ip);
}, 30_000);

wss.on('close', () => clearInterval(heartbeat));

console.log(`Ping Room relay działa na porcie ${PORT}  ->  ws://localhost:${PORT}`);
