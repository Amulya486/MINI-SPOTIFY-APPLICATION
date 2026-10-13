package simplestream;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * MODE 1 - the website.  A raw HTTP server written with java.net.ServerSocket (no framework).
 *   Browser --HTTP--> SpotifyWeb --TCP socket (Balancer)--> MusicServer node
 * It serves the HTML/CSS/JS from /public, streams audio, and turns /api/... calls into
 * backend socket commands, replying with JSON.
 */
public class SpotifyWeb {
    static final File PUBLIC = new File("public"), AUDIO = new File("server/data/audio");

    // {api name, backend COMMAND, query parameters in order}  ("user" = logged-in account)
    static final String[][] ROUTES = {
        {"signup", "SIGNUP", "user", "pass", "email"}, {"login", "LOGIN", "user", "pass"},
        {"songs", "SONGS", "user"}, {"search", "SEARCH", "user", "q"}, {"home", "HOME", "user"}, {"library", "LIBRARY", "user"},
        {"like", "LIKE", "user", "id"}, {"playlists", "PLAYLISTS", "user"}, {"playlist", "PLSONGS", "user", "name"},
        {"create", "PLCREATE", "user", "name"}, {"delete", "PLDELETE", "user", "name"},
        {"add", "PLADD", "user", "name", "id"}, {"remove", "PLREMOVE", "user", "name", "id"},
        {"play", "PLAY", "user", "id"}, {"next", "NEXT", "user", "id", "pl"}, {"prev", "PREV", "user", "id", "pl"}
    };

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        ServerSocket server = new ServerSocket(port);
        ExecutorService pool = Executors.newCachedThreadPool();
        System.out.println("SimpleStream website running -> http://localhost:" + port);
        System.out.println("(MusicServer must be running too)");
        while (true) {
            Socket s = server.accept();
            pool.submit(() -> handle(s));
        }
    }

    static void handle(Socket s) {
        try (s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = s.getOutputStream();
            String req = in.readLine();
            if (req == null || req.isEmpty()) return;
            String range = null, h;
            while ((h = in.readLine()) != null && !h.isEmpty())
                if (h.toLowerCase().startsWith("range:")) range = h.substring(6).trim();

            String target = req.split(" ")[1];
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Map<String, String> q = query(target);

            if (path.startsWith("/api/")) api(out, path.substring(5), q);
            else if (path.startsWith("/audio/")) audio(out, path.substring(7), range);
            else staticFile(out, path);
        } catch (Exception e) {
            // client closed connection (normal while seeking audio)
        }
    }

    // ---------- API: HTTP request -> backend socket command -> JSON ----------
    static void api(OutputStream out, String name, Map<String, String> q) throws IOException {
        if (name.equals("nodes")) {
            StringBuilder b = new StringBuilder("{\"nodes\":[");
            for (int i = 0; i < MusicServer.PORTS.length; i++)
                b.append(i > 0 ? "," : "").append("{\"port\":").append(MusicServer.PORTS[i])
                 .append(",\"up\":").append(Balancer.isUp(MusicServer.PORTS[i])).append("}");
            send(out, 200, "application/json", (b + "],\"last\":" + Balancer.lastNode + "}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (name.equals("crash")) {
            try { Balancer.ask(Integer.parseInt(q.getOrDefault("port", "0")), "CRASH"); } catch (Exception ignored) { }
            send(out, 200, "application/json", "{\"ok\":true}".getBytes());
            return;
        }
        for (String[] r : ROUTES) {
            if (!r[0].equals(name)) continue;
            String[] parts = new String[r.length - 1];
            parts[0] = r[1];
            for (int i = 2; i < r.length; i++) parts[i - 1] = q.getOrDefault(r[i], "").replaceAll("[\\t\\r\\n]", " ");
            String json;
            try { json = toJson(Balancer.send(parts)); }
            catch (IOException e) { json = "{\"error\":" + quote(e.getMessage()) + ",\"sections\":[],\"playlists\":[]}"; }
            send(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(out, 404, "text/plain", "Unknown API".getBytes());
    }

    /** Backend reply lines -> JSON.  Lines: OK|node  ERR|msg  M|msg  #Section title  S|song...  P|playlist|count */
    static String toJson(List<String> lines) {
        String node = "0", err = "", msg = "", uname = "", title = null;
        List<String> secs = new ArrayList<>(), songs = new ArrayList<>(), pls = new ArrayList<>();
        for (String l : lines) {
            String[] f = l.split("\\|");
            if (l.startsWith("OK|")) node = f[1];
            else if (l.startsWith("ERR|")) err = f[1];
            else if (l.startsWith("U|")) uname = f[1];
            else if (l.startsWith("M|")) msg = f[1];
            else if (l.startsWith("#")) {
                if (title != null) secs.add(section(title, songs));
                title = l.substring(1); songs = new ArrayList<>();
            } else if (l.startsWith("S|"))
                songs.add("{\"id\":" + f[1] + ",\"title\":" + quote(f[2]) + ",\"artist\":" + quote(f[3]) + ",\"lang\":" + quote(f[4])
                        + ",\"liked\":" + f[5] + ",\"plays\":" + f[6] + "}");
            else if (l.startsWith("P|")) pls.add("{\"name\":" + quote(f[1]) + ",\"count\":" + f[2] + "}");
        }
        if (title != null) secs.add(section(title, songs));
        return "{\"node\":" + node + ",\"error\":" + quote(err) + ",\"msg\":" + quote(msg) + ",\"username\":" + quote(uname)
             + ",\"sections\":[" + String.join(",", secs) + "],\"playlists\":[" + String.join(",", pls) + "]}";
    }

    static String section(String t, List<String> songs) {
        return "{\"title\":" + quote(t) + ",\"songs\":[" + String.join(",", songs) + "]}";
    }

    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static Map<String, String> query(String target) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        if (!target.contains("?")) return m;
        for (String kv : target.substring(target.indexOf('?') + 1).split("&")) {
            String[] x = kv.split("=", 2);
            m.put(URLDecoder.decode(x[0], "UTF-8"), x.length > 1 ? URLDecoder.decode(x[1], "UTF-8") : "");
        }
        return m;
    }

    // ---------- static files + audio streaming ----------
    static void staticFile(OutputStream out, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        File f = new File(PUBLIC, path).getCanonicalFile();
        if (!f.getPath().startsWith(PUBLIC.getCanonicalPath()) || !f.isFile()) {
            send(out, 404, "text/plain", "Not found".getBytes());
            return;
        }
        String ext = path.substring(path.lastIndexOf('.') + 1);
        String type = ext.equals("html") ? "text/html" : ext.equals("css") ? "text/css" : ext.equals("js") ? "application/javascript"
                    : ext.equals("jpg") || ext.equals("jpeg") ? "image/jpeg" : ext.equals("png") ? "image/png"
                    : ext.equals("svg") ? "image/svg+xml" : "application/octet-stream";
        // text types get a charset; images must not
        String ct = type.startsWith("image/") || type.startsWith("application/octet") ? type : type + "; charset=utf-8";
        send(out, 200, ct, Files.readAllBytes(f.toPath()));
    }

    /** /audio/<id>.wav  - supports HTTP Range so the browser can seek. */
    static void audio(OutputStream out, String name, String range) throws IOException {
        String id = name.replaceAll("\\D", "");
        File f = new File(AUDIO, id + ".wav");
        String type = "audio/wav";
        if (!f.isFile()) { f = new File(AUDIO, id + ".mp3"); type = "audio/mpeg"; }
        if (id.isEmpty() || !f.isFile()) { send(out, 404, "text/plain", "No audio".getBytes()); return; }

        long len = f.length(), start = 0, end = len - 1;
        boolean partial = range != null && range.startsWith("bytes=");
        if (partial) {
            String[] r = range.substring(6).split("-");
            try {
                if (!r[0].isEmpty()) start = Long.parseLong(r[0]);
                if (r.length > 1 && !r[1].isEmpty()) end = Long.parseLong(r[1]);
            } catch (NumberFormatException ignored) { }
            end = Math.min(end, len - 1);
        }
        byte[] buf = new byte[(int) (end - start + 1)];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) { raf.seek(start); raf.readFully(buf); }
        String head = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n"
            + "Content-Type: " + type + "\r\nAccept-Ranges: bytes\r\nContent-Length: " + buf.length + "\r\n"
            + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + len + "\r\n" : "")
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes());
        out.write(buf);
        out.flush();
    }

    static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type
            + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes());
        out.write(body);
        out.flush();
    }
}