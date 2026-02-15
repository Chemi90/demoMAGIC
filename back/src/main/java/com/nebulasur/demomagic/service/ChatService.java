package com.nebulasur.demomagic.service;

import com.nebulasur.demomagic.dto.ChatAction;
import com.nebulasur.demomagic.dto.ChatRequest;
import com.nebulasur.demomagic.dto.ChatResponse;
import com.nebulasur.demomagic.model.KbItem;
import org.springframework.beans.factory.annotation.Value;
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
    private final double minRelevanceScore;

    public ChatService(
        KnowledgeBaseService knowledgeBaseService,
        IntentService intentService,
        OpenAiClient openAiClient,
        @Value("${chat.relevance.min-score:0.12}") double minRelevanceScore
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.intentService = intentService;
        this.openAiClient = openAiClient;
        this.minRelevanceScore = Math.max(0.0, Math.min(1.0, minRelevanceScore));
    }

    public ChatResponse chat(ChatRequest request) {
        String kb = normalizeKb(request.getKb());
        String lang = "en".equalsIgnoreCase(request.getLang()) ? "en" : "es";
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        List<KbItem> items = knowledgeBaseService.listItems(kb);
        IntentService.IntentResult intentResult = intentService.detect(message, items, request.getCart());

        List<KnowledgeBaseService.SearchMatch> matches = knowledgeBaseService.search(kb, message, 5);
        List<KnowledgeBaseService.SearchMatch> filteredMatches = matches.stream()
            .filter(match -> match.score() >= minRelevanceScore)
            .toList();

        List<KnowledgeBaseService.SearchMatch> resolvedMatches = filteredMatches;
        if (resolvedMatches.isEmpty() && intentResult.item() != null) {
            resolvedMatches = List.of(new KnowledgeBaseService.SearchMatch(intentResult.item(), 1.0));
        }

        if (resolvedMatches.isEmpty() && (intentResult.actions() == null || intentResult.actions().isEmpty())) {
            if (isRecommendationRequest(message)) {
                resolvedMatches = defaultRecommendations(kb);
            } else {
                return outOfScopeResponse(lang, kb);
            }
        }

        final List<KnowledgeBaseService.SearchMatch> relevantMatches = resolvedMatches;

        List<String> citations = relevantMatches.stream()
            .map(match -> match.item().getId() + " - " + match.item().getTitle())
            .collect(Collectors.toList());

        String context = relevantMatches.stream()
            .map(match -> match.item().toContextBlock())
            .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt(lang);
        String userPrompt = buildUserPrompt(lang, kb, message, request.getCart(), intentResult.actions(), context);

        String reply = openAiClient.complete(systemPrompt, userPrompt)
            .orElseGet(() -> fallbackReply(lang, kb, message, intentResult.actions(), intentResult.item(), relevantMatches, request.getCart()));

        ChatResponse response = new ChatResponse();
        response.setReply(reply);
        response.setActions(intentResult.actions());
        response.setItem(intentResult.item() != null ? intentResult.item().toApiMap() : null);
        response.setCitations(citations);
        return response;
    }

    private String normalizeKb(String kb) {
        if ("B".equalsIgnoreCase(kb)) {
            return "B";
        }
        if ("C".equalsIgnoreCase(kb)) {
            return "C";
        }
        return "A";
    }

    private String buildSystemPrompt(String lang) {
        if ("en".equals(lang)) {
            return "You are a human-like sales advisor for the selected company. "
                + "Use only provided KB context from TXT files when stating facts, services, prices and timelines. "
                + "Hard rule: do not use external knowledge, assumptions or invented details. "
                + "Write naturally in first person, concise and friendly, never robotic. "
                + "Use readable formatting with short blocks and simple bullet points when useful. "
                + "Never print labels like References or citations in the answer. "
                + "If the user asks for unavailable data, say it clearly and offer human follow-up from the assigned specialist.";
        }

        return "Eres una asesora comercial humana de la empresa seleccionada. "
            + "Usa solo el contexto de KB basado en archivos TXT para datos, servicios, precios y plazos. "
            + "Regla obligatoria: no uses conocimiento externo, supuestos ni datos inventados. "
            + "Responde en primera persona, de forma cercana, profesional y nada robotica. "
            + "Usa formato legible con bloques cortos y vietas simples cuando ayuden. "
            + "Nunca muestres etiquetas de referencias o citas en la respuesta. "
            + "Si el usuario pide algo no disponible, dilo con claridad y ofrece seguimiento humano del especialista asignado.";
    }

    private String buildUserPrompt(
        String lang,
        String kb,
        String message,
        List<Map<String, Object>> cart,
        List<ChatAction> actions,
        String context
    ) {
        String kbName = kbDisplayName(kb);
        String cartSummary = cartSummary(cart);
        String actionText = actions == null || actions.isEmpty()
            ? "none"
            : actions.stream().map(action -> action.getType() + (action.getItemId() != null ? "(" + action.getItemId() + ")" : ""))
            .collect(Collectors.joining(", "));

        if ("en".equals(lang)) {
            return "Selected company: " + kbName + " (" + kb + ")\n"
                + "Assigned specialist for handoff: " + humanContact(kb, "en") + "\n"
                + "Detected actions: " + actionText + "\n"
                + "Cart: " + cartSummary + "\n"
                + "User message: " + message + "\n\n"
                + "Context:\n" + context + "\n\n"
                + "Write in English and keep a human conversational style.";
        }

        return "Empresa seleccionada: " + kbName + " (" + kb + ")\n"
            + "Especialista asignado para escalado: " + humanContact(kb, "es") + "\n"
            + "Acciones detectadas: " + actionText + "\n"
            + "Carrito: " + cartSummary + "\n"
            + "Mensaje usuario: " + message + "\n\n"
            + "Contexto:\n" + context + "\n\n"
            + "Escribe en espanol con estilo humano y cercano.";
    }

    private String fallbackReply(
        String lang,
        String kb,
        String message,
        List<ChatAction> actions,
        KbItem actionItem,
        List<KnowledgeBaseService.SearchMatch> matches,
        List<Map<String, Object>> cart
    ) {
        boolean isEnglish = "en".equals(lang);
        String normalized = normalizeText(message);
        StringBuilder sb = new StringBuilder();

        if (actions != null && !actions.isEmpty()) {
            for (ChatAction action : actions) {
                String type = action.getType() == null ? "" : action.getType().toUpperCase(Locale.ROOT);
                if ("ADD".equals(type) && actionItem != null) {
                    sb.append(isEnglish
                        ? "Perfect, I added this to your cart: "
                        : "Perfecto, ya lo anadi al carrito: ")
                        .append(actionItem.getTitle())
                        .append(" (")
                        .append(actionItem.getPrice())
                        .append(").\n");
                }
                if ("REMOVE".equals(type) && actionItem != null) {
                    sb.append(isEnglish
                        ? "Done, I removed it from your cart: "
                        : "Listo, lo quite del carrito: ")
                        .append(actionItem.getTitle())
                        .append(".\n");
                }
                if ("CLEAR".equals(type)) {
                    sb.append(isEnglish ? "I cleared your cart.\n" : "He vaciado tu carrito.\n");
                }
                if ("SHOW".equals(type)) {
                    sb.append(isEnglish ? "Current cart summary:\n" : "Resumen actual del carrito:\n")
                        .append(cartSummary(cart))
                        .append("\n");
                }
            }
        }

        if (matches == null || matches.isEmpty()) {
            sb.append(outOfScopeReply(lang, kb));
            return sb.toString().trim();
        }

        if (asksInventory(normalized) && !"C".equalsIgnoreCase(kb)) {
            sb.append(isEnglish
                ? "Right now I do not have a live inventory of properties or units in this knowledge base.\n"
                : "Ahora mismo no tengo inventario en vivo de pisos o locales dentro de esta base.\n")
                .append(isEnglish
                    ? "If you want, "
                    : "Si te parece, ")
                .append(humanContact(kb, lang))
                .append(isEnglish
                    ? " will contact you with a personalized availability report."
                    : " te contactara con disponibilidad personalizada.");
            return sb.toString().trim();
        }

        if (asksCompanyOverview(normalized)) {
            KbItem company = findCompanyProfile(kb, matches);
            if (company == null) {
                sb.append(outOfScopeReply(lang, kb));
                return sb.toString().trim();
            }
            sb.append(isEnglish
                ? "Of course. Here is a quick summary of "
                : "Claro. Te resumo rapidamente ")
                .append(kbDisplayName(kb))
                .append(":\n")
                .append("- ")
                .append(isEnglish ? "What they do: " : "A que se dedica: ")
                .append(company.getDescription())
                .append("\n")
                .append("- ")
                .append(isEnglish ? "Main value: " : "Valor principal: ")
                .append(company.getBenefits())
                .append("\n")
                .append("- ")
                .append(isEnglish ? "Contact details: " : "Contacto: ")
                .append(company.getNotes());
            return sb.toString().trim();
        }

        if (asksServiceList(normalized)) {
            List<KbItem> services = listSellableItems(kb, 6);
            sb.append(isEnglish ? "Great, these are the main services/products available:\n" : "Genial, estos son los principales servicios/productos disponibles:\n");
            for (KbItem service : services) {
                sb.append("- ")
                    .append(service.getTitle())
                    .append(" (")
                    .append(service.getPrice())
                    .append(")\n");
            }
            sb.append(isEnglish
                ? "Tell me your goal and I will suggest the best starting package."
                : "Si me dices tu objetivo, te recomiendo el paquete de arranque mas adecuado.");
            return sb.toString().trim();
        }

        if (isRecommendationRequest(message)) {
            KbItem plan = findBestPlan(kb, matches);
            sb.append(isEnglish ? "Good idea. I suggest starting with:\n" : "Buena idea. Yo empezaria por:\n")
                .append("- ")
                .append(plan.getTitle())
                .append(" (")
                .append(plan.getPrice())
                .append(")\n")
                .append(isEnglish ? "Why this option: " : "Por que esta opcion: ")
                .append(plan.getBenefits())
                .append("\n")
                .append(isEnglish
                    ? "If you want, "
                    : "Si quieres, ")
                .append(humanContact(kb, lang))
                .append(isEnglish
                    ? " can contact you to define a personalized rollout plan."
                    : " te contacta para definir un plan personalizado de implantacion.");
            return sb.toString().trim();
        }

        KbItem top = matches.get(0).item();
        sb.append(isEnglish
            ? "Based on what you asked, this is the best fit right now:\n"
            : "Por lo que me comentas, esta es la opcion mas ajustada ahora:\n")
            .append("- ")
            .append(top.getTitle())
            .append(" (")
            .append(top.getPrice())
            .append(")\n")
            .append(isEnglish ? "What it includes: " : "Que incluye: ")
            .append(top.getDescription())
            .append("\n")
            .append(isEnglish ? "Main benefit: " : "Beneficio principal: ")
            .append(top.getBenefits())
            .append("\n")
            .append(isEnglish
                ? "If you need a more tailored answer, "
                : "Si necesitas una respuesta mas personalizada, ")
            .append(humanContact(kb, lang))
            .append(isEnglish
                ? " can contact you directly."
                : " puede contactarte directamente.");

        return sb.toString().trim();
    }

    private List<KnowledgeBaseService.SearchMatch> defaultRecommendations(String kb) {
        return knowledgeBaseService.listItems(kb).stream()
            .filter(item -> !"empresa".equalsIgnoreCase(item.getType()))
            .limit(3)
            .map(item -> new KnowledgeBaseService.SearchMatch(item, 0.20))
            .toList();
    }

    private List<KbItem> listSellableItems(String kb, int limit) {
        return knowledgeBaseService.listItems(kb).stream()
            .filter(item -> {
                String type = item.getType() == null ? "" : item.getType().toLowerCase(Locale.ROOT);
                return !"empresa".equals(type) && !"faq".equals(type) && !"caso".equals(type);
            })
            .limit(limit)
            .toList();
    }

    private KbItem findCompanyProfile(String kb, List<KnowledgeBaseService.SearchMatch> matches) {
        for (KnowledgeBaseService.SearchMatch match : matches) {
            if ("empresa".equalsIgnoreCase(match.item().getType())) {
                return match.item();
            }
        }

        return knowledgeBaseService.listItems(kb).stream()
            .filter(item -> "empresa".equalsIgnoreCase(item.getType()))
            .findFirst()
            .orElse(null);
    }

    private KbItem findBestPlan(String kb, List<KnowledgeBaseService.SearchMatch> matches) {
        for (KnowledgeBaseService.SearchMatch match : matches) {
            String type = match.item().getType();
            if ("plan".equalsIgnoreCase(type) || "servicio".equalsIgnoreCase(type) || "app".equalsIgnoreCase(type) || "producto".equalsIgnoreCase(type)) {
                return match.item();
            }
        }

        return knowledgeBaseService.listItems(kb).stream()
            .filter(item -> {
                String type = item.getType() == null ? "" : item.getType().toLowerCase(Locale.ROOT);
                return "plan".equals(type) || "servicio".equals(type) || "app".equals(type) || "producto".equals(type);
            })
            .findFirst()
            .orElse(knowledgeBaseService.listItems(kb).isEmpty() ? null : knowledgeBaseService.listItems(kb).get(0));
    }

    private boolean isRecommendationRequest(String message) {
        String text = normalizeText(message);
        return containsAny(text,
            "propon", "recomienda", "que me recomiendas", "que me propones", "que opcion",
            "suggest", "recommend", "proposal", "best option", "start with");
    }

    private boolean asksCompanyOverview(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "de que va", "a que se dedica", "quienes sois", "quienes son", "informacion de la empresa",
            "empresa", "direccion", "contacto", "telefono", "email", "about the company", "company info");
    }

    private boolean asksServiceList(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "servicios", "productos", "articulos", "que teneis", "que ofreces", "que ofreceis", "catalogo", "lista",
            "services", "products", "catalog", "list");
    }

    private boolean asksInventory(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "pisos", "locales", "disponibles", "stock", "inventario", "referencia", "availability", "inventory", "units");
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(normalizeText(term))) {
                return true;
            }
        }
        return false;
    }

    private String kbDisplayName(String kb) {
        if ("B".equalsIgnoreCase(kb)) {
            return "LeadWave Growth Marketing";
        }
        if ("C".equalsIgnoreCase(kb)) {
            return "MotoRecambio Atlas";
        }
        return "Urbania Nexus Inmobiliaria";
    }

    private String humanContact(String kb, String lang) {
        boolean isEnglish = "en".equals(lang);
        if ("B".equalsIgnoreCase(kb)) {
            return isEnglish ? "Diego Martin (Senior Growth Consultant)" : "Diego Martin (Consultor Growth Senior)";
        }
        if ("C".equalsIgnoreCase(kb)) {
            return isEnglish ? "Marta Velasco (Parts Operations Lead)" : "Marta Velasco (Responsable de Operaciones de Recambios)";
        }
        return isEnglish ? "Laura Serrano (Senior Real Estate Advisor)" : "Laura Serrano (Asesora Inmobiliaria Senior)";
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
            Object price = item.getOrDefault("price", "0 EUR");
            lines.add(title + "(" + id + ") x" + qty + " " + price);
        }
        return String.join("; ", lines);
    }

    private ChatResponse outOfScopeResponse(String lang, String kb) {
        ChatResponse outOfScope = new ChatResponse();
        outOfScope.setReply(outOfScopeReply(lang, kb));
        outOfScope.setActions(List.of());
        outOfScope.setItem(null);
        outOfScope.setCitations(List.of());
        return outOfScope;
    }

    private String outOfScopeReply(String lang, String kb) {
        if ("en".equals(lang)) {
            return "I can only answer with information available in the selected TXT knowledge base.\n"
                + "If you need details outside this scope, "
                + humanContact(kb, lang)
                + " can contact you with a personalized answer.";
        }
        return "Solo puedo responder con informacion incluida en la base TXT seleccionada.\n"
            + "Si necesitas datos fuera de ese alcance, "
            + humanContact(kb, lang)
            + " te contactara con una respuesta mas personalizada.";
    }
}
