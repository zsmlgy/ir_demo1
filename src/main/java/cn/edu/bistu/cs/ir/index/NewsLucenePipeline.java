package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.crawler.TencentTechNewsCrawler;
import cn.edu.bistu.cs.ir.model.NewsArticle;
import org.apache.lucene.document.*;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.pipeline.Pipeline;

public class NewsLucenePipeline implements Pipeline {

    private final IdxService idxService;

    public NewsLucenePipeline(IdxService idxService) {
        this.idxService = idxService;
    }

    @Override
    public void process(ResultItems resultItems, Task task) {
        NewsArticle article = resultItems.get(TencentTechNewsCrawler.RESULT_ITEM_KEY);
        if (article == null) {
            return;
        }

        Document doc = new Document();
        doc.add(new StringField("ID", article.getId(), Field.Store.YES));
        doc.add(new StringField("URL", safe(article.getUrl()), Field.Store.YES));
        doc.add(new TextField("TITLE", safe(article.getTitle()), Field.Store.YES));
        doc.add(new TextField("CONTENT", safe(article.getContent()), Field.Store.YES));
        doc.add(new StringField("SOURCE", safe(article.getSource()), Field.Store.YES));
        doc.add(new StringField("AUTHOR", safe(article.getAuthor()), Field.Store.YES));
        doc.add(new StringField("PUBLISH_TIME", safe(article.getPublishTime()), Field.Store.YES));
        doc.add(new StringField("KEYWORD", safe(article.getKeyword()), Field.Store.YES));

        idxService.addDocument("ID", article.getId(), doc);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}