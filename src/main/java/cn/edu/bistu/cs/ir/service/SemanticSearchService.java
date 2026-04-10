package cn.edu.bistu.cs.ir.service;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SemanticSearchService {

    private final VectorStore vectorStore;

    public SemanticSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void upsertNews(org.apache.lucene.document.Document luceneDoc) {
        String id = nvl(luceneDoc.get("ID"));
        String title = nvl(luceneDoc.get("TITLE"));
        String content = nvl(luceneDoc.get("CONTENT"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("biz_id", id);
        metadata.put("title", title);
        metadata.put("url", nvl(luceneDoc.get("URL")));
        metadata.put("publishTime", nvl(luceneDoc.get("PUBLISH_TIME")));
        metadata.put("source", nvl(luceneDoc.get("SOURCE")));
        metadata.put("keyword", nvl(luceneDoc.get("KEYWORD")));

        String semanticText = (title + "\n" + content).trim();

        org.springframework.ai.document.Document aiDoc =
                new org.springframework.ai.document.Document(id, semanticText, metadata);

        vectorStore.add(List.of(aiDoc));
    }

    public List<SemanticHit> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        List<org.springframework.ai.document.Document> docs = vectorStore.similaritySearch(request);

        return docs.stream()
                .map(d -> new SemanticHit(
                        String.valueOf(d.getMetadata().getOrDefault("biz_id", d.getId())),
                        d.getScore() == null ? 0.0 : d.getScore()))
                .collect(Collectors.toList());
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    public record SemanticHit(String docId, double score) {}
}