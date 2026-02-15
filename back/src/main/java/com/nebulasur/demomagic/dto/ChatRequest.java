package com.nebulasur.demomagic.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatRequest {

    private String kb = "A";

    @NotBlank
    private String message;

    private List<Map<String, Object>> cart = new ArrayList<>();

    private String lang = "es";
    private String sessionId;
    private String tenantId;
    private List<ChatMessage> messages = new ArrayList<>();

    public String getKb() {
        return kb;
    }

    public void setKb(String kb) {
        this.kb = kb;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<Map<String, Object>> getCart() {
        return cart;
    }

    public void setCart(List<Map<String, Object>> cart) {
        this.cart = cart;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages == null ? new ArrayList<>() : messages;
    }
}
