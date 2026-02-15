package com.nebulasur.demomagic.model;

import java.util.HashMap;
import java.util.Map;

public class KbItem {
    private String id;
    private String title;
    private String type;
    private String description;
    private String benefits;
    private String useCases;
    private String price;
    private String notes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBenefits() {
        return benefits;
    }

    public void setBenefits(String benefits) {
        this.benefits = benefits;
    }

    public String getUseCases() {
        return useCases;
    }

    public void setUseCases(String useCases) {
        this.useCases = useCases;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String toContextBlock() {
        return "ID: " + id + "\n"
            + "TITLE: " + title + "\n"
            + "TYPE: " + type + "\n"
            + "DESCRIPTION: " + description + "\n"
            + "BENEFITS: " + benefits + "\n"
            + "USE_CASES: " + useCases + "\n"
            + "PRICE: " + price + "\n"
            + "NOTES: " + notes;
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("type", type);
        map.put("description", description);
        map.put("price", price);
        map.put("notes", notes);
        return map;
    }
}
