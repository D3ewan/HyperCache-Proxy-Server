package com.deewan;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class ProxyServer {

    static final int MAX_BYTES = 4096;
    static final int MAX_CLIENTS = 400;
    static final long MAX_SIZE = 200L * (1 << 20);
    static final long MAX_ELEMENT_SIZE = 10L * (1 << 20);

    static CacheElement head = null;
    static long cacheSize = 0;

    static final Semaphore semaphore = new Semaphore(MAX_CLIENTS);
    static final ReentrantLock cacheLock = new ReentrantLock();

    // -------------------------------------------------------------------------
    // Cache Element
    // -------------------------------------------------------------------------

    static class CacheElement {
        byte[] data;
        int len;
        String url;
        long lruTimeTrack;
        CacheElement next;

        CacheElement(byte[] data, int len, String url) {
            this.data = Arrays.copyOf(data, len);
            this.len = len;
            this.url = url;
            this.lruTimeTrack = System.currentTimeMillis() / 1000L;
            this.next = null;
        }
    }

    // -------------------------------------------------------------------------
    // Cache Operations
    // -------------------------------------------------------------------------

    static CacheElement find(String url) {
        CacheElement site = null;
        cacheLock.lock();
        try {
            if (head != null) {
                site = head;
                while (site != null) {
                    if (site.url.equals(url)) {
                        System.out.println("url found in cache");
                        site.lruTimeTrack = System.currentTimeMillis() / 1000L;
                        break;
                    }
                    site = site.next;
                }
            }
            if (site == null) System.out.println("url not found in cache");
        } finally {
            cacheLock.unlock();
        }
        return site;
    }

    static void removeCacheElement() {
        cacheLock.lock();
        try {
            if (head == null) return;

            CacheElement temp = head;
            CacheElement prevOfTemp = null;
            CacheElement prev = head;

            for (CacheElement q = head; q.next != null; q = q.next) {
                if (q.next.lruTimeTrack < temp.lruTimeTrack) {
                    temp = q.next;
                    prevOfTemp = q;
                }
            }

            if (temp == head) {
                head = head.next;
            } else {
                prevOfTemp.next = temp.next;
            }

            cacheSize -= (temp.len + 64 + temp.url.length() + 1);
            System.out.println("Evicted from cache: " + temp.url);
        } finally {
            cacheLock.unlock();
        }
    }

    static int addCacheElement(byte[] data, int size, String url) {
        cacheLock.lock();
        try {
            long elementSize = size + 1 + url.length() + 64;
            if (elementSize > MAX_ELEMENT_SIZE) {
                System.out.println("Element too large, not caching.");
                return 0;
            }
            while (cacheSize + elementSize > MAX_SIZE) {
                removeCacheElement();
            }
            CacheElement element = new CacheElement(data, size, url);
            element.next = head;
            head = element;
            cacheSize += elementSize;
            System.out.println("Added to cache: " + url);
            return 1;
        } finally {
            cacheLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // HTTP Error Responses
    // -------------------------------------------------------------------------

    static void sendErrorMessage(OutputStream out, int statusCode) {
        try {
            String currentTime = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
                    .format(new Date());
            String body;
            String status;

            switch (statusCode) {
                case 400: status = "400 Bad Request";
                    body = "<HTML><HEAD><TITLE>400 Bad Request</TITLE></HEAD><BODY><H1>400 Bad Request</H1></BODY></HTML>"; break;
                case 403: status = "403 Forbidden";
                    body = "<HTML><HEAD><TITLE>403 Forbidden</TITLE></HEAD><BODY><H1>403 Forbidden</H1></BODY></HTML>"; break;
                case 404: status = "404 Not Found";
                    body = "<HTML><HEAD><TITLE>404 Not Found</TITLE></HEAD><BODY><H1>404 Not Found</H1></BODY></HTML>"; break;
                case 500: status = "500 Internal Server Error";
                    body = "<HTML><HEAD><TITLE>500 Internal Server Error</TITLE></HEAD><BODY><H1>500 Internal Server Error</H1></BODY></HTML>"; break;
                case 501: status = "501 Not Implemented";
                    body = "<HTML><HEAD><TITLE>501 Not Implemented</TITLE></HEAD><BODY><H1>501 Not Implemented</H1></BODY></HTML>"; break;
                case 505: status = "505 HTTP Version Not Supported";
                    body = "<HTML><HEAD><TITLE>505 HTTP Version Not Supported</TITLE></HEAD><BODY><H1>505 HTTP Version Not Supported</H1></BODY></HTML>"; break;
                default: return;
            }

            String response = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Content-Type: text/html\r\n"
                    + "Connection: close\r\n"
                    + "Date: " + currentTime + "\r\n"
                    + "Server: JavaProxy/1.0\r\n\r\n"
                    + body;

            out.write(response.getBytes());
            out.flush();
            System.out.println("Sent error: " + statusCode);
        } catch (IOException e) {
            System.err.println("Failed to send error response: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // HTTP Version Check
    // -------------------------------------------------------------------------

    static boolean checkHTTPVersion(String version) {
        return version != null && (version.equals("HTTP/1.1") || version.equals("HTTP/1.0"));
    }

    // -------------------------------------------------------------------------
    // Parsed HTTP Request
    // -------------------------------------------------------------------------

    static class ParsedRequest {
        String method;
        String host;
        String path;
        String version;
        int port = 80;
        Map<String, String> headers = new LinkedHashMap<>();

        static ParsedRequest parse(String raw) {
            if (raw == null || raw.isEmpty()) return null;

            ParsedRequest req = new ParsedRequest();

            // Split off just the header section
            String headerSection = raw.contains("\r\n\r\n")
                    ? raw.split("\r\n\r\n", 2)[0]
                    : raw;

            String[] lines = headerSection.split("\r\n");
            if (lines.length == 0) return null;

            // --- Request Line ---
            String[] requestLine = lines[0].trim().split("\\s+");
            if (requestLine.length < 3) return null;

            req.method  = requestLine[0];
            String url  = requestLine[1];
            req.version = requestLine[2];

            // --- Parse Headers ---
            for (int i = 1; i < lines.length; i++) {
                int colonIdx = lines[i].indexOf(':');
                if (colonIdx > 0) {
                    String key   = lines[i].substring(0, colonIdx).trim();
                    String value = lines[i].substring(colonIdx + 1).trim();
                    req.headers.put(key, value);
                }
            }

            // --- Parse Host and Path from URL ---
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // Absolute-form URL: browsers and curl -x send this
                try {
                    URL parsed = new URL(url);
                    req.host = parsed.getHost();
                    req.port = parsed.getPort() == -1
                            ? (url.startsWith("https://") ? 443 : 80)
                            : parsed.getPort();

                    String p = parsed.getPath();
                    req.path = (p == null || p.isEmpty()) ? "/" : p;
                    if (parsed.getQuery() != null && !parsed.getQuery().isEmpty()) {
                        req.path += "?" + parsed.getQuery();
                    }
                } catch (MalformedURLException e) {
                    System.err.println("Malformed URL: " + url);
                    return null;
                }
            } else {
                // Origin-form: use Host header
                req.path = url;
                String hostHeader = req.headers.get("Host");
                if (hostHeader == null || hostHeader.isEmpty()) {
                    System.err.println("No Host header found.");
                    return null;
                }
                if (hostHeader.contains(":")) {
                    String[] parts = hostHeader.split(":", 2);
                    req.host = parts[0];
                    try { req.port = Integer.parseInt(parts[1]); }
                    catch (NumberFormatException ignored) { req.port = 80; }
                } else {
                    req.host = hostHeader;
                    req.port = 80;
                }
            }

            System.out.println("Parsed -> method=" + req.method
                    + " host=" + req.host + " port=" + req.port
                    + " path=" + req.path + " version=" + req.version);

            return req;
        }

        String toWireFormat() {
            headers.put("Connection", "close");
            headers.put("Host", host);
            // Strip proxy-only headers
            headers.remove("Proxy-Connection");
            headers.remove("Proxy-Authorization");

            StringBuilder sb = new StringBuilder();
            sb.append("GET ").append(path).append(" ").append(version).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n");
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Forward to Remote Server
    // -------------------------------------------------------------------------

    static int handleRequest(OutputStream clientOut, ParsedRequest request, String rawRequest) {
        System.out.println("Connecting to " + request.host + ":" + request.port);

        try (Socket remoteSocket = new Socket()) {
            remoteSocket.connect(new InetSocketAddress(request.host, request.port), 10_000);
            remoteSocket.setSoTimeout(10_000);

            OutputStream remoteOut = remoteSocket.getOutputStream();
            InputStream  remoteIn  = remoteSocket.getInputStream();

            String wireRequest = request.toWireFormat();
            System.out.println("Forwarding:\n" + wireRequest);
            remoteOut.write(wireRequest.getBytes());
            remoteOut.flush();

            // Stream response to client and buffer for cache
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[MAX_BYTES];
            int bytesRead;

            while ((bytesRead = remoteIn.read(chunk)) != -1) {
                clientOut.write(chunk, 0, bytesRead);
                clientOut.flush();
                buffer.write(chunk, 0, bytesRead);
            }

            byte[] responseData = buffer.toByteArray();
            System.out.println("Response: " + responseData.length + " bytes");

            if (responseData.length > 0) {
                addCacheElement(responseData, responseData.length, rawRequest);
            }

            System.out.println("Done");
            return 0;

        } catch (IOException e) {
            System.err.println("Error connecting to " + request.host + ": " + e.getMessage());
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Client Handler Thread
    // -------------------------------------------------------------------------

    static class ClientHandler implements Runnable {
        private final Socket clientSocket;

        ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                semaphore.acquire();
                System.out.println("Semaphore value: " + semaphore.availablePermits());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                clientSocket.setSoTimeout(15_000);

                InputStream  in  = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream();

                // Read byte-by-byte until \r\n\r\n — guarantees we get
                // the full header section regardless of TCP segmentation
                StringBuilder sb = new StringBuilder();
                byte[] b = new byte[1];

                while (true) {
                    int n = in.read(b);
                    if (n == -1) break;
                    sb.append((char) b[0]);
                    int len = sb.length();
                    if (len >= 4 && sb.substring(len - 4).equals("\r\n\r\n")) break;
                }

                String requestStr = sb.toString();
                System.out.println("--- Received ---\n" + requestStr + "---");

                if (requestStr.isEmpty()) {
                    System.out.println("Empty request.");
                    return;
                }

                // Check cache
                CacheElement cached = find(requestStr);
                if (cached != null) {
                    System.out.println("Serving " + cached.len + " bytes from cache");
                    int pos = 0;
                    while (pos < cached.len) {
                        int end = Math.min(pos + MAX_BYTES, cached.len);
                        out.write(cached.data, pos, end - pos);
                        pos = end;
                    }
                    out.flush();
                    return;
                }

                // Parse
                ParsedRequest request = ParsedRequest.parse(requestStr);
                if (request == null) {
                    sendErrorMessage(out, 400);
                    return;
                }

                if (!"GET".equalsIgnoreCase(request.method)) {
                    System.out.println("Unsupported method: " + request.method);
                    sendErrorMessage(out, 501);
                    return;
                }

                if (!checkHTTPVersion(request.version)) {
                    sendErrorMessage(out, 505);
                    return;
                }

                if (request.host == null || request.host.isEmpty()) {
                    sendErrorMessage(out, 400);
                    return;
                }

                int result = handleRequest(out, request, requestStr);
                if (result == -1) {
                    sendErrorMessage(out, 500);
                }

            } catch (SocketTimeoutException e) {
                System.err.println("Timeout: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("Handler error: " + e.getMessage());
            } finally {
                try { clientSocket.close(); } catch (IOException ignored) {}
                semaphore.release();
                System.out.println("Semaphore post value: " + semaphore.availablePermits());
            }
        }
    }

}