package cn.edu.bistu.cs.ir.controller;

import cn.edu.bistu.cs.ir.crawler.CrawlerService;
import cn.edu.bistu.cs.ir.index.IdxService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CrawlerService crawlerService;
    private final IdxService idxService;

    public AdminController(CrawlerService crawlerService, IdxService idxService) {
        this.crawlerService = crawlerService;
        this.idxService = idxService;
    }

    @PostMapping("/crawl/tencent-tech")
    public Map<String, Object> crawl(@RequestParam(defaultValue = "10") int limit) {
        crawlerService.crawlTencentTechNews(limit);
        return Map.of(
                "success", true,
                "message", "腾讯科技新闻抓取与索引已执行完成",
                "limit", limit
        );
    }

    @PostMapping("/rebuild-semantic")
    public Map<String, Object> rebuildSemantic() throws Exception {
        int count = idxService.rebuildSemanticIndexFromLucene();
        return Map.of(
                "success", true,
                "message", "Qdrant 语义索引重建完成",
                "count", count
        );
    }
}