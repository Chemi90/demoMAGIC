package com.nebulasur.demomagic.dto;

public class ChatAction {
    private String type;
    private String itemId;

    public ChatAction() {
    }

    public ChatAction(String type, String itemId) {
        this.type = type;
        this.itemId = itemId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }
}
