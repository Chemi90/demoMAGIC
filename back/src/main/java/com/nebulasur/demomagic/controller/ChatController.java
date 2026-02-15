package com.nebulasur.demomagic.controller;

import com.nebulasur.demomagic.dto.ChatRequest;
import com.nebulasur.demomagic.dto.ChatResponse;
import com.nebulasur.demomagic.service.ChatService;
import com.nebulasur.demomagic.service.DemoProxyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final DemoProxyService demoProxyService;

    public ChatController(ChatService chatService, DemoProxyService demoProxyService) {
        this.chatService = chatService;
        this.demoProxyService = demoProxyService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            return demoProxyService.chat(request);
        }
        return chatService.chat(request);
    }
}
