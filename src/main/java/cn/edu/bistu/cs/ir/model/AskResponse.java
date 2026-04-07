package cn.edu.bistu.cs.ir.model;

import java.util.List;

public record AskResponse(
        String question,
        String answer,
        List<Source> sources
) {
    public record Source(String title, String url, String publishTime) {}
}