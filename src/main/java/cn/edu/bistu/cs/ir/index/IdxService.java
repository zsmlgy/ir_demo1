package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.config.Config;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class IdxService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IdxService.class);

    private static final Class<? extends Analyzer> DEFAULT_ANALYZER = StandardAnalyzer.class;

    private static final String VECTOR_FIELD = "CONTENT_VECTOR";

    // 多档回退，尽量避免超长
    private static final int[] EMBED_LIMITS = {1000, 700, 400};

    // 查询一般很短，单独限制一下
    private static final int QUERY_EMBED_LIMIT = 200;

    private final EmbeddingModel embeddingModel;

    private IndexWriter writer;

    public IdxService(@Autowired Config config,
                      @Autowired EmbeddingModel embeddingModel) throws Exception {
        this.embeddingModel = embeddingModel;

        Analyzer analyzer = DEFAULT_ANALYZER.getConstructor().newInstance();
        try {
            Directory index = FSDirectory.open(Paths.get(config.getIdx()));
            IndexWriterConfig writerConfig = new IndexWriterConfig(analyzer);
            writer = new IndexWriter(index, writerConfig);
            log.info("索引初始化完成，索引目录为:[{}]", config.getIdx());
        } catch (IOException e) {
            log.error("无法初始化索引，请检查提供的索引目录是否可用:[{}]", config.getIdx(), e);
            writer = null;
        }
    }

    public boolean addDocument(String idFld, String id, Document doc) {
        if (writer == null || doc == null) {
            log.error("Writer对象或文档对象为空，无法添加文档到索引中");
            return false;
        }
        if (StringUtil.isEmpty(idFld) || StringUtil.isEmpty(id)) {
            log.error("ID字段名或ID字段值为空，无法添加文档到索引中");
            return false;
        }

        try {
            // 向量字段失败时不影响普通索引写入
            try {
                addVectorField(doc);
            } catch (Exception e) {
                log.warn("向量字段生成失败，继续写普通索引。TITLE=[{}], 原因=[{}]",
                        safe(doc.get("TITLE")), e.getMessage());
            }

            writer.updateDocument(new Term(idFld, id), doc);
            writer.commit();
            log.info("成功将ID为[{}]的文档加入索引", id);
            return true;
        } catch (IOException e) {
            log.error("构建索引失败", e);
            return false;
        }
    }

    private void addVectorField(Document doc) {
        String title = safe(doc.get("TITLE"));
        String content = safe(doc.get("CONTENT"));

        List<String> candidates = buildEmbeddingCandidates(title, content);
        if (candidates.isEmpty()) {
            return;
        }

        Exception lastException = null;

        for (String text : candidates) {
            try {
                float[] vector = embeddingModel.embed(text);
                doc.removeFields(VECTOR_FIELD);
                doc.add(new KnnVectorField(VECTOR_FIELD, vector));
                return;
            } catch (Exception e) {
                lastException = e;
            }
        }

        throw new RuntimeException("多次尝试生成向量仍失败", lastException);
    }

    private List<String> buildEmbeddingCandidates(String title, String content) {
        String cleanTitle = normalize(title);
        String cleanContent = normalize(content);

        List<String> candidates = new ArrayList<>();

        for (int limit : EMBED_LIMITS) {
            String truncatedContent = truncate(cleanContent, limit);
            String merged = (cleanTitle + "\n" + truncatedContent).trim();
            merged = truncate(merged, limit);
            if (!merged.isBlank()) {
                candidates.add(merged);
            }
        }

        // 最后再加一个只用标题的兜底
        if (!cleanTitle.isBlank()) {
            candidates.add(truncate(cleanTitle, 120));
        }

        return candidates.stream().distinct().collect(Collectors.toList());
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

    public List<Document> queryByKw(String kw) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = DEFAULT_ANALYZER.getConstructor().newInstance();
            QueryParser parser = new QueryParser("TITLE", analyzer);
            Query query = parser.parse(QueryParser.escape(kw));

            TopDocs docs = searcher.search(query, 10);
            List<Document> results = new ArrayList<>();
            for (ScoreDoc doc : docs.scoreDocs) {
                results.add(searcher.doc(doc.doc));
            }
            return results;
        }
    }

    public List<ScoreDoc> keywordSearch(IndexSearcher searcher, String kw, int topK) throws Exception {
        Analyzer analyzer = DEFAULT_ANALYZER.getConstructor().newInstance();
        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[]{"TITLE", "CONTENT"},
                analyzer
        );
        parser.setDefaultOperator(QueryParser.Operator.OR);

        Query query = parser.parse(QueryParser.escape(kw));
        TopDocs docs = searcher.search(query, topK);
        return Arrays.asList(docs.scoreDocs);
    }

    public List<ScoreDoc> vectorSearch(IndexSearcher searcher, String kw, int topK) {
        try {
            String queryText = truncate(normalize(kw), QUERY_EMBED_LIMIT);
            if (queryText.isBlank()) {
                return Collections.emptyList();
            }

            float[] queryVector = embeddingModel.embed(queryText);
            Query vectorQuery = new KnnVectorQuery(VECTOR_FIELD, queryVector, topK);
            TopDocs docs = searcher.search(vectorQuery, topK);
            return Arrays.asList(docs.scoreDocs);
        } catch (Exception e) {
            log.warn("向量检索失败，退化为仅关键词检索，kw=[{}], 原因=[{}]", kw, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Document> hybridSearch(String kw, int topK) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            List<ScoreDoc> keywordDocs = keywordSearch(searcher, kw, topK);
            List<ScoreDoc> vectorDocs = vectorSearch(searcher, kw, topK);

            // 两路都没结果
            if (keywordDocs.isEmpty() && vectorDocs.isEmpty()) {
                return Collections.emptyList();
            }

            // 只有关键词结果
            if (vectorDocs.isEmpty()) {
                return keywordDocs.stream()
                        .limit(topK)
                        .map(sd -> {
                            try {
                                return searcher.doc(sd.doc);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .collect(Collectors.toList());
            }

            Map<Integer, Double> rrfScores = new HashMap<>();
            int k = 60;

            for (int i = 0; i < keywordDocs.size(); i++) {
                int docId = keywordDocs.get(i).doc;
                rrfScores.merge(docId, 1.0 / (k + i + 1), Double::sum);
            }

            for (int i = 0; i < vectorDocs.size(); i++) {
                int docId = vectorDocs.get(i).doc;
                rrfScores.merge(docId, 1.0 / (k + i + 1), Double::sum);
            }

            return rrfScores.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(topK)
                    .map(e -> {
                        try {
                            return searcher.doc(e.getKey());
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    })
                    .collect(Collectors.toList());
        }
    }

    public List<Document> searchNews(String kw, int topK) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = DEFAULT_ANALYZER.getConstructor().newInstance();

            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{"TITLE", "CONTENT"},
                    analyzer
            );
            parser.setDefaultOperator(QueryParser.Operator.OR);

            Query query = parser.parse(QueryParser.escape(kw));
            TopDocs docs = searcher.search(query, topK);

            List<Document> results = new ArrayList<>();
            for (ScoreDoc hit : docs.scoreDocs) {
                results.add(searcher.doc(hit.doc));
            }
            return results;
        }
    }

    @Override
    public void destroy() {
        if (this.writer == null) {
            return;
        }
        try {
            log.info("索引关闭");
            writer.close();
        } catch (IOException e) {
            log.info("尝试关闭索引失败", e);
        }
    }
}