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
}
