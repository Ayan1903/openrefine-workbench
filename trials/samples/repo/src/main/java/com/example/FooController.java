package com.example;

public class FooController {
    private final BarService barService;

    public FooController(BarService barService) {
        this.barService = barService;
    }

    public String foo() {
        return barService.bar();
    }

    public String summary() {
        return barService.buildSummary();
    }
}
