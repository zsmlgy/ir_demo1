package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.config.Config;
import cn.edu.bistu.cs.ir.service.SemanticSearchService;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class IdxService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IdxService.class);

    @SuppressWarnings("rawtypes")
    private static final Class DEFAULT_ANALYZER = StandardAnalyzer.class;

    private final SemanticSearchService semanticSearchService;

    private IndexWriter writer;

    public IdxService(@Autowired Config config,
                      @Autowired SemanticSearchService semanticSearchService) throws Exception {
        this.semanticSearchService = semanticSearchService;

        Analyzer analyzer = (Analyzer) DEFAULT_ANALYZER.getConstructor().newInstance();
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
            writer.updateDocument(new Term(idFld, id), doc);
            writer.commit();

            try {
                semanticSearchService.upsertNews(doc);
            } catch (Exception e) {
                log.warn("Qdrant 写入失败，但 Lucene 已成功写入。id={}, err={}", id, e.getMessage());
            }

            log.info("成功将ID为[{}]的文档加入索引", id);
            return true;
        } catch (IOException e) {
            log.error("构建索引失败", e);
            return false;
        }
    }

    public List<Document> queryByKw(String kw) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = (Analyzer) DEFAULT_ANALYZER.getConstructor().newInstance();
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
        Analyzer analyzer = (Analyzer) DEFAULT_ANALYZER.getConstructor().newInstance();
        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[]{"TITLE", "CONTENT"}, analyzer
        );
        parser.setDefaultOperator(QueryParser.Operator.OR);
        Query query = parser.parse(QueryParser.escape(kw));
        TopDocs docs = searcher.search(query, topK);

        List<ScoreDoc> results = new ArrayList<>();
        Collections.addAll(results, docs.scoreDocs);
        return results;
    }

    public List<Document> hybridSearch(String kw, int topK) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            int candidateK = Math.max(topK * 4, 20);

            List<RankedDocId> lexicalHits = keywordSearchIds(searcher, kw, candidateK);
            List<SemanticSearchService.SemanticHit> semanticHits = semanticSearchService.search(kw, candidateK);

            if (lexicalHits.isEmpty() && semanticHits.isEmpty()) {
                return Collections.emptyList();
            }

            Map<String, Double> rrfScores = new HashMap<>();
            int fusionK = 60;

            for (int i = 0; i < lexicalHits.size(); i++) {
                rrfScores.merge(lexicalHits.get(i).docId(), 1.0 / (fusionK + i + 1), Double::sum);
            }

            for (int i = 0; i < semanticHits.size(); i++) {
                rrfScores.merge(semanticHits.get(i).docId(), 1.0 / (fusionK + i + 1), Double::sum);
            }

            List<String> finalDocIds = rrfScores.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(topK)
                    .map(Map.Entry::getKey)
                    .toList();

            return fetchDocsByIdsInOrder(searcher, finalDocIds);
        }
    }

    public List<Document> searchNews(String kw, int topK) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = (Analyzer) DEFAULT_ANALYZER.getConstructor().newInstance();
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{"TITLE", "CONTENT"}, analyzer
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

    public int rebuildSemanticIndexFromLucene() throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs allDocs = searcher.search(new MatchAllDocsQuery(), reader.numDocs());

            int count = 0;
            for (ScoreDoc scoreDoc : allDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                semanticSearchService.upsertNews(doc);
                count++;
            }
            return count;
        }
    }

    private List<RankedDocId> keywordSearchIds(IndexSearcher searcher, String kw, int topK) throws Exception {
        List<ScoreDoc> hits = keywordSearch(searcher, kw, topK);
        List<RankedDocId> results = new ArrayList<>();

        for (ScoreDoc hit : hits) {
            Document doc = searcher.doc(hit.doc);
            String businessId = safe(doc.get("ID"));
            if (!businessId.isBlank()) {
                results.add(new RankedDocId(businessId, hit.score));
            }
        }
        return results;
    }

    private List<Document> fetchDocsByIdsInOrder(IndexSearcher searcher, List<String> docIds) throws IOException {
        List<Document> docs = new ArrayList<>();

        for (String docId : docIds) {
            TopDocs topDocs = searcher.search(new TermQuery(new Term("ID", docId)), 1);
            if (topDocs.scoreDocs.length > 0) {
                docs.add(searcher.doc(topDocs.scoreDocs[0].doc));
            }
        }

        return docs;
    }

    private String safe(String text) {
        return text == null ? "" : text;
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

    private record RankedDocId(String docId, float score) {}
}