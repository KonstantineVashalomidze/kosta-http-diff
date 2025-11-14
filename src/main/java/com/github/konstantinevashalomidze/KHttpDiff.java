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
            System.out.println("Response 1: " + future1.get());
            System.out.println("Response 2: " + future2.get());
        } catch (Exception e) {
            System.err.println("Error getting results: " + e.getMessage());
            System.exit(2);
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




}