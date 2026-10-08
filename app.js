'use strict';
const $ = (id) => document.getElementById(id);
const keyInput = $('key');
const form = $('connectForm');
const connectPanel = $('connectPanel');
const error = $('loginError');
const network = $('network');
const connectionText = $('connectionText');
const pulse = $('pulse');
const screen = $('screen');
const empty = $('empty');
const stats = $('frameStats');
let key = '';
let connected = false;
let currentUrl = '';
let gestureStart = null;
let lastImageTime = 0;
let updateTimer = null;

function render() {
  network.textContent = connected ? 'Online' : 'Offline';
  network.className = 'tag ' + (connected ? 'on' : 'off');
  connectionText.textContent = connected ? 'Redmi terhubung' : 'Belum terhubung';
  pulse.classList.toggle('active', connected);
  for (const b of document.querySelectorAll('[data-action]')) b.disabled = !connected;
}
function stop() {
  connected = false; key = ''; gestureStart = null;
  if (updateTimer) clearTimeout(updateTimer);
  updateTimer = null;
  if (currentUrl) { URL.revokeObjectURL(currentUrl); currentUrl = ''; }
  screen.removeAttribute('src'); screen.hidden = true; empty.hidden = false;
  connectPanel.hidden = false;
  render();
}
async function loadFrame() {
  if (!connected) return;
  try {
    const result = await fetch('/frame', { headers: { 'X-Arunika-Key': key }, cache: 'no-store' });
    if (result.status === 401) { error.textContent = 'Kode ditolak, cek kode di Redmi.'; stop(); return; }
    if (result.ok) {
      const blob = await result.blob();
      const url = URL.createObjectURL(blob);
      screen.onload = () => { if (currentUrl && currentUrl !== url) URL.revokeObjectURL(currentUrl); currentUrl = url; };
      screen.src = url;
      screen.hidden = false; empty.hidden = true;
      lastImageTime = Date.now();
      stats.textContent = `Layar aktif • pembaruan tiap ±1,5 detik • ${new Date().toLocaleTimeString('id-ID')}`;
    } else if (result.status !== 503) {
      throw new Error('Response: ' + result.status);
    }
  } catch (_) {
    stats.textContent = 'Koneksi terputus; mencoba lagi saat perangkat kembali online';
  }
  if (connected) updateTimer = setTimeout(loadFrame, 1500);
}
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const nextKey = keyInput.value.trim().toLowerCase();
  if (!/^[a-f0-9]{32}$/.test(nextKey)) { error.textContent = 'Masukkan 32 karakter kode dari Redmi.'; return; }
  key = nextKey;
  error.textContent = '';
  connected = true;
  connectPanel.hidden = true;
  render();
  loadFrame();
});
$('disconnect').addEventListener('click', () => { stop(); keyInput.value = ''; });
async function sendAction(action, values = {}) {
  if (!connected) return;
  try {
    const result = await fetch('/action', {
      method: 'POST', headers: { 'X-Arunika-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ action, ...values }), cache: 'no-store'
    });
    if (result.status === 401) { error.textContent = 'Kode akses sudah tidak berlaku'; stop(); }
  } catch (_) { stats.textContent = 'Gagal mengirim perintah, periksa Tailscale'; }
}
for (const b of document.querySelectorAll('[data-action]')) b.addEventListener('click', () => sendAction(b.dataset.action));
function point(ev) {
  const rect = screen.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  return {
    x: Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width)),
    y: Math.max(0, Math.min(1, (ev.clientY - rect.top) / rect.height))
  };
}
screen.addEventListener('pointerdown', (ev) => {
  if (!connected || ev.button > 0) return;
  ev.preventDefault(); screen.setPointerCapture(ev.pointerId);
  gestureStart = { p: point(ev), id: ev.pointerId };
});
screen.addEventListener('pointerup', (ev) => {
  if (!gestureStart || ev.pointerId !== gestureStart.id) return;
  ev.preventDefault();
  const e = point(ev); const start = gestureStart; gestureStart = null;
  if (!e || !start.p) return;
  const diff = Math.hypot(e.x - start.p.x, e.y - start.p.y);
  if (diff > 0.022) sendAction('swipe', { ...start.p, x2:e.x, y2:e.y });
  else sendAction('tap', start.p);
});
screen.addEventListener('pointercancel', () => { gestureStart = null; });
render();
