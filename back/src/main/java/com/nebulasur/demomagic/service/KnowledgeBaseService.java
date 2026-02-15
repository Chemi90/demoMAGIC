package com.nebulasur.demomagic.service;

import com.nebulasur.demomagic.model.KbItem;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private final OpenAiClient openAiClient;

    private final Map<String, List<KbItem>> kbItems = new HashMap<>();
    private final Map<String, Map<String, List<Double>>> kbVectors = new HashMap<>();

    public KnowledgeBaseService(OpenAiClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    @PostConstruct
    public void init() throws IOException {
        kbItems.put("A", loadItems("kb/kbA.txt"));
        kbItems.put("B", loadItems("kb/kbB.txt"));

        if (!openAiClient.isConfigured()) {
            return;
        }

        for (String kb : kbItems.keySet()) {
            Map<String, List<Double>> vectors = new HashMap<>();
            for (KbItem item : kbItems.get(kb)) {
                openAiClient.embed(buildEmbeddingText(item)).ifPresent(vector -> vectors.put(item.getId(), vector));
            }
            kbVectors.put(kb, vectors);
        }
    }

    public List<KbItem> listItems(String kb) {
        return kbItems.getOrDefault(normalizeKb(kb), List.of());
    }

    public List<SearchMatch> search(String kb, String query, int limit) {
        List<KbItem> items = listItems(kb);
        if (items.isEmpty()) {
            return List.of();
        }

        Optional<List<Double>> queryVector = openAiClient.embed(query);
        Map<String, List<Double>> vectors = kbVectors.getOrDefault(normalizeKb(kb), Map.of());

        return items.stream()
            .map(item -> {
                double score = queryVector
                    .flatMap(qv -> Optional.ofNullable(vectors.get(item.getId())).map(iv -> cosineSimilarity(qv, iv)))
                    .orElseGet(() -> lexicalScore(query, buildEmbeddingText(item)));
                return new SearchMatch(item, score);
            })
            .sorted(Comparator.comparingDouble(SearchMatch::score).reversed())
            .limit(limit)
            .toList();
    }

    public KbItem findById(String kb, String id) {
        return listItems(kb).stream()
            .filter(item -> item.getId().equalsIgnoreCase(id))
            .findFirst()
            .orElse(null);
    }

    private String normalizeKb(String kb) {
        return "B".equalsIgnoreCase(kb) ? "B" : "A";
    }

    private List<KbItem> loadItems(String classpathFile) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathFile);
        String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        List<KbItem> items = new ArrayList<>();
        Map<String, String> fields = new HashMap<>();

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("ID:") && !fields.isEmpty()) {
                items.add(toItem(fields));
                fields = new HashMap<>();
            }

            int separator = line.indexOf(':');
            if (separator > 0) {
                String key = line.substring(0, separator).trim().toUpperCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                fields.put(key, value);
            }
        }

        if (!fields.isEmpty()) {
            items.add(toItem(fields));
        }

        return items;
    }

    private KbItem toItem(Map<String, String> fields) {
        KbItem item = new KbItem();
        item.setId(fields.getOrDefault("ID", "N/A"));
        item.setTitle(fields.getOrDefault("TITLE", "Sin título"));
        item.setType(fields.getOrDefault("TYPE", "servicio"));
        item.setDescription(fields.getOrDefault("DESCRIPTION", ""));
        item.setBenefits(fields.getOrDefault("BENEFITS", ""));
        item.setUseCases(fields.getOrDefault("USE_CASES", ""));
        item.setPrice(fields.getOrDefault("PRICE", "0 €"));
        item.setNotes(fields.getOrDefault("NOTES", ""));
        return item;
    }

    private String buildEmbeddingText(KbItem item) {
        return String.join("\n",
            item.getId(),
            item.getTitle(),
            item.getType(),
            item.getDescription(),
            item.getBenefits(),
            item.getUseCases(),
            item.getPrice(),
            item.getNotes());
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return -1;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        if (normA == 0.0 || normB == 0.0) {
            return -1;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double lexicalScore(String query, String document) {
        Set<String> q = tokenize(query);
        Set<String> d = tokenize(document);
        if (q.isEmpty() || d.isEmpty()) {
            return 0.0;
        }

        long overlap = q.stream().filter(d::contains).count();
        return (double) overlap / Math.max(1, q.size());
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9áéíóúñü\\s]", " ")
                .split("\\s+"))
            .filter(token -> token.length() > 2)
            .collect(Collectors.toCollection(HashSet::new));
    }

    public record SearchMatch(KbItem item, double score) {
    }
}
