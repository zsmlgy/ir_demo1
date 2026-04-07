package cn.edu.bistu.cs.ir.controller;

import cn.edu.bistu.cs.ir.crawler.CrawlerService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CrawlerService crawlerService;

    public AdminController(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
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
}