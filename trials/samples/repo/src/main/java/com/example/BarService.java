package com.example;

public class BarService {
    private final BazRepository bazRepository;
    private final AuditClient auditClient;

    public BarService(BazRepository bazRepository, AuditClient auditClient) {
        this.bazRepository = bazRepository;
        this.auditClient = auditClient;
    }

    public String bar() {
        String message = bazRepository.fetchMessage();
        auditClient.recordAccess();
        return message;
    }

    public String buildSummary() {
        String raw = bazRepository.fetchSummary();
        return FormatterUtil.wrap(raw);
    }
}
