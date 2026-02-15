package com.nebulasur.demomagic;

import com.nebulasur.demomagic.dto.ChatRequest;
import com.nebulasur.demomagic.dto.ChatResponse;
import com.nebulasur.demomagic.service.ChatService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@SpringBootTest
class ChatbotReplayTest {

    @Autowired
    private ChatService chatService;

    @Test
    void replayQuestionsAndWriteLog() throws Exception {
        String kb = System.getProperty("chatbot.replay.kb", "A").trim().toUpperCase();
        String lang = System.getProperty("chatbot.replay.lang", "es").trim().toLowerCase();
        List<String> questions = loadQuestions();
        Assertions.assertFalse(questions.isEmpty(), "No hay preguntas para el replay.");

        String sessionId = UUID.randomUUID().toString();
        StringBuilder log = new StringBuilder();
        log.append("KB=").append(kb).append(" | LANG=").append(lang).append(" | SESSION=").append(sessionId).append("\n\n");

        for (int i = 0; i < questions.size(); i++) {
            String question = questions.get(i);
            ChatRequest request = new ChatRequest();
            request.setKb(kb);
            request.setLang(lang);
            request.setSessionId(sessionId);
            request.setMessage(question);

            ChatResponse response = chatService.chat(request);
            String answer = response.getReply() == null ? "" : response.getReply().trim();

            Assertions.assertFalse(answer.isBlank(), "Respuesta vacia para pregunta: " + question);

            log.append("[").append(i + 1).append("] Q: ").append(question).append("\n");
            log.append("[").append(i + 1).append("] A: ").append(answer).append("\n\n");
        }

        Path outDir = Paths.get("target", "chatbot-logs");
        Files.createDirectories(outDir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = outDir.resolve("replay-" + kb + "-" + ts + ".log");
        Files.writeString(outFile, log.toString(), StandardCharsets.UTF_8);

        System.out.println("[CHATBOT-REPLAY] Log generado: " + outFile.toAbsolutePath());
    }

    private List<String> loadQuestions() throws Exception {
        String fileArg = System.getProperty("chatbot.replay.file", "").trim();
        List<String> lines;

        if (!fileArg.isBlank()) {
            Path path = Paths.get(fileArg);
            Assertions.assertTrue(Files.exists(path), "No existe el fichero de preguntas: " + path);
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } else {
            ClassPathResource resource = new ClassPathResource("chatbot/questions.txt");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                lines = reader.lines().collect(Collectors.toList());
            }
        }

        return lines.stream()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !line.startsWith("#"))
            .collect(Collectors.toList());
    }
}
