package com.github.konstantinevashalomidze;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

        if (!options.containsKey("method")) {
            System.err.println(colorize(AnsiColor.RED, "Error: '-method' is required"));
            printHelp();
            return;
        }

        String method = options.get("method");
        Set<String> excludeHeaders = parseExcludeHeaders(options.getOrDefault("ignore", ""));
        mono = options.containsKey("mono");

        System.out.println(colorize(AnsiColor.YELLOW, "Comparing ") +
                colorize(AnsiColor.MAGENTA, method) +
                colorize(AnsiColor.YELLOW, " requests:"));
        System.out.println("    " + colorize(AnsiColor.RED, urls.getFirst()));
        System.out.println("    " + colorize(AnsiColor.GREEN, urls.getLast()));
        System.out.println();

        System.out.println(colorize(AnsiColor.YELLOW, "Making requests..."));
        System.out.println();

        CompletableFuture<HttpResponse<String>> future1 = makeHttpRequestAsync(method, urls.get(0), body, headers);
        CompletableFuture<HttpResponse<String>> future2 = makeHttpRequestAsync(method, urls.get(1), body, headers);


        try {

            HttpResponse<String> result1 = future1.get();
            HttpResponse<String> result2 = future2.get();

            if (result1 == null || result2 == null) {
                return;
            }

            boolean hasSameStatusCodes = compareStatusCodes(result1, result2);
            boolean hasSameHeaders = compareHeaders(result1, result2, excludeHeaders);
            boolean hasSameBodies = compareBodies(result1, result2);

        } catch (Exception e) {
            System.out.println(colorize(AnsiColor.YELLOW, "Error getting results " + e.getMessage()));
        }
    }

    private Set<String> parseExcludeHeaders(String ignore) {
        return Set.of(ignore.split(","));
    }

    private void printHelp() {
        System.out.println(colorize(AnsiColor.YELLOW, "khttpdiff - Compare HTTP requests"));
        System.out.println(colorize(AnsiColor.YELLOW, "Usage: khttpdiff [options] url1 url2"));
        System.out.println();
        System.out.println(colorize(AnsiColor.YELLOW, "Options:"));
        System.out.println(colorize(AnsiColor.YELLOW, "    -method <method>       *HTTP method to use (GET, POST, PUT, DELETE)"));
        System.out.println(colorize(AnsiColor.YELLOW, "    -ignore <header>        Comma-separated list of headers to ignore"));
        System.out.println(colorize(AnsiColor.YELLOW, "    -mono                   Monochrome output"));
        System.out.println(colorize(AnsiColor.YELLOW, "    -help                   Show this help message"));

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
            System.out.println("Different status codes:");
            System.out.println("    " + colorize(AnsiColor.RED, String.valueOf(response1.statusCode())));
            System.out.println("    " + colorize(AnsiColor.GREEN, String.valueOf(response2.statusCode())));
            notSame = true;
            return false;
        }

        System.out.println(colorize(AnsiColor.YELLOW, "Status codes identical: ") +
                colorize(AnsiColor.MAGENTA, String.valueOf(response1.statusCode())));
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
                    System.out.println("Header " + colorize(AnsiColor.GREEN, header) + " only in response:");
                    System.out.println("    " + colorize(color, response1.headers().map().get(header).toString()));
                    System.out.println();
                    notSame = true;
                }
            }
        }
    }



    private boolean compareBodies(HttpResponse<String> response1, HttpResponse<String> response2) {
        if (response1.body().length() != response2.body().length()) {
            System.out.println(colorize(AnsiColor.YELLOW, "Bodies are different (different length)"));
            System.out.println("    " + colorize(AnsiColor.RED, response1.body().length() + " Length"));
            System.out.println("    " + colorize(AnsiColor.GREEN, response2.body().length() + " Length"));
            notSame = true;
            return false;
        }

        if (!response1.body().equals(response2.body())) {
            System.out.println(colorize(AnsiColor.YELLOW, "Bodies are different (same length, different content)"));
            notSame = true;
            return false;
        }

        System.out.println(colorize(AnsiColor.YELLOW, "Bodies identical"));
        return true;
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
