package com.github.konstantinevashalomidze;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class KHttpDiff {

    private static final String ANSI_RESET = "\u001b[0m";
    private static final String ANSI_COLOR_START = "\u001b[3"; // 3 is for foreground color
    private static final String ANSI_COLOR_END = "m";

    private boolean mono = false;
    private boolean notSame = false;

    public static void main(String[] args) {
        new KHttpDiff().run(args);
    }

    private void run(String[] args) {
        Map<String, String> options = new HashMap<>();
        List<String> urls = parseArguments(args, options);

        if (options.containsKey("help") || urls.size() != 2) {
            printHelp();
            return;
        }

        String method = options.getOrDefault("method", "GET");
        String body = options.getOrDefault("body", "");
        String host = options.getOrDefault("host", "");
        String userAgent = options.getOrDefault("agent", "khttpdiff/0.1");
        boolean insecure = options.containsKey("insecure");
        String diffApp = options.getOrDefault("diffapp", "");
        String singleHeader = options.getOrDefault("header", "");
        String headersFile = options.getOrDefault("headers", "");

        Set<String> excludeHeaders = parseExcludeHeaders(options.getOrDefault("ignore", ""));
        mono = options.containsKey("mono");

        Map<String, String> extraHeaders = new HashMap<>();
        if (!singleHeader.isEmpty()) {
            String[] parts = singleHeader.split(":", 2);
            if (parts.length == 2) {
                extraHeaders.put(parts[0], parts[1]);
            } else {
                System.out.println(colorize(AnsiColor.YELLOW, "Invalid header format: " + singleHeader));
                return;
            }
        }

        if (!headersFile.isEmpty()) {
            extraHeaders.putAll(parseHeadersFromFile(headersFile));
        }

        if (!host.isEmpty()) {
            System.out.println(colorize(AnsiColor.YELLOW, "Host: " + host));
        }


        System.out.println(colorize(AnsiColor.YELLOW, "Comparing ") +
                colorize(AnsiColor.MAGENTA, method) +
                colorize(AnsiColor.YELLOW, " requests:"));
        System.out.println("    " + colorize(AnsiColor.RED, urls.getFirst()));
        System.out.println("    " + colorize(AnsiColor.GREEN, urls.getLast()));
        System.out.println();

        System.out.println(colorize(AnsiColor.YELLOW, "Making requests..."));
        System.out.println();

        CompletableFuture<HttpResponse<String>> future1 = makeHttpRequestAsync(host, userAgent, insecure,
                method, urls.getFirst(), body, extraHeaders);
        CompletableFuture<HttpResponse<String>> future2 = makeHttpRequestAsync(host, userAgent, insecure,
                method, urls.getLast(), body, extraHeaders);


        try {

            HttpResponse<String> result1 = future1.get();
            HttpResponse<String> result2 = future2.get();

            if (result1 == null || result2 == null) {
                return;
            }

            boolean hasSameStatusCodes = compareStatusCodes(result1, result2);
            boolean hasSameHeaders = compareHeaders(result1, result2, excludeHeaders);
            boolean hasSameBodies = compareBodies(result1, result2, urls.getFirst(), urls.getLast(), diffApp);


            if (!notSame && hasSameHeaders && hasSameStatusCodes && hasSameBodies) {
                System.out.println(colorize(AnsiColor.YELLOW, "Requests are the same"));
            } else {
                System.out.println(colorize(AnsiColor.YELLOW, "Requests are different"));
            }

        } catch (Exception e) {
            System.out.println(colorize(AnsiColor.YELLOW, "Error getting results " + e.getMessage()));
        }
    }

    private Set<String> parseExcludeHeaders(String ignore) {
        return Set.of(ignore.split(","));
    }

    private void printHelp() {
        System.out.println("HttpDiff - Compare HTTP responses");
        System.out.println("Usage: httpdiff [options] url1 url2");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -method <method>    HTTP method (default: GET)");
        System.out.println("  -body <data>        Request body for POST/PUT");
        System.out.println("  -host <host>        Host header to send");
        System.out.println("  -agent <ua>         User-Agent header (default: httpdiff/0.1)");
        System.out.println("  -ignore <headers>   Comma-separated headers to ignore");
        System.out.println("  -header <hdr>       Single header (Key: Value)");
        System.out.println("  -headers <file>     File with headers (one per line)");
        System.out.println("  -insecure           Allow insecure SSL connections");
        System.out.println("  -diffapp <app>      Diff tool to use for body differences");
        System.out.println("  -mono               Monochrome output");
        System.out.println("  -help               Show this help");
        System.out.println();
    }

    private HttpClient createHttpClient(boolean insecure) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        if (insecure) {
            try {
                // Trust manager that doesn't validate certificate chains
                TrustManager[] trustAllCerts = new TrustManager[] {
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() {
                                return null;
                            }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {
                            }
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {
                            }
                        }
                };

                // Install the all-trusting trust manager
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());
                builder.sslContext(sslContext);
            } catch (Exception e) {
                System.out.println(colorize(AnsiColor.YELLOW, "Warning: Couldn't setup insecure SSL: " + e.getMessage()));
            }
        }

        return builder.build();
    }

    private CompletableFuture<HttpResponse<String>> makeHttpRequestAsync(
            String host, String userAgent, boolean insecure,
            String method, String url, String body, Map<String, String> headers
            ) {
        return CompletableFuture.supplyAsync(() -> {
            try (HttpClient client = createHttpClient(insecure)) {




                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url));


                if (method.equals("POST") || method.equals("PUT")) {
                    requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
                } else {
                    requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
                }

                // Can be overridden by headers
                if (host != null && !host.isEmpty()) {
                    requestBuilder.header("Host", host);
                }

                if (userAgent != null && !userAgent.isEmpty()) {
                    requestBuilder.header("User-Agent", userAgent);
                }


                for (var entry : headers.entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }


                HttpRequest request = requestBuilder.build();

                return client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

            } catch (Exception e) {
                System.out.println(colorize(AnsiColor.YELLOW, "Something went wrong during request "
                        + url + " possible reason " + e.getMessage()));
                return null;
            }
        });
    }

    private String colorize(AnsiColor color, String text) {
        if (mono) {                       // color code
            return String.format("%s: %s", color.getCode(), text);
        }

        String colorCode = ANSI_COLOR_START + color.getCode() + ANSI_COLOR_END;

        return String.format("%s%s%s", colorCode, text, ANSI_RESET);
    }

    private boolean compareStatusCodes(HttpResponse<String> response1, HttpResponse<String> response2) {
        if (response1.statusCode() != response2.statusCode()) {
            System.out.println(colorize(AnsiColor.YELLOW, "Different status codes:"));
            System.out.println("    " + colorize(AnsiColor.RED, String.valueOf(response1.statusCode())));
            System.out.println("    " + colorize(AnsiColor.GREEN, String.valueOf(response2.statusCode())));
            System.out.println();
            notSame = true;
            return false;
        }

        System.out.println(colorize(AnsiColor.YELLOW, "Status codes identical: ") +
                colorize(AnsiColor.MAGENTA, String.valueOf(response1.statusCode())));
        System.out.println();
        return true;
    }

    private boolean compareHeaders(HttpResponse<String> response1, HttpResponse<String> response2,
                                   Set<String> excludeHeaders) {
        boolean headersSame = true;

        for (String header : response1.headers().map().keySet()) {
            if (!excludeHeaders.contains(header)) { // Don't compare excluded headers
                if (response2.headers().map().containsKey(header)) { // Header exists in both responses
                    // Obtain header values from both responses
                    List<String> values1 = response1.headers().map().get(header);
                    List<String> values2 = response2.headers().map().get(header);

                    if (!values1.equals(values2)) {
                        System.out.println(colorize(AnsiColor.YELLOW, "Different ") +
                                colorize(AnsiColor.CYAN, header) +
                                colorize(AnsiColor.YELLOW, " Header:"));
                        for (int i = 0; i < Integer.max(values1.size(), values2.size()); i++) {
                            if (i < values1.size()) {
                                System.out.println("    " + colorize(AnsiColor.RED, values1.get(i)));
                            }
                            if (i < values2.size()) {
                                System.out.println("    " + colorize(AnsiColor.GREEN, values2.get(i)));
                            }
                            System.out.println();
                        }
                        headersSame = false;
                        notSame = true;
                    }
                }
            }
        }


        findUniqueHeaders(response1, response2, excludeHeaders, AnsiColor.RED);
        findUniqueHeaders(response2, response1, excludeHeaders, AnsiColor.GREEN);

        if (headersSame) {
            System.out.println(colorize(AnsiColor.GREEN, " Headers identical"));
            System.out.println();
        }

        return headersSame;

    }

    private void findUniqueHeaders(HttpResponse<String> response1, HttpResponse<String> response2,
                                   Set<String> excludeHeaders, AnsiColor color) {
        for (String header : response1.headers().map().keySet()) {
            if (!excludeHeaders.contains(header)) {
                if (!response2.headers().map().containsKey(header)) {
                    System.out.println(colorize(AnsiColor.YELLOW, "Header ") +
                            colorize(AnsiColor.GREEN, header) +
                            colorize(AnsiColor.YELLOW, " only in response:"));
                    System.out.println("    " + colorize(color, response1.headers().map().get(header).toString()));
                    System.out.println();
                    notSame = true;
                }
            }
        }
    }



    private boolean compareBodies(HttpResponse<String> response1, HttpResponse<String> response2,
                                  String url1, String url2, String diffApp) {
        if (response1.body().length() != response2.body().length()) {
            System.out.println(colorize(AnsiColor.YELLOW, "Bodies are different (different length)"));
            System.out.println("    " + colorize(AnsiColor.RED, response1.body().length() + " Length"));
            System.out.println("    " + colorize(AnsiColor.GREEN, response2.body().length() + " Length"));
            System.out.println();
            notSame = true;

            handleBodyDiff(response1.body(), response2.body(), url1, url2, diffApp);
            return false;
        }

        if (!response1.body().equals(response2.body())) {
            System.out.println(colorize(AnsiColor.YELLOW, "Bodies are different (same length, different content)"));
            System.out.println();
            notSame = true;

            handleBodyDiff(response1.body(), response2.body(), url1, url2, diffApp);
            return false;
        }

        System.out.println(colorize(AnsiColor.YELLOW, "Bodies are identical"));
        System.out.println();
        return true;
    }


    private void handleBodyDiff(String body1, String body2, String url1, String url2, String diffApp) {
        try {
            Path tempFile1 = Files.createTempFile("khttpdiff", ".tmp");
            Path tempFile2 = Files.createTempFile("khttpdiff", ".tmp");

            // Deletes in case of crash
            tempFile1.toFile().deleteOnExit();
            tempFile2.toFile().deleteOnExit();

            Files.write(tempFile1, body1.getBytes());
            Files.write(tempFile2, body2.getBytes());

            System.out.println(colorize(AnsiColor.YELLOW, "Body differences found:"));
            System.out.println("    " + colorize(AnsiColor.GREEN, "Wrote to " + tempFile1));
            System.out.println("    " + colorize(AnsiColor.GREEN, "Wrote to " + tempFile2));
            System.out.println();

            // if diff app is specified run it
            if (diffApp != null && !diffApp.isEmpty()) {
                System.out.println(colorize(AnsiColor.YELLOW, "Running diff tool:") + colorize(AnsiColor.MAGENTA, diffApp));
                System.out.println();
                Process process = new ProcessBuilder(
                        diffApp,
                        tempFile1.toString(),
                        tempFile2.toString()
                        ).inheritIO() // Show diff output in our console
                        .start();

                int exitCode = process.waitFor();
                System.out.println(colorize(AnsiColor.YELLOW, "Diff tool exited with code: " + exitCode));
                System.out.println();

                // Files are already processed no longer necessary
                Files.delete(tempFile1);
                Files.delete(tempFile2);
            } else {
                System.out.println(colorize(AnsiColor.YELLOW,"Use an external tool to compare: "));
                System.out.println(colorize(AnsiColor.YELLOW, "    diff " + tempFile1 + " " + tempFile2));
                System.out.println(colorize(AnsiColor.YELLOW, "Or specify a diff tool with -diffapp"));
                System.out.println();
            }

        } catch (IOException e) {
            System.out.println(colorize(AnsiColor.YELLOW, "Something went wrong during diff " + e.getMessage()));
            System.out.println();
        } catch (InterruptedException e) {
            System.out.println(colorize(AnsiColor.YELLOW, "Diff process interrupted"));
            System.out.println();
        }
    }



    private List<String> parseArguments(String[] args, Map<String, String> options) {
        List<String> urls = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                String key = args[i].replaceFirst("^-+", ""); // Remove leading dashes
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    options.put(key, args[++i]);
                } else {
                    options.put(key, "true");
                }
            } else {
                urls.add(args[i]);
            }
        }

        return urls;
    }

    private Map<String, String> parseHeadersFromFile(String fileName) {
        Map<String, String> headers = new HashMap<>();

        if (fileName == null || fileName.isEmpty())
            return headers;

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("//"))
                    continue;
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    headers.put(parts[0], parts[1]);
                } else {
                    System.out.println(colorize(AnsiColor.YELLOW, "Warning: Invalid header line " + line));
                }
            }
        } catch (IOException e) {
            System.out.println(colorize(AnsiColor.YELLOW, "Error reading headers file: " + e.getMessage()));
        }


        return headers;
    }



    enum AnsiColor {
        RED(1),
        GREEN(2),
        YELLOW(3),
        BLUE(4),
        MAGENTA(5),
        CYAN(6),
        WHITE(7);

        private final int code;

        AnsiColor(int code) {
            this.code = code;
        }

        public String getCode() {
            return String.valueOf(code);
        }
    }


}
