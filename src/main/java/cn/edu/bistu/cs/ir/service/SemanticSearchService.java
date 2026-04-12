package cn.edu.bistu.cs.ir.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private static final int DOC_EMBED_LIMIT = 1000;
    private static final int QUERY_EMBED_LIMIT = 200;

    private final VectorStore vectorStore;

    public SemanticSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }
    //业务 ID 稳定地映射成一个标准 UUID 字符串
    private String toStableUuid(String bizId) {
        return UUID.nameUUIDFromBytes(bizId.getBytes(StandardCharsets.UTF_8)).toString();
    }
    public void upsertNews(org.apache.lucene.document.Document luceneDoc) {
        String bizId = safe(luceneDoc.get("ID"));
        if (bizId.isBlank()) {
            return;
        }

        String qdrantId = toStableUuid(bizId);

        String title = safe(luceneDoc.get("TITLE"));
        String content = safe(luceneDoc.get("CONTENT"));
        String semanticText = buildSemanticText(title, content);
        if (semanticText.isBlank()) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("biz_id", bizId);
        metadata.put("title", title);
        metadata.put("url", safe(luceneDoc.get("URL")));
        metadata.put("publishTime", safe(luceneDoc.get("PUBLISH_TIME")));
        metadata.put("source", safe(luceneDoc.get("SOURCE")));
        metadata.put("author", safe(luceneDoc.get("AUTHOR")));
        metadata.put("keyword", safe(luceneDoc.get("KEYWORD")));

        org.springframework.ai.document.Document aiDoc =
                new org.springframework.ai.document.Document(qdrantId, semanticText, metadata);

        try {
            vectorStore.delete(List.of(qdrantId));
        } catch (Exception e) {
            log.debug("删除旧向量点失败，继续新增。bizId={}, qdrantId={}, err={}", bizId, qdrantId, e.getMessage());
        }

        vectorStore.add(List.of(aiDoc));
    }

    public List<SemanticHit> search(String query, int topK) {
        try {
            String queryText = truncate(normalize(query), QUERY_EMBED_LIMIT);
            if (queryText.isBlank()) {
                return Collections.emptyList();
            }

            SearchRequest request = SearchRequest.builder()
                    .query(queryText)
                    .topK(topK)
                    .build();

            List<org.springframework.ai.document.Document> docs = vectorStore.similaritySearch(request);
            if (docs == null || docs.isEmpty()) {
                return Collections.emptyList();
            }

            List<SemanticHit> hits = new ArrayList<>();
            for (org.springframework.ai.document.Document doc : docs) {
                String docId = resolveBizId(doc);
                if (!docId.isBlank()) {
                    hits.add(new SemanticHit(docId, doc.getScore() == null ? 0.0 : doc.getScore()));
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("Qdrant 语义检索失败，退化为仅关键词检索。query={}, err={}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildSemanticText(String title, String content) {
        String cleanTitle = normalize(title);
        String cleanContent = truncate(normalize(content), DOC_EMBED_LIMIT);
        String merged = (cleanTitle + "\n" + cleanContent).trim();

        if (!merged.isBlank()) {
            return merged;
        }
        return truncate(cleanTitle, 120);
    }

    private String resolveBizId(org.springframework.ai.document.Document doc) {
        Object bizId = doc.getMetadata().get("biz_id");
        if (bizId != null && !bizId.toString().isBlank()) {
            return bizId.toString();
        }
        return doc.getId() == null ? "" : doc.getId();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    public record SemanticHit(String docId, double score) {}
}