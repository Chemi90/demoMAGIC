package com.nebulasur.demomagic.service;

import com.nebulasur.demomagic.dto.ChatMessage;
import com.nebulasur.demomagic.dto.ChatRequest;
import com.nebulasur.demomagic.dto.ChatResponse;
import com.nebulasur.demomagic.model.KbItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DemoProxyService {

    private final OpenAiClient openAiClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final int maxMessages;
    private final long cacheTtlMillis;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DemoProxyService(
        OpenAiClient openAiClient,
        KnowledgeBaseService knowledgeBaseService,
        @Value("${chat.demo.max-messages:8}") int maxMessages,
        @Value("${chat.demo.cache-ttl-seconds:45}") long cacheTtlSeconds
    ) {
        this.openAiClient = openAiClient;
        this.knowledgeBaseService = knowledgeBaseService;
        this.maxMessages = Math.max(1, maxMessages);
        this.cacheTtlMillis = Math.max(1L, cacheTtlSeconds) * 1000L;
    }

    public ChatResponse chat(ChatRequest request) {
        String tenant = normalizeTenant(request.getTenantId(), request.getKb());
        String lang = "en".equalsIgnoreCase(request.getLang()) ? "en" : "es";
        List<ChatMessage> conversation = sanitizeMessages(request.getMessages(), request.getMessage());
        String lastUserMessage = findLastUserMessage(conversation);

        if (lastUserMessage.isBlank()) {
            return simpleResponse(fallbackReply(lang, tenant));
        }

        String cacheKey = tenant + "::" + lang + "::" + normalizeText(lastUserMessage);
        ChatResponse cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        TenantProfile profile = tenantProfile(tenant, lang);
        String systemPrompt = buildSystemPrompt(profile, lang);

        String reply = openAiClient.complete(systemPrompt, conversation)
            .orElseGet(() -> fallbackReply(lang, tenant));

        putCache(cacheKey, reply);
        return simpleResponse(reply);
    }

    private List<ChatMessage> sanitizeMessages(List<ChatMessage> incoming, String fallbackMessage) {
        List<ChatMessage> normalized = new ArrayList<>();

        if (incoming != null) {
            for (ChatMessage message : incoming) {
                if (message == null) {
                    continue;
                }
                String content = message.getContent() == null ? "" : message.getContent().trim();
                if (content.isBlank()) {
                    continue;
                }
                normalized.add(new ChatMessage(normalizeRole(message.getRole()), content));
            }
        }

        if (normalized.isEmpty() && fallbackMessage != null && !fallbackMessage.isBlank()) {
            normalized.add(new ChatMessage("user", fallbackMessage.trim()));
        }

        if (normalized.size() <= maxMessages) {
            return normalized;
        }
        return new ArrayList<>(normalized.subList(normalized.size() - maxMessages, normalized.size()));
    }

    private String findLastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("user".equalsIgnoreCase(message.getRole())) {
                return message.getContent() == null ? "" : message.getContent().trim();
            }
        }
        return "";
    }

    private String normalizeRole(String role) {
        if ("assistant".equalsIgnoreCase(role)) {
            return "assistant";
        }
        if ("system".equalsIgnoreCase(role)) {
            return "system";
        }
        return "user";
    }

    private ChatResponse simpleResponse(String reply) {
        ChatResponse response = new ChatResponse();
        response.setReply(reply);
        response.setActions(List.of());
        response.setItem(null);
        response.setCitations(List.of());
        return response;
    }

    private ChatResponse getCached(String key) {
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis < now) {
            cache.remove(key);
            return null;
        }
        return simpleResponse(entry.reply);
    }

    private void putCache(String key, String reply) {
        cache.put(key, new CacheEntry(reply, System.currentTimeMillis() + cacheTtlMillis));
    }

    private String normalizeTenant(String tenantId, String kb) {
        String raw = (tenantId == null || tenantId.isBlank()) ? kb : tenantId;
        if ("B".equalsIgnoreCase(raw)) {
            return "B";
        }
        if ("C".equalsIgnoreCase(raw)) {
            return "C";
        }
        return "A";
    }

    private String buildSystemPrompt(TenantProfile profile, String lang) {
        if ("en".equals(lang)) {
            return String.join("\n",
                "You are the demo assistant of " + profile.company + ".",
                "Persona: " + profile.agentName + " | Sector: " + profile.sector + ".",
                "Capabilities: " + String.join(", ", profile.capabilities) + ".",
                "Contact facts: address=" + profile.address + ", schedule=" + profile.schedule + ", phone=" + profile.phone + ", email=" + profile.email + ".",
                "Rules:",
                "- Keep answers short, concrete and practical.",
                "- If asked for contact/address/schedule/phone/email, answer directly with facts.",
                "- If asked about privacy, answer neutral and short.",
                "- If off-topic, politely redirect to this company scope.",
                "- Do not suggest plans/packages unless the user explicitly asks for price, services or plans.",
                "- Do not invent data."
            );
        }

        return String.join("\n",
            "Eres la asistente de demo de " + profile.company + ".",
            "Persona: " + profile.agentName + " | Sector: " + profile.sector + ".",
            "Capacidades: " + String.join(", ", profile.capabilities) + ".",
            "Datos fijos: direccion=" + profile.address + ", horario=" + profile.schedule + ", telefono=" + profile.phone + ", email=" + profile.email + ".",
            "Reglas:",
            "- Responde corto, concreto y util.",
            "- Si preguntan contacto/direccion/horario/telefono/email, responde directo con esos datos.",
            "- Si preguntan privacidad, respuesta corta y neutra.",
            "- Si es fuera de tema, redirige al alcance de la empresa.",
            "- No sugieras paquetes/planes salvo que pidan precio, servicios o planes.",
            "- No inventes datos."
        );
    }

    private String fallbackReply(String lang, String tenant) {
        if ("en".equals(lang)) {
            return "I can help with products, services and contact details for " + displayName(tenant) + ".";
        }
        return "Puedo ayudarte con productos, servicios y datos de contacto de " + displayName(tenant) + ".";
    }

    private String displayName(String tenant) {
        if ("B".equalsIgnoreCase(tenant)) {
            return "LeadWave Growth Marketing";
        }
        if ("C".equalsIgnoreCase(tenant)) {
            return "MotoRecambio Atlas";
        }
        return "Urbania Nexus Inmobiliaria";
    }

    private TenantProfile tenantProfile(String tenant, String lang) {
        KbItem company = knowledgeBaseService.listItems(tenant).stream()
            .filter(item -> "empresa".equalsIgnoreCase(item.getType()))
            .findFirst()
            .orElse(null);

        if (company == null) {
            return defaultProfile(tenant, lang);
        }

        String companyName = company.getTitle() == null ? displayName(tenant) : company.getTitle().replace(" - Perfil corporativo", "").trim();
        String notes = company.getNotes() == null ? "" : company.getNotes();
        String address = extractField(notes, "Direccion central:", "Oficina principal:");
        String phone = extractField(notes, "Telefono:");
        String email = extractField(notes, "Email:");
        String schedule = extractField(notes, "Horario:");

        TenantProfile fallback = defaultProfile(tenant, lang);
        return new TenantProfile(
            companyName.isBlank() ? fallback.company : companyName,
            fallback.agentName,
            fallback.sector,
            fallback.capabilities,
            address.isBlank() ? fallback.address : address,
            schedule.isBlank() ? fallback.schedule : schedule,
            phone.isBlank() ? fallback.phone : phone,
            email.isBlank() ? fallback.email : email
        );
    }

    private TenantProfile defaultProfile(String tenant, String lang) {
        if ("B".equalsIgnoreCase(tenant)) {
            return new TenantProfile(
                "LeadWave Growth Marketing",
                "Diego Martin",
                "en".equals(lang) ? "marketing, advertising and sales" : "marketing, publicidad y ventas",
                "en".equals(lang)
                    ? List.of("lead generation", "sales automation", "campaign analytics")
                    : List.of("captacion de leads", "automatizacion comercial", "analitica de campanas"),
                "Avenida Diagonal 487, Barcelona",
                "en".equals(lang) ? "Mon-Fri 08:30 to 19:30" : "L-V de 8:30 a 19:30",
                "+34 931 880 225",
                "hola@leadwavegrowth.demo"
            );
        }

        if ("C".equalsIgnoreCase(tenant)) {
            return new TenantProfile(
                "MotoRecambio Atlas",
                "Marta Velasco",
                "en".equals(lang) ? "vehicle parts warehousing and distribution" : "almacen y distribucion de recambios",
                "en".equals(lang)
                    ? List.of("VIN validation", "multi-brand catalog", "urgent delivery")
                    : List.of("validacion por VIN", "catalogo multimarca", "entrega urgente"),
                "Poligono Industrial La Estrella, Nave 12, Zaragoza",
                "en".equals(lang) ? "Mon-Fri 08:00-19:00" : "L-V 08:00-19:00",
                "+34 976 550 410",
                "ventas@motorecambioatlas.demo"
            );
        }

        return new TenantProfile(
            "Urbania Nexus Inmobiliaria",
            "Laura Serrano",
            "en".equals(lang) ? "real estate and construction" : "inmobiliario y construccion",
            "en".equals(lang)
                ? List.of("property search", "office information", "commercial appointments")
                : List.of("busqueda de viviendas", "informacion de oficina", "citas comerciales"),
            "Calle Orense 18, Madrid (zona AZCA)",
            "en".equals(lang) ? "Mon-Fri 09:30 to 19:00" : "L-V de 9:30 a 19:00",
            "+34 910 240 118",
            "contacto@urbanianexus.demo"
        );
    }

    private String extractField(String text, String... labels) {
        if (text == null || text.isBlank()) {
            return "";
        }
        for (String label : labels) {
            int start = text.indexOf(label);
            if (start < 0) {
                continue;
            }
            String rest = text.substring(start + label.length()).trim();
            int end = rest.indexOf(". ");
            return end < 0 ? rest : rest.substring(0, end).trim();
        }
        return "";
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private record CacheEntry(String reply, long expiresAtMillis) {
    }

    private record TenantProfile(
        String company,
        String agentName,
        String sector,
        List<String> capabilities,
        String address,
        String schedule,
        String phone,
        String email
    ) {
    }
}
