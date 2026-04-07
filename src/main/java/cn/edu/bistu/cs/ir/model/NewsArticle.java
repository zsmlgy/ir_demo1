package cn.edu.bistu.cs.ir.model;

import lombok.Data;

@Data
public class NewsArticle {
    private String id;
    private String url;
    private String title;
    private String content;
    private String source;
    private String author;
    private String publishTime;
    private String keyword;
}