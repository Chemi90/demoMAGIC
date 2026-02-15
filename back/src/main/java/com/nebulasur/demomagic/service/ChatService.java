package com.nebulasur.demomagic.service;

import com.nebulasur.demomagic.dto.ChatAction;
import com.nebulasur.demomagic.dto.ChatRequest;
import com.nebulasur.demomagic.dto.ChatResponse;
import com.nebulasur.demomagic.model.KbItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final IntentService intentService;
    private final OpenAiClient openAiClient;
    private final double minRelevanceScore;
    private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();

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
        String normalizedMessage = normalizeText(message);
        String sessionId = (request.getSessionId() == null || request.getSessionId().isBlank())
            ? UUID.randomUUID().toString()
            : request.getSessionId().trim();
        String stateKey = kb + "::" + sessionId;

        ConversationState state = sessions.computeIfAbsent(stateKey, key -> new ConversationState(kb, lang));
        if (!state.getLang().equalsIgnoreCase(lang)) {
            state.reset(kb, lang);
        }

        List<KbItem> items = knowledgeBaseService.listItems(kb);
        IntentService.IntentResult intentResult = intentService.detect(message, items, request.getCart());
        Intent intent = detectIntent(kb, normalizedMessage);

        if (intent == Intent.PRIVACY) {
            state.clear();
            return privacyResponse(lang);
        }

        if (intent == Intent.CONTACT_INFO) {
            state.clear();
            return contactInfoResponse(kb, lang, normalizedMessage);
        }

        ChatResponse pendingFlowReply = handlePendingFlow(state, kb, lang, message, normalizedMessage, intent, intentResult);
        if (pendingFlowReply != null) {
            return pendingFlowReply;
        }

        if (intent == Intent.LOCATION) {
            return locationResponse(kb, lang, isLocationOnlyRequest(normalizedMessage) || showsFrustration(normalizedMessage));
        }

        if (intent == Intent.DIRECTIONS) {
            return directionsResponse(kb, lang, normalizedMessage);
        }

        if (intent == Intent.GREETING) {
            return simpleResponse(
                "en".equals(lang)
                    ? "Hi, I am the commercial assistant of " + kbDisplayName(kb) + ". I can help with services, products, appointments and support."
                    : "Hola, soy la asistente comercial de " + kbDisplayName(kb) + ". Puedo ayudarte con servicios, productos, citas y soporte."
            );
        }

        if (intent == Intent.IDENTITY) {
            return companyIdentityResponse(kb, lang);
        }

        if (intent == Intent.CATALOG) {
            return catalogResponse(kb, lang);
        }

        if (intent == Intent.PROPERTY_SEARCH) {
            return propertySearchResponse(state, kb, lang, normalizedMessage);
        }

        if (intent == Intent.APPOINTMENT) {
            state.clear();
            state.setFlow(Flow.CITA_MOTIVO);
            return simpleResponse(
                "en".equals(lang)
                    ? "Perfect. Let us schedule your appointment. What is the reason for the meeting?"
                    : "Perfecto. Vamos a concertar tu cita. Cual es el motivo de la reunion?"
            );
        }

        if (intent == Intent.PERSONAL) {
            return simpleResponse(
                "en".equals(lang)
                    ? "I am a virtual assistant from " + kbDisplayName(kb) + ". I can help with products, services, appointments and orders."
                    : "Soy una asistente virtual de " + kbDisplayName(kb) + ". Puedo ayudarte con productos, servicios, citas y pedidos."
            );
        }

        if (intent == Intent.SMALLTALK) {
            return simpleResponse(
                "en".equals(lang)
                    ? "Quick one: why did the lead cross the funnel? To become a sale. If you want, we continue with your request."
                    : "Uno rapido: por que un lead cruza el embudo? Para convertirse en venta. Si quieres, seguimos con tu consulta."
            );
        }

        if (isAddToCartPendingVehicle(kb, normalizedMessage, intentResult)) {
            state.clear();
            state.setFlow(Flow.CARRITO_DATOS_VEHICULO);
            state.put("cart_item_id", "C-02");
            return simpleResponse(
                "en".equals(lang)
                    ? "Perfect. To add the correct filter, I need vehicle data: brand/model, year, engine, or VIN."
                    : "Perfecto. Para anadir el filtro correcto, necesito datos del vehiculo: marca/modelo, ano, motor o VIN."
            );
        }

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
        response.setCitations(List.of());
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

    private ChatResponse simpleResponse(String reply) {
        ChatResponse response = new ChatResponse();
        response.setReply(reply);
        response.setActions(List.of());
        response.setItem(null);
        response.setCitations(List.of());
        return response;
    }

    private Intent detectIntent(String kb, String normalizedMessage) {
        if (normalizedMessage.isBlank()) {
            return Intent.DEFAULT;
        }
        if (isPrivacyRequest(normalizedMessage)) {
            return Intent.PRIVACY;
        }
        if (asksContactInfo(normalizedMessage)) {
            return Intent.CONTACT_INFO;
        }
        if (isPersonalRequest(normalizedMessage)) {
            return Intent.PERSONAL;
        }
        if (isSmallTalk(normalizedMessage)) {
            return Intent.SMALLTALK;
        }
        if (isGreeting(normalizedMessage)) {
            return Intent.GREETING;
        }
        if (asksDirections(normalizedMessage)) {
            return Intent.DIRECTIONS;
        }
        if (asksOfficeLocation(normalizedMessage)) {
            return Intent.LOCATION;
        }
        if (asksAppointment(normalizedMessage)) {
            return Intent.APPOINTMENT;
        }
        if (asksCompanyOverview(normalizedMessage)) {
            return Intent.IDENTITY;
        }
        if (isPropertySearchIntent(kb, normalizedMessage)) {
            return Intent.PROPERTY_SEARCH;
        }
        if (asksServiceList(normalizedMessage)) {
            return Intent.CATALOG;
        }
        return Intent.DEFAULT;
    }

    private ChatResponse companyIdentityResponse(String kb, String lang) {
        KbItem company = findCompanyProfile(kb, List.of());
        if (company == null) {
            return outOfScopeResponse(lang, kb);
        }

        String notes = company.getNotes() == null ? "" : company.getNotes();
        String address = extractField(notes, "Direccion central:", "Oficina principal:");
        String phone = extractField(notes, "Telefono:");
        String email = extractField(notes, "Email:");

        String reply = "en".equals(lang)
            ? "I am the commercial assistant of " + kbDisplayName(kb) + ".\n"
                + "- What we do: " + company.getDescription()
                + (address.isBlank() ? "" : "\n- Office: " + address)
                + (phone.isBlank() ? "" : "\n- Phone: " + phone)
                + (email.isBlank() ? "" : "\n- Email: " + email)
            : "Soy la asistente comercial de " + kbDisplayName(kb) + ".\n"
                + "- A que nos dedicamos: " + company.getDescription()
                + (address.isBlank() ? "" : "\n- Oficina: " + address)
                + (phone.isBlank() ? "" : "\n- Telefono: " + phone)
                + (email.isBlank() ? "" : "\n- Email: " + email);
        return simpleResponse(reply);
    }

    private ChatResponse locationResponse(String kb, String lang, boolean shortMode) {
        KbItem company = findCompanyProfile(kb, List.of());
        if (company == null) {
            return outOfScopeResponse(lang, kb);
        }
        String notes = company.getNotes() == null ? "" : company.getNotes();
        String address = extractField(notes, "Direccion central:", "Oficina principal:");
        String phone = extractField(notes, "Telefono:");
        String email = extractField(notes, "Email:");
        String schedule = extractField(notes, "Horario:");
        if (address.isBlank()) {
            return outOfScopeResponse(lang, kb);
        }
        if (schedule.isBlank()) {
            schedule = "en".equals(lang) ? "Mon-Fri 09:30 to 19:00" : "L-V de 9:30 a 19:00";
        }

        if (shortMode) {
            return simpleResponse(
                "en".equals(lang)
                    ? "Address: " + address + "\nWould you like schedule or Google Maps location?"
                    : "Direccion: " + address + "\nQuieres tambien el horario o la ubicacion en Google Maps?"
            );
        }

        String reply = "en".equals(lang)
            ? "We are at " + address + ".\n- Schedule: " + schedule
                + (phone.isBlank() ? "" : "\n- Phone: " + phone)
                + (email.isBlank() ? "" : "\n- Email: " + email)
                + "\nWould you like Google Maps location or to schedule an appointment?"
            : "Estamos en " + address + ".\n- Horario: " + schedule
                + (phone.isBlank() ? "" : "\n- Telefono: " + phone)
                + (email.isBlank() ? "" : "\n- Email: " + email)
                + "\nQuieres que te envie la ubicacion en Google Maps o concertar una cita?";
        return simpleResponse(reply);
    }

    private ChatResponse directionsResponse(String kb, String lang, String normalizedMessage) {
        KbItem company = findCompanyProfile(kb, List.of());
        if (company == null) {
            return outOfScopeResponse(lang, kb);
        }
        String address = extractField(company.getNotes() == null ? "" : company.getNotes(), "Direccion central:", "Oficina principal:");
        if (address.isBlank()) {
            return outOfScopeResponse(lang, kb);
        }

        boolean asksParking = containsAny(normalizedMessage, "parking", "aparcamiento");
        boolean asksTransport = containsAny(normalizedMessage, "transporte", "metro", "bus", "autobus", "public transport");

        StringBuilder sb = new StringBuilder();
        if ("en".equals(lang)) {
            sb.append("Address: ").append(address).append(".\n");
            if (asksParking) {
                sb.append("For parking, check live availability near the office before you go.\n");
            }
            if (asksTransport) {
                sb.append("For public transport, use this address in your route app to see current options.\n");
            }
            sb.append("If you want, I can share a map link and help you book a visit.");
        } else {
            sb.append("Direccion: ").append(address).append(".\n");
            if (asksParking) {
                sb.append("Para parking, revisa disponibilidad en tiempo real cerca de la oficina.\n");
            }
            if (asksTransport) {
                sb.append("Para transporte publico, usa esta direccion en tu app de rutas para ver opciones actuales.\n");
            }
            sb.append("Si quieres, te envio ubicacion para mapa y te ayudo a concertar una visita.");
        }
        return simpleResponse(sb.toString().trim());
    }

    private ChatResponse privacyResponse(String lang) {
        return simpleResponse(
            "en".equals(lang)
                ? "Demo privacy note: avoid sharing sensitive personal data here. This chat is for product and service guidance."
                : "Nota de privacidad demo: evita compartir datos personales sensibles aqui. Este chat es para orientacion de productos y servicios."
        );
    }

    private ChatResponse contactInfoResponse(String kb, String lang, String normalizedMessage) {
        if (asksDirections(normalizedMessage)) {
            return directionsResponse(kb, lang, normalizedMessage);
        }

        KbItem company = findCompanyProfile(kb, List.of());
        if (company == null) {
            return outOfScopeResponse(lang, kb);
        }

        String notes = company.getNotes() == null ? "" : company.getNotes();
        String address = extractField(notes, "Direccion central:", "Oficina principal:");
        String phone = extractField(notes, "Telefono:");
        String email = extractField(notes, "Email:");
        String schedule = extractField(notes, "Horario:");

        boolean asksWhatsapp = containsAny(normalizedMessage, "whatsapp", "wsp", "wasap");
        boolean asksHuman = containsAny(normalizedMessage,
            "persona real", "humano", "humana", "agente", "ventas", "soporte", "responsable", "supervisor", "hablar con",
            "real person", "human", "agent", "sales", "support", "manager");

        StringBuilder sb = new StringBuilder();
        if ("en".equals(lang)) {
            sb.append("Contact details for ").append(kbDisplayName(kb)).append(":\n")
                .append(address.isBlank() ? "" : "- Address: " + address + "\n")
                .append(schedule.isBlank() ? "" : "- Schedule: " + schedule + "\n")
                .append(phone.isBlank() ? "" : "- Phone: " + phone + "\n")
                .append(email.isBlank() ? "" : "- Email: " + email + "\n");
            if (asksWhatsapp && !phone.isBlank()) {
                sb.append("- WhatsApp: available on ").append(phone).append(".\n");
            }
            if (asksHuman) {
                sb.append("If you want, ").append(humanContact(kb, lang)).append(" can contact you directly.");
            }
        } else {
            sb.append("Datos de contacto de ").append(kbDisplayName(kb)).append(":\n")
                .append(address.isBlank() ? "" : "- Direccion: " + address + "\n")
                .append(schedule.isBlank() ? "" : "- Horario: " + schedule + "\n")
                .append(phone.isBlank() ? "" : "- Telefono: " + phone + "\n")
                .append(email.isBlank() ? "" : "- Email: " + email + "\n");
            if (asksWhatsapp && !phone.isBlank()) {
                sb.append("- WhatsApp: disponible en ").append(phone).append(".\n");
            }
            if (asksHuman) {
                sb.append("Si quieres, ").append(humanContact(kb, lang)).append(" te contacta directamente.");
            }
        }

        return simpleResponse(sb.toString().trim());
    }

    private ChatResponse catalogResponse(String kb, String lang) {
        List<KbItem> services = listSellableItems(kb, 6);
        if (services.isEmpty()) {
            return outOfScopeResponse(lang, kb);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("en".equals(lang)
            ? "These are the main categories available:\n"
            : "Estas son las principales categorias disponibles:\n");
        for (KbItem service : services) {
            sb.append("- ").append(service.getTitle()).append(" (").append(service.getPrice()).append(")\n");
        }
        if ("C".equalsIgnoreCase(kb)) {
            sb.append("en".equals(lang)
                ? "For parts, share vehicle brand/model, year, engine, or VIN."
                : "Para recambios, dime marca/modelo del vehiculo, ano, motor o VIN.");
        } else if ("A".equalsIgnoreCase(kb)) {
            sb.append("en".equals(lang)
                ? "If you are searching properties, share area, budget and bedrooms."
                : "Si buscas vivienda, dime zona, presupuesto y habitaciones.");
        } else {
            sb.append("en".equals(lang)
                ? "Tell me your objective and I suggest the best starting option."
                : "Si me dices tu objetivo, te recomiendo la mejor opcion de arranque.");
        }
        return simpleResponse(sb.toString().trim());
    }

    private ChatResponse propertySearchResponse(ConversationState state, String kb, String lang, String normalizedMessage) {
        if (!"A".equalsIgnoreCase(kb)) {
            return simpleResponse(
                "en".equals(lang)
                    ? "You are currently speaking with " + kbDisplayName(kb) + ". Switch to Urbania Nexus Inmobiliaria for property search."
                    : "Ahora mismo te atiendo desde " + kbDisplayName(kb) + ". Cambia a Urbania Nexus Inmobiliaria para busqueda de viviendas."
            );
        }

        state.clear();
        state.setFlow(Flow.PROPIEDAD_ZONA);
        return simpleResponse(
            "en".equals(lang)
                ? "Great. To show available properties, tell me area or city first."
                : "Claro. Para mostrarte viviendas disponibles, dime primero zona o ciudad."
        );
    }

    private ChatResponse handlePendingFlow(
        ConversationState state,
        String kb,
        String lang,
        String rawMessage,
        String normalizedMessage,
        Intent intent,
        IntentService.IntentResult intentResult
    ) {
        if (state.getFlow() == Flow.NONE) {
            return null;
        }

        if (wantsToCancelFlow(normalizedMessage)) {
            state.clear();
            return simpleResponse(
                "en".equals(lang)
                    ? "Done. I canceled the active process. How can I help you now?"
                    : "Perfecto. He cancelado el proceso activo. Como quieres que te ayude ahora?"
            );
        }

        if (intent != Intent.DEFAULT && intent != Intent.APPOINTMENT && !hasCartAction(intentResult)) {
            ChatResponse interruption;
            if (intent == Intent.LOCATION) {
                interruption = locationResponse(kb, lang, isLocationOnlyRequest(normalizedMessage) || showsFrustration(normalizedMessage));
            } else if (intent == Intent.DIRECTIONS) {
                interruption = directionsResponse(kb, lang, normalizedMessage);
            } else if (intent == Intent.IDENTITY) {
                interruption = companyIdentityResponse(kb, lang);
            } else if (intent == Intent.PROPERTY_SEARCH) {
                interruption = simpleResponse(
                    "en".equals(lang)
                        ? "Sure. For property search I need area, budget, bedrooms, type and goal."
                        : "Claro. Para busqueda de vivienda necesito zona, presupuesto, habitaciones, tipo y objetivo."
                );
            } else if (intent == Intent.CATALOG) {
                interruption = catalogResponse(kb, lang);
            } else if (intent == Intent.GREETING) {
                interruption = simpleResponse(
                    "en".equals(lang)
                        ? "Hi, I am still with you."
                        : "Hola, sigo aqui contigo."
                );
            } else if (intent == Intent.SMALLTALK) {
                interruption = simpleResponse(
                    "en".equals(lang)
                        ? "Sure. And now, let us continue where we left off."
                        : "Claro. Y ahora continuamos donde lo dejamos."
                );
            } else if (intent == Intent.PERSONAL) {
                interruption = simpleResponse(
                    "en".equals(lang)
                        ? "I am a virtual assistant, and I can continue helping with your request."
                        : "Soy una asistente virtual, y puedo seguir ayudandote con tu solicitud."
                );
            } else if (intent == Intent.PRIVACY) {
                interruption = simpleResponse(
                    "en".equals(lang)
                        ? "I cannot share third-party private order data."
                        : "No puedo compartir datos privados de pedidos de terceros."
                );
            } else {
                interruption = null;
            }
            if (interruption != null) {
                interruption.setReply(interruption.getReply() + "\n\n" + pendingQuestion(state, lang));
                return interruption;
            }
        }

        if (state.getFlow() == Flow.CITA_MOTIVO) {
            if (!isValidReason(rawMessage)) {
                return simpleResponse(
                    "en".equals(lang)
                        ? "Please tell me the reason for the appointment (for example: advisory, quote, follow-up)."
                        : "Indica el motivo de la cita (por ejemplo: asesoria, presupuesto, seguimiento)."
                );
            }
            state.put("cita_motivo", rawMessage);
            state.setFlow(Flow.CITA_FECHA);
            return simpleResponse("en".equals(lang) ? "Great. What date works best for you?" : "Genial. Que fecha te viene mejor?");
        }

        if (state.getFlow() == Flow.CITA_FECHA) {
            if (!looksLikeDate(normalizedMessage)) {
                return simpleResponse(
                    "en".equals(lang)
                        ? "I need a date to continue (for example: tomorrow, Thursday, 15/02)."
                        : "Necesito una fecha para continuar (por ejemplo: manana, jueves, 15/02)."
                );
            }
            state.put("cita_fecha", rawMessage);
            state.setFlow(Flow.CITA_HORA);
            return simpleResponse("en".equals(lang) ? "Perfect. What time do you prefer?" : "Perfecto. Que hora prefieres?");
        }

        if (state.getFlow() == Flow.CITA_HORA) {
            if (!looksLikeTime(normalizedMessage)) {
                return simpleResponse(
                    "en".equals(lang)
                        ? "I need a valid time (for example: 10:30, afternoon, after 17:00)."
                        : "Necesito una hora valida (por ejemplo: 10:30, por la tarde, despues de las 17:00)."
                );
            }
            state.put("cita_hora", rawMessage);
            state.setFlow(Flow.CITA_MODALIDAD);
            return simpleResponse("en".equals(lang) ? "In-person or online?" : "Presencial u online?");
        }

        if (state.getFlow() == Flow.CITA_MODALIDAD) {
            if (!looksLikeMode(normalizedMessage)) {
                return simpleResponse(
                    "en".equals(lang)
                        ? "Please choose one mode: in-person or online."
                        : "Elige una modalidad: presencial u online."
                );
            }
            state.put("cita_modalidad", normalizeMode(normalizedMessage, lang));
            state.setFlow(Flow.CITA_CONTACTO);
            return simpleResponse(
                "en".equals(lang)
                    ? "Last step. What phone or email should we use to confirm?"
                    : "Ultimo paso. A que telefono o email te confirmamos?"
            );
        }

        if (state.getFlow() == Flow.CITA_CONTACTO) {
            if (!looksLikeContact(rawMessage)) {
                return simpleResponse(
                    "en".equals(lang)
                        ? "I need a valid phone or email to confirm the appointment."
                        : "Necesito un telefono o email valido para confirmar la cita."
                );
            }
            state.put("cita_contacto", rawMessage);
            String summary = "en".equals(lang)
                ? "Perfect, your appointment request is ready:\n"
                    + "- Reason: " + state.get("cita_motivo") + "\n"
                    + "- Date: " + state.get("cita_fecha") + "\n"
                    + "- Time: " + state.get("cita_hora") + "\n"
                    + "- Mode: " + state.get("cita_modalidad") + "\n"
                    + "- Contact: " + state.get("cita_contacto") + "\n"
                    + humanContact(kb, lang) + " will contact you shortly."
                : "Perfecto, ya tengo tu solicitud de cita:\n"
                    + "- Motivo: " + state.get("cita_motivo") + "\n"
                    + "- Fecha: " + state.get("cita_fecha") + "\n"
                    + "- Hora: " + state.get("cita_hora") + "\n"
                    + "- Modalidad: " + state.get("cita_modalidad") + "\n"
                    + "- Contacto: " + state.get("cita_contacto") + "\n"
                    + humanContact(kb, lang) + " te contactara en breve.";
            state.clear();
            return simpleResponse(summary);
        }

        if (state.getFlow() == Flow.PROPIEDAD_ZONA) {
            if (!looksLikeZone(normalizedMessage)) {
                return simpleResponse("en".equals(lang) ? "Tell me area or city first." : "Dime primero zona o ciudad.");
            }
            state.put("prop_zona", rawMessage);
            state.setFlow(Flow.PROPIEDAD_PRESUPUESTO);
            return simpleResponse("en".equals(lang) ? "Great. What budget do you have?" : "Perfecto. Que presupuesto manejas?");
        }

        if (state.getFlow() == Flow.PROPIEDAD_PRESUPUESTO) {
            if (!looksLikeBudget(normalizedMessage)) {
                return simpleResponse("en".equals(lang) ? "Please share an approximate budget." : "Indica un presupuesto aproximado.");
            }
            state.put("prop_presupuesto", rawMessage);
            state.setFlow(Flow.PROPIEDAD_HABITACIONES);
            return simpleResponse("en".equals(lang) ? "How many bedrooms do you need?" : "Cuantas habitaciones necesitas?");
        }

        if (state.getFlow() == Flow.PROPIEDAD_HABITACIONES) {
            if (!looksLikeRooms(normalizedMessage)) {
                return simpleResponse("en".equals(lang) ? "How many bedrooms?" : "Cuantas habitaciones?");
            }
            state.put("prop_habitaciones", rawMessage);
            state.setFlow(Flow.PROPIEDAD_TIPO);
            return simpleResponse(
                "en".equals(lang)
                    ? "What type are you looking for? (apartment, house, new build, investment)"
                    : "Que tipo buscas? (piso, chalet, obra nueva, inversion)"
            );
        }

        if (state.getFlow() == Flow.PROPIEDAD_TIPO) {
            if (!looksLikeType(normalizedMessage)) {
                return simpleResponse(
                    "en".equals(lang)
                        ? "Choose type: apartment, house, new build, investment, commercial."
                        : "Elige tipo: piso, chalet, obra nueva, inversion o local."
                );
            }
            state.put("prop_tipo", rawMessage);
            state.setFlow(Flow.PROPIEDAD_OBJETIVO);
            return simpleResponse("en".equals(lang) ? "Is it for living or investment?" : "Es para vivir o inversion?");
        }

        if (state.getFlow() == Flow.PROPIEDAD_OBJETIVO) {
            if (!looksLikeGoal(normalizedMessage)) {
                return simpleResponse("en".equals(lang) ? "Is it for living, renting or investment?" : "Es para vivir, alquilar o inversion?");
            }
            state.put("prop_objetivo", rawMessage);
            state.clear();
            return simpleResponse(
                "en".equals(lang)
                    ? "Perfect. I have your profile and can prepare matching options."
                    : "Perfecto. Ya tengo tu perfil y puedo prepararte opciones."
            );
        }

        if (state.getFlow() == Flow.CARRITO_DATOS_VEHICULO) {
            if (!looksLikeVehicleData(normalizedMessage)) {
                return simpleResponse(
                    "en".equals(lang)
                        ? "I still need vehicle data: brand/model, year, engine, or VIN."
                        : "Necesito datos del vehiculo: marca/modelo, ano, motor o VIN."
                );
            }
            KbItem item = knowledgeBaseService.findById(kb, state.get("cart_item_id"));
            state.clear();
            if (item == null) {
                return outOfScopeResponse(lang, kb);
            }
            ChatResponse response = simpleResponse(
                "en".equals(lang)
                    ? "Perfect, I added " + item.getTitle() + " to your cart."
                    : "Perfecto, he anadido " + item.getTitle() + " al carrito."
            );
            response.setActions(List.of(new ChatAction("ADD", item.getId())));
            response.setItem(item.toApiMap());
            return response;
        }

        return null;
    }

    private boolean hasCartAction(IntentService.IntentResult intentResult) {
        return intentResult.actions() != null && !intentResult.actions().isEmpty();
    }

    private String pendingQuestion(ConversationState state, String lang) {
        return switch (state.getFlow()) {
            case CITA_MOTIVO -> "en".equals(lang) ? "To continue, what is the reason for the appointment?" : "Para continuar, cual es el motivo de la cita?";
            case CITA_FECHA -> "en".equals(lang) ? "To continue, what date works best for you?" : "Para continuar, que fecha te viene bien?";
            case CITA_HORA -> "en".equals(lang) ? "To continue, what time do you prefer?" : "Para continuar, que hora prefieres?";
            case CITA_MODALIDAD -> "en".equals(lang) ? "To continue, choose in-person or online." : "Para continuar, elige presencial u online.";
            case CITA_CONTACTO -> "en".equals(lang) ? "To finish, share phone or email." : "Para terminar, comparte telefono o email.";
            case PROPIEDAD_ZONA -> "en".equals(lang) ? "To continue, tell me area or city." : "Para continuar, dime zona o ciudad.";
            case PROPIEDAD_PRESUPUESTO -> "en".equals(lang) ? "To continue, tell me your budget." : "Para continuar, dime presupuesto.";
            case PROPIEDAD_HABITACIONES -> "en".equals(lang) ? "To continue, how many bedrooms?" : "Para continuar, cuantas habitaciones?";
            case PROPIEDAD_TIPO -> "en".equals(lang) ? "To continue, what type?" : "Para continuar, que tipo?";
            case PROPIEDAD_OBJETIVO -> "en".equals(lang) ? "To continue, is it for living or investment?" : "Para continuar, es para vivir o inversion?";
            case CARRITO_DATOS_VEHICULO -> "en".equals(lang) ? "To continue, share vehicle details." : "Para continuar, comparte datos del vehiculo.";
            default -> "";
        };
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
            "quien eres", "who are you", "about the company", "company info", "what do you do");
    }

    private boolean asksServiceList(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "servicios", "productos", "articulos", "que teneis", "que ofreces", "que ofreceis", "catalogo", "lista",
            "services", "products", "catalog", "list");
    }

    private boolean isPropertySearchIntent(String kb, String normalizedMessage) {
        if (!"A".equalsIgnoreCase(kb)) {
            return false;
        }
        return containsAny(normalizedMessage,
            "viviendas", "vivienda", "pisos", "casas", "chalet", "obra nueva", "locales", "en venta", "comprar vivienda",
            "properties", "apartments", "homes", "for sale");
    }

    private boolean asksInventory(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "pisos", "locales", "disponibles", "stock", "inventario", "referencia", "availability", "inventory", "units");
    }

    private boolean asksContactInfo(String normalizedMessage) {
        if (containsAny(normalizedMessage, "mi telefono", "my phone", "mi email", "my email")) {
            return false;
        }
        return asksOfficeLocation(normalizedMessage)
            || asksDirections(normalizedMessage)
            || containsAny(normalizedMessage,
                "whatsapp", "wsp", "wasap", "contacto", "atencion al cliente",
                "persona real", "humano", "humana", "hablar con", "ventas", "soporte", "supervisor", "responsable",
                "horario", "a que hora", "abris", "abrir", "cerrar", "cerrais", "cerras",
                "fin de semana", "fines de semana", "sabado", "domingo", "atendeis", "abierto", "cerrado",
                "sin cita", "cita previa", "puedo pasar ahora", "pasar ahora", "atenderme hoy",
                "contact", "customer service", "human", "real person", "talk to", "sales", "support", "manager",
                "opening hours", "open", "close", "weekend", "available today");
    }

    private boolean asksOfficeLocation(String normalizedMessage) {
        if (containsAny(normalizedMessage, "mi telefono", "my phone", "mi email", "my email")) {
            return false;
        }
        return containsAny(normalizedMessage,
            "donde estais", "donde estan", "ubicacion", "direccion", "oficina", "horario", "telefono", "mapa", "email", "correo",
            "where are you", "location", "address", "office", "phone", "schedule", "maps", "email");
    }

    private boolean asksDirections(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "como llego", "como llegar", "parking", "aparcamiento", "transporte", "metro", "bus", "google maps", "indicaciones",
            "how to get", "directions", "parking", "public transport", "maps");
    }

    private boolean asksAppointment(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "cita", "concertar", "agendar", "reunion", "appointment", "book", "meeting", "schedule");
    }

    private boolean isPrivacyRequest(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "privacidad", "conversacion privada", "es privada", "rgpd", "gdpr",
            "datos personales", "guardais mis datos", "guardar mis datos", "compartis mis datos",
            "borrar mis datos", "que datos teneis", "se guarda lo que escribo",
            "me esta leyendo una persona", "eres una ia", "eres ia", "ia o humano", "ia o un humano",
            "ai or human", "human or ai", "are you ai", "are you human",
            "privacy", "personal data", "store my data", "share my data", "delete my data")
            || (containsAny(normalizedMessage, "pedido", "pedidos", "order", "orders")
            && containsAny(normalizedMessage, "juan", "perez", "otra persona", "tercero", "another person", "third party"));
    }

    private boolean isPersonalRequest(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "que llevas puesto", "tu edad", "cuantos anos", "donde vives", "eres real", "what are you wearing", "your age");
    }

    private boolean isSmallTalk(String normalizedMessage) {
        return containsAny(normalizedMessage, "chiste", "joke", "cuentame algo");
    }

    private boolean isGreeting(String normalizedMessage) {
        return normalizedMessage.length() <= 20
            && containsAny(normalizedMessage, "hola", "buenas", "hello", "hi", "hey", "buenos dias", "buenas tardes");
    }

    private boolean wantsToCancelFlow(String normalizedMessage) {
        return containsAny(normalizedMessage, "cancelar", "cancel", "detener", "stop", "salir", "exit");
    }

    private boolean isAddToCartPendingVehicle(String kb, String normalizedMessage, IntentService.IntentResult intentResult) {
        if (!"C".equalsIgnoreCase(kb) || intentResult.item() != null) {
            return false;
        }
        return containsAny(normalizedMessage, "anade", "agrega", "add", "carrito", "cart")
            && containsAny(normalizedMessage, "filtro", "filter");
    }

    private boolean isLocationOnlyRequest(String normalizedMessage) {
        return containsAny(normalizedMessage, "solo ubicacion", "solo la ubicacion", "solo direccion", "solamente la ubicacion");
    }

    private boolean showsFrustration(String normalizedMessage) {
        return containsAny(normalizedMessage, "no quiero", "te he dicho", "solamente", "solo eso");
    }

    private boolean isValidReason(String rawMessage) {
        String normalized = normalizeText(rawMessage);
        return normalized.length() >= 3
            && !normalized.endsWith("?")
            && !looksLikeDate(normalized)
            && !looksLikeTime(normalized)
            && !looksLikeContact(rawMessage);
    }

    private boolean looksLikeDate(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "hoy", "manana", "pasado manana", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo",
            "today", "tomorrow", "monday", "tuesday", "wednesday", "thursday", "friday")
            || normalizedMessage.matches(".*\\b\\d{1,2}[/-]\\d{1,2}([/-]\\d{2,4})?\\b.*");
    }

    private boolean looksLikeTime(String normalizedMessage) {
        return containsAny(normalizedMessage, "manana", "tarde", "noche", "morning", "afternoon", "evening")
            || normalizedMessage.matches(".*\\b([01]?\\d|2[0-3])[:h.]?[0-5]?\\d\\b.*")
            || normalizedMessage.matches(".*\\b\\d{1,2}\\s*(am|pm)\\b.*");
    }

    private boolean looksLikeMode(String normalizedMessage) {
        return containsAny(normalizedMessage, "presencial", "online", "virtual", "remoto", "in person");
    }

    private String normalizeMode(String normalizedMessage, String lang) {
        if (containsAny(normalizedMessage, "presencial", "in person")) {
            return "en".equals(lang) ? "in-person" : "presencial";
        }
        return "online";
    }

    private boolean looksLikeContact(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String text = rawMessage.trim();
        if (text.matches(".*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*")) {
            return true;
        }
        String digits = text.replaceAll("[^0-9]", "");
        return digits.length() >= 9;
    }

    private boolean looksLikeZone(String normalizedMessage) {
        return containsAny(normalizedMessage,
            "madrid", "barcelona", "valencia", "sevilla", "malaga", "zaragoza", "centro", "norte", "sur", "zona")
            || normalizedMessage.matches(".*[a-z]{4,}.*");
    }

    private boolean looksLikeBudget(String normalizedMessage) {
        return normalizedMessage.matches(".*\\b\\d{4,}\\b.*")
            || containsAny(normalizedMessage, "eur", "euro", "mil", "k");
    }

    private boolean looksLikeRooms(String normalizedMessage) {
        return normalizedMessage.matches(".*\\b\\d+\\b.*")
            || containsAny(normalizedMessage, "habitacion", "habitaciones", "dormitorio", "bedroom");
    }

    private boolean looksLikeType(String normalizedMessage) {
        return containsAny(normalizedMessage, "piso", "chalet", "casa", "obra nueva", "inversion", "local", "apartment", "house");
    }

    private boolean looksLikeGoal(String normalizedMessage) {
        return containsAny(normalizedMessage, "vivir", "alquilar", "inversion", "invertir", "living", "rent", "investment");
    }

    private boolean looksLikeVehicleData(String normalizedMessage) {
        boolean hasYear = normalizedMessage.matches(".*\\b(19\\d{2}|20\\d{2})\\b.*");
        boolean hasEngine = containsAny(normalizedMessage, "motor", "diesel", "gasolina", "hdi", "tdi", "tsi", "dci", "cv");
        boolean hasVin = normalizedMessage.matches(".*\\b[a-hj-npr-z0-9]{17}\\b.*");
        return hasVin || (hasYear && hasEngine);
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
            return "I can help you with services, products, pricing, appointments and support from " + kbDisplayName(kb) + ".\n"
                + "If you need a tailored answer, "
                + humanContact(kb, lang)
                + " can contact you with a personalized answer.";
        }
        return "Puedo ayudarte con servicios, productos, precios, citas y soporte de " + kbDisplayName(kb) + ".\n"
            + "Si necesitas una respuesta mas personalizada, "
            + humanContact(kb, lang)
            + " te contactara con una respuesta mas personalizada.";
    }

    private enum Intent {
        DEFAULT,
        GREETING,
        IDENTITY,
        LOCATION,
        DIRECTIONS,
        CONTACT_INFO,
        APPOINTMENT,
        PROPERTY_SEARCH,
        CATALOG,
        PRIVACY,
        PERSONAL,
        SMALLTALK
    }

    private enum Flow {
        NONE,
        CITA_MOTIVO,
        CITA_FECHA,
        CITA_HORA,
        CITA_MODALIDAD,
        CITA_CONTACTO,
        PROPIEDAD_ZONA,
        PROPIEDAD_PRESUPUESTO,
        PROPIEDAD_HABITACIONES,
        PROPIEDAD_TIPO,
        PROPIEDAD_OBJETIVO,
        CARRITO_DATOS_VEHICULO
    }

    private static final class ConversationState {
        private String tenant;
        private String lang;
        private Flow flow = Flow.NONE;
        private final Map<String, String> data = new HashMap<>();

        private ConversationState(String tenant, String lang) {
            this.tenant = tenant;
            this.lang = lang;
        }

        private String getTenant() {
            return tenant;
        }

        private String getLang() {
            return lang;
        }

        private Flow getFlow() {
            return flow;
        }

        private void setFlow(Flow flow) {
            this.flow = flow;
        }

        private void put(String key, String value) {
            data.put(key, value == null ? "" : value.trim());
        }

        private String get(String key) {
            return data.getOrDefault(key, "");
        }

        private void clear() {
            flow = Flow.NONE;
            data.clear();
        }

        private void reset(String tenant, String lang) {
            this.tenant = tenant;
            this.lang = lang;
            clear();
        }
    }
}
