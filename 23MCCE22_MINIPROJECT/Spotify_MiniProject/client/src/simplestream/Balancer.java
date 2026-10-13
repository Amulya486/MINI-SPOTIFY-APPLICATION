package simplestream;

import java.io.*;
import java.net.*;
import java.util.*;

//CLIENT-SIDE LOAD BALANCER used by BOTH the website and the terminal app.
//Round-robin: every request goes to the next node in the list.
//Failover: if a node does not answer, the next node is tried automatically.

public class Balancer {
    static int counter = 0;
    public static volatile int lastNode = MusicServer.PORTS[0];

    //Send one command (parts are joined with TAB) to the cluster; returns the reply lines (without END).
    public static List<String> send(String... parts) throws IOException {
        String cmd = String.join("\t", parts);
        int n = MusicServer.PORTS.length, start;
        synchronized (Balancer.class) { start = counter++ % n; }       // round robin
        for (int i = 0; i < n; i++) {
            int port = MusicServer.PORTS[(start + i) % n];
            try {
                List<String> reply = ask(port, cmd);
                lastNode = port;
                return reply;
            } catch (IOException e) {
                System.out.println("[Balancer] node " + port + " not reachable -> trying next node");
            }
        }
        throw new IOException("All backend nodes are down. Start MusicServer first.");
    }

    // Talk to one specific node over a TCP socket. 
    public static List<String> ask(int port, String cmd) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", port), 800);
            s.setSoTimeout(4000);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
            w.print(cmd + "\n");
            w.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            List<String> lines = new ArrayList<>();
            String l;
            while ((l = r.readLine()) != null && !l.equals("END")) lines.add(l);
            if (l == null) throw new IOException("connection closed");
            return lines;
        }
    }

    public static boolean isUp(int port) {
        try { ask(port, "PING"); return true; } catch (IOException e) { return false; }
    }
}
