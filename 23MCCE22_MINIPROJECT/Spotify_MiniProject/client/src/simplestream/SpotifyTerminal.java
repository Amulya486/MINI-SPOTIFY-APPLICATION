package simplestream;

import java.io.*;
import java.util.*;
import javax.sound.sampled.*;

/*
  MODE 2 - the terminal app.  Same backend as the website: every command is sent to the
  MusicServer cluster through Balancer (TCP sockets).  Audio plays locally with javax.sound.
 */
public class SpotifyTerminal {
    static Clip clip;
    static int curId = -1;
    static String curTitle = "", ctx = "";           // ctx = playlist we are playing from ("" = all songs)
    static boolean paused = false, userStopped = false;
    static String user = null;                       // logged-in account (null = logged out)

    public static void main(String[] args) {
        System.out.println("==== SimpleStream - Terminal Spotify (Java sockets) ====");
        help();
        System.out.println("\nPlease  signup <user> <pass>  or  login <user> <pass>");
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("\nspotify" + (user == null ? "" : "(" + user + ")") + "> ");
            if (!sc.hasNextLine()) break;
            String[] w = sc.nextLine().trim().split("\\s+", 2);
            String cmd = w[0].toLowerCase(), arg = w.length > 1 ? w[1].trim() : "";
            try {
                if (user == null && !Set.of("", "login", "signup", "help", "quit", "exit", "nodes", "crash").contains(cmd)) {
                    System.out.println("Please login or signup first.");
                    continue;
                }
                switch (cmd) {
                    case "": break;
                    case "signup":    signup(arg); break;
                    case "login":     login(arg); break;
                    case "logout":    logout(); break;
                    case "whoami":    System.out.println(user == null ? "Not logged in." : "Logged in as " + user); break;
                    case "home":      ctx = ""; run("HOME"); break;
                    case "songs":     ctx = ""; run("SONGS"); break;
                    case "search":    ctx = ""; run("SEARCH", arg); break;
                    case "library":   ctx = ""; run("LIBRARY"); break;
                    case "like":      run("LIKE", arg); break;
                    case "playlists": run("PLAYLISTS"); break;
                    case "open":      ctx = arg; run("PLSONGS", arg); break;
                    case "create":    run("PLCREATE", arg); break;
                    case "delete":    run("PLDELETE", arg); break;
                    case "add":       twoArgs("PLADD", arg); break;
                    case "remove":    twoArgs("PLREMOVE", arg); break;
                    case "play":      play(arg); break;
                    case "pause":     pause(); break;
                    case "resume":    resume(); break;
                    case "next":      jump("NEXT"); break;
                    case "prev":      jump("PREV"); break;
                    case "now":       System.out.println(curId < 0 ? "Nothing playing." : (paused ? "Paused: " : "Playing: ") + curTitle); break;
                    case "nodes":     nodes(); break;
                    case "crash":     Balancer.ask(Integer.parseInt(arg), "CRASH"); System.out.println("Node " + arg + " crashed (demo)."); break;
                    case "help":      help(); break;
                    case "quit": case "exit": stopClip(); System.out.println("Bye!"); System.exit(0); break;
                    default: System.out.println("Unknown command. Type 'help'.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    static void help() {
        System.out.println(
            "\nCommands:\n"
          + "  signup <user> <pass> | login <user> <pass> | logout | whoami\n"
          + "  home | songs | search <text> | library | like <songId>\n"
          + "  playlists | open <playlist> | create <name> | delete <name>\n"
          + "  add <songId> <playlist>      remove <songId> <playlist>\n"
          + "  play <songId> | pause | resume | next | prev | now\n"
          + "  nodes | crash <port>  (show cluster / simulate a node failure)\n"
          + "  help | quit");
    }

    //Send a command to the backend and print the reply. 
    static List<String> run(String... parts) {
        try {
            List<String> r = send(parts);
            show(r);
            return r;
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
            return List.of();
        }
    }

    // Like Balancer.send, but puts the logged-in user name right after the command. 
    static List<String> send(String... parts) throws IOException {
        String[] x = new String[parts.length + 1];
        x[0] = parts[0];
        x[1] = user;
        System.arraycopy(parts, 1, x, 2, parts.length - 1);
        return Balancer.send(x);
    }

    //  accounts 
    static void signup(String arg) throws IOException {
        String[] a = arg.split("\\s+");
        if (a.length != 2) { System.out.println("Usage: signup <username> <password>"); return; }
        List<String> r = Balancer.send("SIGNUP", a[0], a[1]);
        show(r);
        if (r.stream().noneMatch(l -> l.startsWith("ERR")))
            System.out.println("Account created. Now log in:  login " + a[0] + " <password>");
    }

    static void login(String arg) throws IOException {
        String[] a = arg.split("\\s+");
        if (a.length != 2) { System.out.println("Usage: login <username> <password>"); return; }
        List<String> r = Balancer.send("LOGIN", a[0], a[1]);
        show(r);
        if (r.stream().noneMatch(l -> l.startsWith("ERR"))) { user = a[0]; run("HOME"); }
    }

    static void logout() {
        stopClip();
        curId = -1; ctx = ""; paused = false;
        System.out.println("Logged out " + user + ". Bye!");
        user = null;
    }

    static void show(List<String> lines) {
        for (String l : lines) {
            String[] f = l.split("\\|");
            if (l.startsWith("OK|")) System.out.println("(served by node " + f[1] + ", logical clock " + f[2] + ")");
            else if (l.startsWith("ERR|")) System.out.println("! " + f[1]);
            else if (l.startsWith("M|")) System.out.println("* " + f[1]);
            else if (l.startsWith("#")) System.out.println("\n== " + l.substring(1) + " ==");
            else if (l.startsWith("P|")) System.out.printf("  [playlist] %-25s %s songs%n", f[1], f[2]);
            else if (l.startsWith("S|"))
                System.out.printf("  %2s  %-22s %-16s %-8s %s%n", f[1], f[2], f[3], f[4], f[5].equals("1") ? "<3" : "");
        }
    }

    static void twoArgs(String cmd, String arg) {
        String[] a = arg.split("\\s+", 2);
        if (a.length < 2) { System.out.println("Usage: add|remove <songId> <playlist name>"); return; }
        run(cmd, a[1], a[0]);                          // backend expects: name, songId
    }

    // player
    static void play(String id) throws IOException {
        List<String> r = send("PLAY", id);
        String song = firstSong(r);
        if (song == null) { show(r); return; }
        String[] f = song.split("\\|");
        curId = Integer.parseInt(f[1]);
        curTitle = f[2] + " - " + f[3];
        stopClip();
        paused = false;
        System.out.println("Now playing: " + curTitle + "  [" + f[4] + "]");
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new File("server/data/audio/" + curId + ".wav"));
            final Clip c = AudioSystem.getClip();
            c.open(ais);
            userStopped = false;
            c.addLineListener(ev -> {
                // song reached its end by itself -> auto play next
                if (ev.getType() == LineEvent.Type.STOP && c == clip && !userStopped)
                    new Thread(() -> { try { jump("NEXT"); } catch (IOException ignored) { } }).start();
            });
            clip = c;
            c.start();
        } catch (Exception e) {
            System.out.println("(no audio device / file - playback is only simulated: " + e.getMessage() + ")");
        }
    }

    static void pause() {
        if (curId < 0) { System.out.println("Nothing playing."); return; }
        userStopped = true; paused = true;
        if (clip != null) clip.stop();
        System.out.println("Paused: " + curTitle);
    }

    static void resume() {
        if (curId < 0 || !paused) { System.out.println("Nothing paused."); return; }
        paused = false; userStopped = false;
        if (clip != null) clip.start();
        System.out.println("Playing: " + curTitle);
    }

    static void jump(String cmd) throws IOException {
        if (curId < 0) { System.out.println("Play a song first."); return; }
        List<String> r = send(cmd, "" + curId, ctx);
        String song = firstSong(r);
        if (song != null) play(song.split("\\|")[1]);
    }

    static String firstSong(List<String> r) {
        for (String l : r) if (l.startsWith("S|")) return l;
        return null;
    }

    static void stopClip() {
        userStopped = true;
        if (clip != null) { clip.stop(); clip.close(); clip = null; }
    }

    static void nodes() {
        for (int p : MusicServer.PORTS) System.out.println("  node " + p + " : " + (Balancer.isUp(p) ? "UP" : "DOWN"));
    }
}