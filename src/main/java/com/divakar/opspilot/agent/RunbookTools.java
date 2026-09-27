package com.divakar.opspilot.agent;

import com.google.adk.tools.Annotations.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Tool used by the Runbook agent: retrieval over the team's runbooks (the "R" in RAG).
 *
 * Version 1 uses simple keyword scoring so it runs with zero extra infrastructure.
 * Planned upgrade: embeddings + a vector store (pgvector) for semantic search.
 */
public final class RunbookTools {

    private static final int TOP_K = 2;
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "are", "was", "were", "has", "have",
            "not", "but", "into", "after", "when", "then", "than", "its", "our", "service");

    /** Runbooks are loaded once and cached; they only change on redeploy. */
    private static volatile List<Runbook> cache;

    record Runbook(String fileName, String title, String content) {
    }

    private RunbookTools() {
    }

    @Schema(name = "searchRunbooks",
            description = "Searches the team's runbooks and returns the most relevant ones for a problem description.")
    public static Map<String, Object> searchRunbooks(
            @Schema(name = "query",
                    description = "Short description of the problem, e.g. 'database connection pool exhausted timeout'")
            String query) {

        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return Map.of("status", "error", "message", "Query is empty.");
        }

        List<Map<String, Object>> results = runbooks().stream()
                .map(rb -> Map.entry(rb, score(rb, terms)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Runbook, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_K)
                .map(e -> Map.<String, Object>of(
                        "fileName", e.getKey().fileName(),
                        "title", e.getKey().title(),
                        "score", e.getValue(),
                        "content", e.getKey().content()))
                .toList();

        if (results.isEmpty()) {
            return Map.of("status", "no_match", "message", "No runbook matched the query.");
        }
        return Map.of("status", "ok", "results", results);
    }

    static List<String> tokenize(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2 && !STOP_WORDS.contains(t))
                .distinct()
                .collect(Collectors.toList());
    }

    /** Title matches count triple; each body occurrence counts once (capped so one word can't dominate). */
    static int score(Runbook rb, List<String> terms) {
        String title = rb.title().toLowerCase(Locale.ROOT);
        String body = rb.content().toLowerCase(Locale.ROOT);
        int total = 0;
        for (String term : terms) {
            if (title.contains(term)) {
                total += 3;
            }
            total += Math.min(5, countOccurrences(body, term));
        }
        return total;
    }

    private static int countOccurrences(String text, String term) {
        int count = 0;
        int idx = text.indexOf(term);
        while (idx >= 0) {
            count++;
            idx = text.indexOf(term, idx + term.length());
        }
        return count;
    }

    static List<Runbook> runbooks() {
        List<Runbook> local = cache;
        if (local == null) {
            synchronized (RunbookTools.class) {
                local = cache;
                if (local == null) {
                    local = loadRunbooks();
                    cache = local;
                }
            }
        }
        return local;
    }

    private static List<Runbook> loadRunbooks() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:runbooks/*.md");
            List<Runbook> list = new ArrayList<>();
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String title = content.lines()
                            .filter(l -> l.startsWith("# "))
                            .findFirst()
                            .map(l -> l.substring(2).trim())
                            .orElse(r.getFilename());
                    list.add(new Runbook(r.getFilename(), title, content));
                }
            }
            return List.copyOf(list);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load runbooks", e);
        }
    }
}
