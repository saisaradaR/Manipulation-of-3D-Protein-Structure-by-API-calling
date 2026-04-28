import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class ProteinAPI {

    public static void main(String[] args) throws Exception {

    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    System.out.println("Server running on port: " + port);
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // ── /protein?query=hemoglobin  → returns PDB file text
        server.createContext("/protein", exchange -> {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Parse query param
            String query = exchange.getRequestURI().getQuery();
            String input = "";
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("query=")) {
                        input = URLDecoder.decode(
                            param.substring(6), StandardCharsets.UTF_8
                        ).trim();
                        break;
                    }
                }
            }

            if (input.isEmpty()) {
                sendError(exchange, 400, "ERROR: query parameter is required. Example: /protein?query=hemoglobin");
                return;
            }

            try {
                System.out.println("User input: " + input);

                String pdbId = resolveToPdbId(input);
                System.out.println("Resolved PDB ID: " + pdbId);

                String pdbData = fetchPdb(pdbId);

                byte[] response = pdbData.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "ERROR: " + e.getMessage());
            }
        });

        // ── /suggest?query=hemo  → returns JSON list of suggestions
        server.createContext("/suggest", exchange -> {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

            String query = exchange.getRequestURI().getQuery();
            String input = "";
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("query=")) {
                        input = URLDecoder.decode(
                            param.substring(6), StandardCharsets.UTF_8
                        ).trim();
                        break;
                    }
                }
            }

            if (input.isEmpty()) {
                sendJson(exchange, "[]");
                return;
            }

            try {
                String suggestions = fetchSuggestions(input, 6);
                sendJson(exchange, suggestions);
            } catch (Exception e) {
                sendJson(exchange, "[]");
            }
        });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("✅ Server running at http://localhost:8080");
        System.out.println("   Try: http://localhost:8080/protein?query=hemoglobin");
    }

    // ─────────────────────────────────────────────────────────────────
    //  STEP 1 — Turn any user input into a valid PDB ID
    //           Uses RCSB Search API v2 (full-text + structure name)
    // ─────────────────────────────────────────────────────────────────
    public static String resolveToPdbId(String input) throws Exception {

        // Case A: user typed a 4-char PDB ID directly (e.g. "1A3N", "4ins")
        if (input.matches("(?i)[a-zA-Z0-9]{4}")) {
            String id = input.toUpperCase();
            System.out.println("Direct PDB ID detected: " + id);
            return id;
        }

        // Case B: free-text name — search RCSB for best match
        return searchRCSB(input);
    }

    // ─────────────────────────────────────────────────────────────────
    //  RCSB Search API v2
    //  Docs: https://search.rcsb.org/index.html#search-api
    //
    //  We send a combined query:
    //    • full_text search  (catches "hemoglobin", "spike protein", etc.)
    //    • struct.title match (catches exact structure names)
    //  Ranked by score, we take the top result.
    // ─────────────────────────────────────────────────────────────────
    private static String searchRCSB(String userInput) throws Exception {

        // Escape quotes so the JSON is valid
        String safe = userInput.replace("\\", "").replace("\"", "");

        // Build the search payload
        String jsonBody = "{"
            + "\"query\": {"
            + "  \"type\": \"group\","
            + "  \"logical_operator\": \"or\","
            + "  \"nodes\": ["
            + "    {"
            + "      \"type\": \"terminal\","
            + "      \"service\": \"full_text\","
            + "      \"parameters\": { \"value\": \"" + safe + "\" }"
            + "    },"
            + "    {"
            + "      \"type\": \"terminal\","
            + "      \"service\": \"text\","
            + "      \"parameters\": {"
            + "        \"attribute\": \"struct.title\","
            + "        \"operator\": \"contains_words\","
            + "        \"value\": \"" + safe + "\""
            + "      }"
            + "    }"
            + "  ]"
            + "},"
            + "\"return_type\": \"entry\","
            + "\"request_options\": {"
            + "  \"paginate\": { \"start\": 0, \"rows\": 1 },"
            + "  \"sort\": [{ \"sort_by\": \"score\", \"direction\": \"desc\" }]"
            + "}"
            + "}";

        URL url = new URL("https://search.rcsb.org/rcsbsearch/v2/query");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");

        // Send body
        try (OutputStream out = conn.getOutputStream()) {
            out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        System.out.println("RCSB search HTTP status: " + code);

        if (code != 200) {
            throw new Exception(
                "RCSB search returned HTTP " + code + " for query: " + userInput
            );
        }

        // Read response
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }

        String json = sb.toString();
        System.out.println("RCSB raw response: " + json);

        // ── Parse the first "identifier" value from JSON ──────────────
        // Response: {"result_set":[{"identifier":"1A3N","score":...},...]}
        int idx = json.indexOf("\"identifier\"");
        if (idx == -1) {
            throw new Exception(
                "No results found for \"" + userInput + "\". "
                + "Try a different name or a 4-character PDB ID like 1A3N."
            );
        }

        int colon  = json.indexOf(":", idx);
        int quote1 = json.indexOf("\"", colon) + 1;
        int quote2 = json.indexOf("\"", quote1);

        if (quote1 <= 0 || quote2 <= quote1) {
            throw new Exception("Failed to parse RCSB search response.");
        }

        String pdbId = json.substring(quote1, quote2).toUpperCase();
        System.out.println("Best RCSB match for \"" + userInput + "\": " + pdbId);
        return pdbId;
    }

    // ─────────────────────────────────────────────────────────────────
    //  STEP 2 — Download the actual PDB structure file from RCSB
    // ─────────────────────────────────────────────────────────────────
    public static String fetchPdb(String pdbId) throws Exception {

        // Try both RCSB download endpoints
        String[] urls = {
            "https://files.rcsb.org/download/" + pdbId + ".pdb",
            "https://files.rcsb.org/view/"     + pdbId + ".pdb"
        };

        for (String urlStr : urls) {
            System.out.println("Fetching PDB from: " + urlStr);
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "ProteinViewer/1.0");

                if (conn.getResponseCode() == 200) {
                    StringBuilder data = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(),
                                StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            data.append(line).append("\n");
                        }
                    }
                    String pdb = data.toString();

                    // Sanity check — a real PDB file always has ATOM records
                    if (pdb.contains("ATOM") || pdb.contains("HETATM")) {
                        System.out.println("PDB file fetched successfully (" 
                            + pdb.length() + " chars)");
                        return pdb;
                    }
                }
            } catch (Exception e) {
                System.out.println("Failed on " + urlStr + ": " + e.getMessage());
            }
        }

        throw new Exception(
            "Could not download PDB file for ID: " + pdbId
            + ". The structure may be obsolete or not yet publicly released."
        );
    }

    // ─────────────────────────────────────────────────────────────────
    //  /suggest endpoint — returns top N matches as JSON array
    //  [ { "id": "1A3N", "title": "DEOXY HUMAN HEMOGLOBIN" }, ... ]
    // ─────────────────────────────────────────────────────────────────
    private static String fetchSuggestions(String userInput, int rows) throws Exception {
        String safe = userInput.replace("\\", "").replace("\"", "");

        String jsonBody = "{"
            + "\"query\": {"
            + "  \"type\": \"terminal\","
            + "  \"service\": \"full_text\","
            + "  \"parameters\": { \"value\": \"" + safe + "\" }"
            + "},"
            + "\"return_type\": \"entry\","
            + "\"request_options\": {"
            + "  \"paginate\": { \"start\": 0, \"rows\": " + rows + " },"
            + "  \"sort\": [{ \"sort_by\": \"score\", \"direction\": \"desc\" }]"
            + "}"
            + "}";

        URL url = new URL("https://search.rcsb.org/rcsbsearch/v2/query");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "application/json");

        try (OutputStream out = conn.getOutputStream()) {
            out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() != 200) return "[]";

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }

        // Extract all identifiers → fetch their titles from RCSB Data API
        String json = sb.toString();
        StringBuilder result = new StringBuilder("[");
        boolean first = true;
        int searchFrom = 0;

        while (true) {
            int idx = json.indexOf("\"identifier\"", searchFrom);
            if (idx == -1) break;

            int colon  = json.indexOf(":", idx);
            int q1     = json.indexOf("\"", colon) + 1;
            int q2     = json.indexOf("\"", q1);
            if (q1 <= 0 || q2 <= q1) break;

            String id = json.substring(q1, q2).toUpperCase();
            String title = fetchTitle(id);

            if (!first) result.append(",");
            result.append("{\"id\":\"").append(id)
                  .append("\",\"title\":\"").append(title).append("\"}");
            first = false;
            searchFrom = q2 + 1;
        }

        result.append("]");
        return result.toString();
    }

    // Fetches the structure title from RCSB Data API
    private static String fetchTitle(String pdbId) {
        try {
            URL url = new URL(
                "https://data.rcsb.org/rest/v1/core/entry/" + pdbId
            );
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() != 200) return pdbId;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            String json = sb.toString();
            // "title":"DEOXY HUMAN HEMOGLOBIN"
            int idx = json.indexOf("\"title\"");
            if (idx == -1) return pdbId;

            int colon = json.indexOf(":", idx);
            int q1    = json.indexOf("\"", colon) + 1;
            int q2    = json.indexOf("\"", q1);
            if (q1 <= 0 || q2 <= q1) return pdbId;

            return json.substring(q1, q2)
                       .replace("\\\"", "'")
                       .replace("\\", "");

        } catch (Exception e) {
            return pdbId;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private static void sendError(
            com.sun.net.httpserver.HttpExchange ex, int code, String msg)
            throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void sendJson(
            com.sun.net.httpserver.HttpExchange ex, String json)
            throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}

