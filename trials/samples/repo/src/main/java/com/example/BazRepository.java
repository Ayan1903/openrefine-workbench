package com.example;

public class BazRepository {
    public String fetchMessage() {
        return FormatterUtil.wrap("bar");
    }

    public String fetchSummary() {
        return FormatterUtil.wrap("summary");
    }
}
