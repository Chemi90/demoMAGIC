package com.nebulasur.demomagic.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatResponse {
    private String reply;
    private List<ChatAction> actions = new ArrayList<>();
    private Map<String, Object> item;
    private List<String> citations = new ArrayList<>();

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public List<ChatAction> getActions() {
        return actions;
    }

    public void setActions(List<ChatAction> actions) {
        this.actions = actions;
    }

    public Map<String, Object> getItem() {
        return item;
    }

    public void setItem(Map<String, Object> item) {
        this.item = item;
    }

    public List<String> getCitations() {
        return citations;
    }

    public void setCitations(List<String> citations) {
        this.citations = citations;
    }
}
