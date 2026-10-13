// Frontend: plain JS. Every action calls /api/... on SpotifyWebServer, which talks to the backend by sockets.
const $ = s => document.querySelector(s);
const enc = encodeURIComponent;
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

let view = 'home', plName = '', query = '';   // what is on screen
let ctx = '';                                  // playlist we are playing from ('' = all songs)
let cur = null, lists = [], lastNode = 0;
let user = localStorage.getItem('user') || '';   // logged-in account
const audio = new Audio();

async function api(path, post) {
  const url = '/api/' + path + (path.includes('?') ? '&' : '?') + 'user=' + enc(user);
  const r = await fetch(url, { method: post ? 'POST' : 'GET' });
  const j = await r.json();
  if (j.node) lastNode = j.node;
  if (j.error === 'Please log in first') logout();      // e.g. backend was restarted
  return j;
}

// ---------- accounts: sign up / log in / log out ----------
let authMode = 'login';
function setMode(m) {
  authMode = m;
  $('#tab-login').classList.toggle('on', m === 'login');
  $('#tab-signup').classList.toggle('on', m === 'signup');
  $('#abtn').textContent = m === 'login' ? 'Log In' : 'Create account';
  $('#au').style.display = m === 'signup' ? '' : 'none';      // username only needed when signing up
  $('#ap').autocomplete = m === 'signup' ? 'new-password' : 'current-password';
  $('#aerr').textContent = '';
  $('#aerr').style.color = '';
}
function authMsg(text, ok) {
  $('#aerr').textContent = text;
  $('#aerr').style.color = ok ? '#1ed760' : '';
}
async function submitAuth() {
  const e = $('#ae').value.trim().toLowerCase(), u = $('#au').value.trim(), p = $('#ap').value;
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) return authMsg('Please enter a valid email address');
  if (!p) return authMsg('Please enter your password');

  if (authMode === 'signup') {
    if (!/^[A-Za-z0-9_]{3,15}$/.test(u)) return authMsg('Username must be 3-15 letters, digits or _');
    const r = await fetch(`/api/signup?user=${enc(u)}&pass=${enc(p)}&email=${enc(e)}`, { method: 'POST' });
    const j = await r.json();
    if (j.error) return authMsg(j.error);
    // account created -> go to the Log In tab (email stays filled in, user types the password to log in)
    setMode('login');
    $('#ap').value = '';
    authMsg('Account created! Now log in with your email and password.', true);
    return;
  }

  // log in with EMAIL + password; the backend tells us which username belongs to that email
  const r = await fetch(`/api/login?user=${enc(e)}&pass=${enc(p)}`, { method: 'POST' });
  const j = await r.json();
  if (j.error) return authMsg(j.error);
  user = j.username;
  localStorage.setItem('user', user);
  enter();
}
function enter() {
  $('#auth').style.display = 'none';
  $('#aerr').textContent = '';
  $('#who').textContent = '👤 ' + user;
  show('home');
}
function logout() {
  audio.pause(); audio.removeAttribute('src');
  cur = null; ctx = ''; user = '';
  localStorage.removeItem('user');
  $('#ptitle').textContent = 'Nothing playing';
  $('#partist').textContent = 'Pick a song ▶';
  $('#pcover').style.background = '#333'; $('#pcover').textContent = '♪';
  $('#view').innerHTML = ''; $('#msg').textContent = '';
  $('#ae').value = ''; $('#au').value = ''; $('#ap').value = ''; setMode('login');
  $('#auth').style.display = 'flex';
}
$('#ap').onkeydown = e => { if (e.key === 'Enter') submitAuth(); };

function say(j) {
  const m = $('#msg');
  m.className = j.error ? 'err' : '';
  m.textContent = j.error || j.msg || '';
}

// ---------- rendering ----------
// gradient + music symbol "cover art", different for every song
function cover(id) {
  const h = (id * 47) % 360;
  return `linear-gradient(135deg, hsl(${h},70%,48%), hsl(${(h + 55) % 360},65%,24%))`;
}
const SYM = ['♪', '♫', '♬', '♩'];
// real cover image: public/covers/<song id>.jpg (gradient stays behind it as a fallback)
const coverImg = id => `url('/covers/${id}.jpg') center/cover no-repeat`;

function rows(songs, inPlaylist) {
  if (!songs.length) return '<p style="color:#b3b3b3">Nothing here yet.</p>';
  return '<div class="grid">' + songs.map(s => `
    <div class="album ${cur && cur.id == s.id ? 'now' : ''}" data-id="${s.id}">
      <div class="cover" style="background:${coverImg(s.id)}, ${cover(s.id)}" onclick="play(${s.id})">
        <span class="lang">${s.lang}</span>
        <button class="playbtn">▶</button>
      </div>
      <b>${esc(s.title)}</b><small>${esc(s.artist)}</small>
      <div class="acts">
        <button class="${s.liked ? 'liked' : ''}" title="Add/remove from Library" onclick="like(${s.id})">${s.liked ? '♥' : '♡'}</button>
        ${inPlaylist
          ? `<button title="Remove from playlist" onclick="removeFrom(${s.id})">✕</button>`
          : `<select onchange="addTo(this, ${s.id})"><option value="">＋ Playlist</option>${lists.map(p => `<option>${esc(p.name)}</option>`).join('')}</select>`}
      </div>
    </div>`).join('') + '</div>';
}

function sections(j, inPlaylist) {
  return j.sections.map(s => `<h2>${esc(s.title)}</h2>${rows(s.songs, inPlaylist)}`).join('');
}

async function show(v, name) {
  view = v;
  if (v === 'playlist') plName = name;
  if (v !== 'playlist') ctx = '';
  document.querySelectorAll('#side a').forEach(a => a.classList.toggle('on', a.dataset.v === (v === 'playlist' ? 'playlists' : v)));
  lists = (await api('playlists')).playlists;
  const el = $('#view');

  if (v === 'home')    { const j = await api('home');    el.innerHTML = '<h2>Good day 👋</h2>' + sections(j); }
  if (v === 'songs')   { const j = await api('songs');   el.innerHTML = sections(j); }
  if (v === 'library') { const j = await api('library'); el.innerHTML = sections(j) + '<p style="color:#b3b3b3">Tip: click ♥ on any song to save it here.</p>'; }
  if (v === 'search') {
    if (!$('#q')) el.innerHTML = `<input id="q" type="text" placeholder="Search title, artist or language (Hindi / English / Telugu)"><div id="res"></div>`;
    $('#q').value = query;
    $('#q').oninput = e => { query = e.target.value; show('search'); };
    $('#q').focus();
    const j = await api('search?q=' + enc(query));
    $('#res').innerHTML = sections(j);
  }
  if (v === 'playlists') {
    el.innerHTML = `<h2>Your Playlists</h2>
      <input id="newpl" type="text" placeholder="New playlist name"> <button class="btn" onclick="createPl()">Create</button>
      <div class="cards">${lists.map(p => `<div class="card pl" style="background:${plBg(p.name, true)}" onclick="show('playlist','${esc(p.name).replace(/'/g, "\\'")}')"><b>${esc(p.name)}</b><small>${p.count} songs</small></div>`).join('')}</div>`;
  }
  if (v === 'playlist') {
    ctx = name;
    const j = await api('playlist?name=' + enc(name));
    el.innerHTML = `<div class="plhead"><div class="plcover" style="background:${plBg(name, false)}"></div>
      <div><h2 style="margin:0 0 12px">${esc(name)}</h2><button class="btn red" onclick="deletePl()">Delete playlist</button></div></div>` +
      (j.sections.length ? rows(j.sections[0].songs, true) : '');
  }
}

// playlist cover pictures: 1st playlist -> /covers/playlists/1.jpg, 2nd -> 2.jpg, ...
// a playlist with no picture of its own number (for example every new playlist) gets /covers/playlists/default.jpg
function plBg(name, shade) {
  const n = lists.findIndex(p => p.name === name) + 1;          // position of the playlist in the list: 1, 2, 3 ...
  const imgs = (n > 0 ? `url(/covers/playlists/${n}.jpg) center/cover, ` : '') + 'url(/covers/playlists/default.jpg) center/cover, #282828';
  return (shade ? 'linear-gradient(to top, rgba(0,0,0,.75), rgba(0,0,0,0) 60%), ' : '') + imgs;
}

// ---------- actions (each one is a backend socket command) ----------
async function like(id)     { say(await api('like?id=' + id, true)); show(view, plName); }
async function createPl()   { const n = $('#newpl').value; say(await api('create?name=' + enc(n), true)); show('playlists'); }
async function deletePl()   { say(await api('delete?name=' + enc(plName), true)); show('playlists'); }
async function removeFrom(id) { say(await api(`remove?name=${enc(plName)}&id=${id}`, true)); show('playlist', plName); }
async function addTo(sel, id) {
  if (!sel.value) return;
  say(await api(`add?name=${enc(sel.value)}&id=${id}`, true));
  sel.value = '';
}

// ---------- player: Play / Pause / Next / Previous ----------
async function play(id) {
  const j = await api('play?id=' + id, true);
  if (j.error) return say(j);
  cur = j.sections[0].songs[0];
  audio.src = '/audio/' + cur.id + '.wav';
  audio.play();
  $('#ptitle').textContent = cur.title;
  $('#partist').textContent = cur.artist + ' • ' + cur.lang;
  $('#pcover').style.background = coverImg(cur.id) + ', ' + cover(cur.id);
  $('#pcover').textContent = '';
  mark();
}
function toggle() {
  if (!cur) return;
  audio.paused ? audio.play() : audio.pause();
}
async function jump(cmd) {
  if (!cur) return;
  const j = await api(`${cmd}?id=${cur.id}&pl=${enc(ctx)}`);
  play(j.sections[0].songs[0].id);
}
const next = () => jump('next');
const prev = () => jump('prev');

function mark() {
  document.querySelectorAll('.album').forEach(r => r.classList.toggle('now', cur && r.dataset.id == cur.id));
}
audio.onplay  = () => $('#pp').textContent = '⏸';
audio.onpause = () => $('#pp').textContent = '▶';
audio.onended = next;
audio.ontimeupdate = () => { if (audio.duration) $('#bar').value = audio.currentTime / audio.duration * 100; };
$('#bar').oninput = e => { if (audio.duration) audio.currentTime = e.target.value / 100 * audio.duration; };

// ---------- cluster status (shows the load balancing / failover) ----------
document.querySelectorAll('#side a').forEach(a => a.onclick = () => show(a.dataset.v));
$('#crash').onclick = () => fetch('/api/crash?port=' + lastNode, { method: 'POST' });
setInterval(async () => {
  try {
    const j = await (await fetch('/api/nodes')).json();
    $('#nodes').innerHTML = '<b>Backend nodes</b><br>' +
      j.nodes.map(n => `${n.up ? '🟢' : '🔴'} Node ${n.port}${n.port == j.last ? ' ◀ last served' : ''}`).join('<br>');
  } catch (e) { }
}, 1500);

if (user) enter(); else logout();