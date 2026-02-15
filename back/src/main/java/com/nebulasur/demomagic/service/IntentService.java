package com.nebulasur.demomagic.service;

import com.nebulasur.demomagic.dto.ChatAction;
import com.nebulasur.demomagic.model.KbItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class IntentService {

    public IntentResult detect(String message, List<KbItem> kbItems, List<Map<String, Object>> cart) {
        String normalized = normalize(message);
        List<ChatAction> actions = new ArrayList<>();
        KbItem matchedItem = null;

        boolean wantsAdd = containsAny(normalized,
            "añade", "agrega", "agregar", "suma", "incluye", "mete", "add", "include", "put in cart", "add to cart");

        boolean wantsRemove = containsAny(normalized,
            "quita", "elimina", "saca", "borra", "remove", "delete", "drop");

        boolean wantsClear = containsAny(normalized,
            "vaciar carrito", "vacía carrito", "limpia carrito", "clear cart", "empty cart");

        boolean wantsShow = containsAny(normalized,
            "ver carrito", "mostrar carrito", "muéstrame carrito", "show cart", "view cart", "total carrito", "cart total");

        if (wantsAdd || wantsRemove) {
            matchedItem = bestItemMatch(normalized, kbItems);
            if (matchedItem == null) {
                matchedItem = matchFromCart(normalized, cart, kbItems);
            }
        }

        if (wantsAdd && matchedItem != null) {
            actions.add(new ChatAction("ADD", matchedItem.getId()));
        }

        if (wantsRemove && matchedItem != null) {
            actions.add(new ChatAction("REMOVE", matchedItem.getId()));
        }

        if (wantsClear) {
            actions.add(new ChatAction("CLEAR", null));
        }

        if (wantsShow) {
            actions.add(new ChatAction("SHOW", null));
        }

        return new IntentResult(actions, matchedItem);
    }

    private KbItem bestItemMatch(String message, List<KbItem> items) {
        KbItem winner = null;
        double winnerScore = 0.0;

        for (KbItem item : items) {
            double score = scoreItemMatch(message, item);
            if (score > winnerScore) {
                winnerScore = score;
                winner = item;
            }
        }

        return winnerScore >= 0.18 ? winner : null;
    }

    private KbItem matchFromCart(String message, List<Map<String, Object>> cart, List<KbItem> items) {
        if (cart == null || cart.isEmpty()) {
            return null;
        }

        for (Map<String, Object> entry : cart) {
            Object idValue = entry.get("id");
            Object titleValue = entry.get("title");
            if (idValue != null && message.contains(normalize(String.valueOf(idValue)))) {
                return findById(items, String.valueOf(idValue));
            }
            if (titleValue != null && message.contains(normalize(String.valueOf(titleValue)))) {
                return findByTitle(items, String.valueOf(titleValue));
            }
        }
        return null;
    }

    private KbItem findById(List<KbItem> items, String id) {
        for (KbItem item : items) {
            if (item.getId().equalsIgnoreCase(id)) {
                return item;
            }
        }
        return null;
    }

    private KbItem findByTitle(List<KbItem> items, String title) {
        for (KbItem item : items) {
            if (item.getTitle().equalsIgnoreCase(title)) {
                return item;
            }
        }
        return null;
    }

    private double scoreItemMatch(String message, KbItem item) {
        String id = normalize(item.getId());
        String title = normalize(item.getTitle());
        String type = normalize(item.getType());

        if (message.contains(id)) {
            return 1.0;
        }
        if (message.contains(title)) {
            return 0.9;
        }

        Set<String> messageTokens = tokenize(message);
        Set<String> itemTokens = tokenize(title + " " + type);
        if (messageTokens.isEmpty() || itemTokens.isEmpty()) {
            return 0.0;
        }

        long overlap = messageTokens.stream().filter(itemTokens::contains).count();
        return (double) overlap / itemTokens.size();
    }

    private Set<String> tokenize(String input) {
        return new HashSet<>(Arrays.stream(normalize(input).split("\\s+"))
            .filter(token -> token.length() > 2)
            .toList());
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9áéíóúñü\\s]", " ").trim();
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(normalize(term))) {
                return true;
            }
        }
        return false;
    }

    public record IntentResult(List<ChatAction> actions, KbItem item) {
    }
}
