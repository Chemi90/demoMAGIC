package com.nebulasur.demomagic.service;

import com.nebulasur.demomagic.dto.ChatAction;
import com.nebulasur.demomagic.dto.ChatRequest;
import com.nebulasur.demomagic.dto.ChatResponse;
import com.nebulasur.demomagic.model.KbItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final IntentService intentService;
    private final OpenAiClient openAiClient;

    public ChatService(
        KnowledgeBaseService knowledgeBaseService,
        IntentService intentService,
        OpenAiClient openAiClient
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.intentService = intentService;
        this.openAiClient = openAiClient;
    }

    public ChatResponse chat(ChatRequest request) {
        String kb = "B".equalsIgnoreCase(request.getKb()) ? "B" : "A";
        String lang = "en".equalsIgnoreCase(request.getLang()) ? "en" : "es";
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        List<KbItem> items = knowledgeBaseService.listItems(kb);
        IntentService.IntentResult intentResult = intentService.detect(message, items, request.getCart());

        List<KnowledgeBaseService.SearchMatch> matches = knowledgeBaseService.search(kb, message, 3);
        List<String> citations = matches.stream()
            .map(match -> match.item().getId() + " - " + match.item().getTitle())
            .collect(Collectors.toList());

        String context = matches.stream()
            .map(match -> match.item().toContextBlock())
            .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt(lang);
        String userPrompt = buildUserPrompt(lang, kb, message, request.getCart(), intentResult.actions(), context);

        String reply = openAiClient.complete(systemPrompt, userPrompt)
            .orElseGet(() -> fallbackReply(lang, intentResult.actions(), intentResult.item(), matches, request.getCart()));

        ChatResponse response = new ChatResponse();
        response.setReply(reply);
        response.setActions(intentResult.actions());
        response.setItem(intentResult.item() != null ? intentResult.item().toApiMap() : null);
        response.setCitations(citations);
        return response;
    }

    private String buildSystemPrompt(String lang) {
        if ("en".equals(lang)) {
            return "You are an enterprise AI assistant for Nébula Sur. "
                + "Use only provided KB context when stating features, benefits and prices. "
                + "Keep answers concise, professional and practical. "
                + "If the user asks for comparisons, provide a short table-like comparison in plain text. "
                + "Mention that prices are indicative and may vary by scope.";
        }

        return "Eres un asistente IA empresarial de Nébula Sur. "
            + "Usa solo el contexto de la KB para explicar funcionalidades, beneficios y precios. "
            + "Responde de forma profesional, breve y accionable. "
            + "Si el usuario compara soluciones, haz una comparación corta y clara en texto plano. "
            + "Recuerda que los importes son orientativos según alcance.";
    }

    private String buildUserPrompt(
        String lang,
        String kb,
        String message,
        List<Map<String, Object>> cart,
        List<ChatAction> actions,
        String context
    ) {
        String cartSummary = cartSummary(cart);
        String actionText = actions == null || actions.isEmpty()
            ? "none"
            : actions.stream().map(action -> action.getType() + (action.getItemId() != null ? "(" + action.getItemId() + ")" : ""))
            .collect(Collectors.joining(", "));

        if ("en".equals(lang)) {
            return "KB: " + kb + "\n"
                + "Detected actions: " + actionText + "\n"
                + "Cart: " + cartSummary + "\n"
                + "User message: " + message + "\n\n"
                + "Context:\n" + context + "\n\n"
                + "Write the answer in English.";
        }

        return "KB: " + kb + "\n"
            + "Acciones detectadas: " + actionText + "\n"
            + "Carrito: " + cartSummary + "\n"
            + "Mensaje usuario: " + message + "\n\n"
            + "Contexto:\n" + context + "\n\n"
            + "Escribe la respuesta en español.";
    }

    private String cartSummary(List<Map<String, Object>> cart) {
        if (cart == null || cart.isEmpty()) {
            return "[]";
        }

        List<String> lines = new ArrayList<>();
        for (Map<String, Object> item : cart) {
            Object title = item.getOrDefault("title", "item");
            Object id = item.getOrDefault("id", "N/A");
            Object qty = item.getOrDefault("qty", 1);
            Object price = item.getOrDefault("price", "0 €");
            lines.add(title + "(" + id + ") x" + qty + " " + price);
        }
        return String.join("; ", lines);
    }

    private String fallbackReply(
        String lang,
        List<ChatAction> actions,
        KbItem actionItem,
        List<KnowledgeBaseService.SearchMatch> matches,
        List<Map<String, Object>> cart
    ) {
        boolean isEnglish = "en".equals(lang);
        StringBuilder sb = new StringBuilder();

        if (actions != null && !actions.isEmpty()) {
            for (ChatAction action : actions) {
                String type = action.getType() == null ? "" : action.getType().toUpperCase(Locale.ROOT);
                if ("ADD".equals(type) && actionItem != null) {
                    sb.append(isEnglish
                        ? "I added this item to your cart: "
                        : "He añadido este artículo al carrito: ")
                        .append(actionItem.getTitle())
                        .append(" (" + actionItem.getPrice() + "). ");
                }
                if ("REMOVE".equals(type) && actionItem != null) {
                    sb.append(isEnglish
                        ? "I removed this item from your cart: "
                        : "He quitado este artículo del carrito: ")
                        .append(actionItem.getTitle())
                        .append(". ");
                }
                if ("CLEAR".equals(type)) {
                    sb.append(isEnglish ? "Your cart was cleared. " : "Tu carrito fue vaciado. ");
                }
                if ("SHOW".equals(type)) {
                    sb.append(isEnglish
                        ? "Current cart summary: "
                        : "Resumen actual del carrito: ")
                        .append(cartSummary(cart))
                        .append(". ");
                }
            }
        }

        if (matches != null && !matches.isEmpty()) {
            KbItem top = matches.get(0).item();
            sb.append(isEnglish
                ? "Recommended option: "
                : "Opción recomendada: ")
                .append(top.getTitle())
                .append(". ")
                .append(isEnglish ? "Estimated price: " : "Precio estimado: ")
                .append(top.getPrice())
                .append(". ")
                .append(isEnglish ? "Benefit: " : "Beneficio: ")
                .append(top.getBenefits())
                .append(". ")
                .append(isEnglish
                    ? "Indicative amount. It may vary depending on scope and agreements from the meeting."
                    : "Importe orientativo. Puede variar según el alcance y lo acordado en reunión.");
        } else {
            sb.append(isEnglish
                ? "I did not find relevant products in the selected knowledge base."
                : "No encontré productos relevantes en la base seleccionada.");
        }

        return sb.toString().trim();
    }
}
