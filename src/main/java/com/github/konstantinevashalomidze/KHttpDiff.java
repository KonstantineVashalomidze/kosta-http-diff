package com.github.konstantinevashalomidze;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class KHttpDiff {

    private static final String ANSI_RESET = "\u001b[0m";
    private static final String ANSI_COLOR_START = "\u001b[3"; // 3 is for foreground color
    private static final String ANSI_COLOR_END = "m";

    private boolean mono = false;

    public static void main(String[] args) {
        System.out.println("khttpdiff starting...");
        new KHttpDiff().run(args);
    }

    private void run(String[] args) {
        Map<String, String> options = new HashMap<>();
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

        if (urls.size() != 2) {
            System.err.println("Error: Exactly two URLs are required");
            System.err.println("Usage: khttpdiff [options] url1 url2");
            System.exit(2);
        }

        System.out.println("Options: " + options);
        System.out.println("Urls: " + urls);

        System.out.println("Making requests...");

        CompletableFuture<String> future1 = makeHttpRequestAsync(urls.get(0));
        CompletableFuture<String> future2 = makeHttpRequestAsync(urls.get(1));


        try {

            String result1 = future1.get();
            String result2 = future2.get();

            boolean isSame = compareResponses(result1, result2);

        } catch (Exception e) {
            System.err.println("Error getting results: " + e.getMessage());
        }


    }

    private CompletableFuture<String> makeHttpRequestAsync(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                return String.format("Status: %d\nBody length: %d", response.statusCode(), response.body().length());

            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });
    }

    private boolean compareResponses(String result1, String result2) {
        boolean isSame = result1.equals(result2);
        if (isSame) {
            System.out.println("Responses are identical");
        } else {
            System.out.println("Responses are different");
            System.out.println("    " + colorize(AnsiColor.RED, result1));
            System.out.println("    " + colorize(AnsiColor.GREEN, result2));
        }

        return isSame;
    }

    private String colorize(AnsiColor color, String text) {
        if (mono) {                     // color code
            return String.format("%s: %s", color.getCode(), text);
        }

        String colorCode = ANSI_COLOR_START + color.getCode() + ANSI_COLOR_END;

        return String.format("%s%s%s", colorCode, text, ANSI_RESET);
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