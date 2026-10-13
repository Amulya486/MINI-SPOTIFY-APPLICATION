# SimpleStream - Spotify-like app in pure Java (Socket Programming)

Java only, no libraries, no frameworks. Everything talks over `java.net.Socket / ServerSocket`.

A music streaming app with a website and a terminal client, both connected to a **cluster of 3 socket-server nodes**.
It supports sign up / log in with email, liked songs, playlists, recently played, and your own mp3 songs.
Accounts and user data are **saved to disk**, so they are still there after a restart.

## How to run (JDK 11+ needed - check with `javac -version`)

Open a terminal **inside the project folder** (the one that contains `run.bat`, `run.sh`, `client/`, `server/` and `public/`).

**Step 1 - build (only the first time, and again after any code change):**

```
run.bat build         (Windows)         ./run.sh build         (Linux/Mac)
```

You should see `Build OK`.

**Step 2 - start the app:**

| Terminal | Command (Windows) | Command (Linux/Mac) | What it does |
|---|---|---|---|
| 1 | `run web` | `./run.sh web` | starts the 3-node socket backend **and** the website -> open **http://localhost:8080** (first run also generates 18 WAV songs, ~5 s) |
| 2 (optional) | `run terminal` | `./run.sh terminal` | terminal Spotify (can run together with the website) |

So the whole thing is just **two commands**: `run build`, then `run web`.

Notes:
- `run web` starts the backend for you. On Windows the backend opens in a second small window (named "SimpleStream Backend"); on Linux/Mac it runs in the background and stops when you press `Ctrl+C`.
- The terminal app needs the backend running, so start `run web` first (or use `run backend`, below).
- Optional: `run backend` starts **only** the 3 backend nodes (no website). Use it if you want to run just the terminal app.
- Without scripts: `java -cp server/out simplestream.MusicServer` (backend) and `java -cp server/out simplestream.SpotifyWeb` (website).
- If you see "Address already in use", an old backend is still running - close the old window/terminal and start again.

**Quick start on the website:** open http://localhost:8080 -> **Sign Up** tab -> enter email, username, password -> **Create account** -> you are taken to the **Log In** tab -> log in with the **email and password**.

## Files

**Client source code (in `client/src/simplestream/`)**

| File | Role |
|---|---|
| `Balancer.java`      | CLIENT SIDE: round-robin load balancing + failover over sockets (used by both modes) |
| `SpotifyWeb.java`    | MODE 1: tiny HTTP server (ServerSocket) -> serves `public/` (HTML/CSS/JS), audio, `/api/*` |
| `SpotifyTerminal.java` | MODE 2: command-line Spotify, plays audio with javax.sound |

**Server source code (in `server/src/simplestream/`)**

| File | Role |
|---|---|
| `MusicServer.java`   | BACKEND: 3 socket server nodes (ports 5001-5003), accounts (email + username + password), saving/loading to disk, text protocol, replication, Lamport clock |
| `Catalog.java`, `Song.java` | 18 songs: 6 Hindi, 6 English, 6 Telugu |
| `AudioGenerator.java` | creates a different tune per song (own scale/tempo/key) -> `server/data/audio/<id>.wav` |

**Website (in `public/`)**

| File | Role |
|---|---|
| `index.html` | the page: login / sign-up card, sidebar, player bar |
| `css/style.css` | Spotify-style dark theme |
| `js/app.js` | login / sign-up logic, rendering, player controls, calls `/api/...` |

**Scripts and data**

| File / folder | Role |
|---|---|
| `run.bat`, `run.sh` | `build`, `web`, `terminal`, `backend` commands |
| `server/data/audio/` | the song files (`<id>.wav` or `<id>.mp3`) |
| `server/data/users.db` | saved accounts: username, email, password hash (created automatically) |
| `server/data/userdata.db` | saved liked songs, playlists, recently played, play counts (created automatically) |

To **reset everything** (delete all accounts and likes), stop the app and delete `users.db` and `userdata.db`.

## Accounts (sign up / log in / log out)

- **Sign Up** needs **email, username and password**. The email must be valid and not already used; the username must be 3-15 letters/digits/`_` and not already taken; the password needs at least 3 characters.
- After signing up, the website shows a "Account created" message and switches to the **Log In** tab.
- **Log In** on the website uses **email + password**.
- **Log out** button in the sidebar. The website remembers who is logged in (browser `localStorage`), so a page refresh does not log you out.
- Passwords are stored only as **SHA-256 hashes**, never as plain text.
- Each user has their **own** Library (liked songs), Playlists and Recently Played.
- Accounts are saved in `server/data/users.db`, so they survive stopping and starting the server.
- The terminal app uses the older style: `signup <user> <pass>` and `login <user> <pass>` (username, no email). Accounts made there have no email and cannot be used for email login on the website; they can still log in with their username.

## What is saved (persistence)

| Data | Saved in | Survives restart? |
|---|---|---|
| Accounts (email, username, password hash) | `server/data/users.db` | Yes |
| Liked songs (Library) | `server/data/userdata.db` | Yes |
| Playlists and their songs | `server/data/userdata.db` | Yes |
| Recently Played | `server/data/userdata.db` | Yes |
| Play counts ("Most Played") | `server/data/userdata.db` | Yes |

Every write (sign up, like, create/add/remove/delete playlist, play) is saved to disk right after it is applied.
When the backend starts, all 3 nodes load these files, so every node has the same data again.

## Use your own music

Song files are named by the song's **id**, which is its position in `Catalog.java` (first song = 1, second = 2, ... 18).

1. Rename your mp3 to the id, e.g. `7.mp3`, and put it in `server/data/audio/`.
2. **Delete the matching `7.wav`** (the website plays the `.wav` first and only uses the `.mp3` if the `.wav` is missing).
3. Change the title and artist for that song in `Catalog.java`, then run `run build` and restart.

To add **new** songs, add lines at the **end** of the `SONGS` array in `Catalog.java` (they become ids 19, 20, ...) and add `19.mp3`, `20.mp3`.
Do not insert songs in the middle of the list - that changes the ids of later songs and mixes up saved likes and playlists.

The website plays mp3 or wav. The terminal app only plays **WAV** (convert mp3 to wav first).

## Architecture

```
 Browser --HTTP--> SpotifyWeb --\
                                 >-- Balancer --TCP--> MusicServer node 5001 \
 SpotifyTerminal ---------------/      (round robin)   MusicServer node 5002  } replicate writes
                                                       MusicServer node 5003 /
                                                              |
                                              saved to server/data/users.db + userdata.db
```

Protocol: request `COMMAND<TAB>arg<TAB>arg` + newline. Reply `OK|port|clock`, data lines, then `END`.
One short TCP connection per request; the server uses one thread per connection.

Login flow on the website: the browser sends the email and password to `SpotifyWeb`, which sends `LOGIN<TAB>email<TAB>password` to a node.
The node finds the account for that email, checks the password hash and replies with the username (`U|username`).
After that, every request carries the username, and each user's data is kept separately.

## Distributed paradigms demonstrated (viva points)

1. **Client-Server** - website and terminal are clients; MusicServer nodes are servers (sockets).
2. **Load balancing (round robin)** - `Balancer.send()` picks the next node each request
   (watch "served by node ..." in the terminal, or the node list in the website sidebar).
3. **Replication (write-all)** - a write (sign up / play / like / create / add / remove / delete playlist) is applied on
   the node that receives it, then forwarded as `REPL` to the other nodes (`MusicServer.replicate`).
   Any node can then answer reads with the same data.
4. **Fault tolerance / failover** - if a node does not answer, `Balancer` tries the next one.
   Demo: terminal `crash 5001` (or the website's "Crash last node" button); the app keeps working.
5. **Lamport logical clock** - each write increments the node's clock; a replica sets
   `clock = max(own, received) + 1`. Shown in terminal as "logical clock N" and in the backend log.
6. **Multithreading / concurrency** - thread per connection; `synchronized` protects the data (and the file writes).
7. **Stateless requests** - NEXT/PREV carry the current song id + playlist, so any node can answer them.
8. **Persistence (durability)** - accounts, likes and playlists are written to files after every write and reloaded at start-up,
   so data is not lost when the servers are stopped.

Limitations (say this in viva): a crashed node is not re-synced when restarted (restart the whole
backend); data is saved in simple text files (`users.db`, `userdata.db`) shared by the 3 nodes, not in a real database;
passwords use plain SHA-256 without salt; the username is sent with each request instead of using a session token (kept simple for the demo).

## Terminal commands
```
signup <user> <pass> | login <user> <pass> | logout | whoami
home | songs | search <text> | library | like <id>
playlists | open <name> | create <name> | delete <name>
add <songId> <playlist> | remove <songId> <playlist>
play <id> | pause | resume | next | prev | now
nodes | crash <port> | help | quit
```
The first screen asks you to sign up (e.g. `signup alice 1234`) or log in (`login alice 1234`).
Terminal accounts use a username and password only (no email).

## Website features

- Spotify-style login card with **Log In / Sign Up** tabs and a **Log out** button.
- Song cards with cover art (gradient + music symbol, hover ▶ button) for **Home**, **Search**, **Library** (♥), **All Songs** and **Playlists**.
- Playlists: create / open / delete, "＋ Playlist" on a song to add it, ✕ to remove it.
- Player bar with Play/Pause, Next, Previous and a seek bar.
- Sidebar shows the 3 backend nodes (🟢 up / 🔴 down, and which node served the last request) and a "Crash last node" button for the fault-tolerance demo.