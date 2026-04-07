package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.model.NewsArticle;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.selector.Html;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TencentTechNewsCrawler implements PageProcessor {

    public static final String RESULT_ITEM_KEY = "tencent_news_article";

    private static final Pattern DETAIL_URL_PATTERN =
            Pattern.compile("https?://(news\\.qq\\.com/rain/a/|view\\.inews\\.qq\\.com/a/)[A-Z0-9]+.*");

    private static final List<String> KEYWORDS = List.of("龙虾", "OpenClaw");

    private final int limit;
    private final AtomicInteger collected = new AtomicInteger(0);
    private final Set<String> acceptedUrls = ConcurrentHashMap.newKeySet();

    private final Site site = Site.me()
            .setCharset("UTF-8")
            .setRetryTimes(3)
            .setSleepTime(1500)
            .setUserAgent("Mozilla/5.0");

    public TencentTechNewsCrawler(int limit) {
        this.limit = limit;
    }

    @Override
    public void process(Page page) {
        String url = page.getUrl().get();

        if (isListPage(url)) {
            List<String> detailUrls = page.getHtml().links().all().stream()
                    .filter(link -> DETAIL_URL_PATTERN.matcher(link).matches())
                    .distinct()
                    .collect(Collectors.toList());
            page.addTargetRequests(detailUrls);
            page.setSkip(true);
            return;
        }

        if (collected.get() >= limit) {
            page.setSkip(true);
            return;
        }

        NewsArticle article = parseDetail(page.getHtml(), url);
        if (article == null) {
            page.setSkip(true);
            return;
        }

        if (!containsKeyword(article.getTitle(), article.getContent())) {
            page.setSkip(true);
            return;
        }

        if (!acceptedUrls.add(url)) {
            page.setSkip(true);
            return;
        }

        article.setKeyword(hitKeyword(article.getTitle(), article.getContent()));
        article.setId(md5(url));

        page.putField(RESULT_ITEM_KEY, article);
        collected.incrementAndGet();
    }

    @Override
    public Site getSite() {
        return site;
    }

    private boolean isListPage(String url) {
        return url.contains("/ch/tech");
    }

    private NewsArticle parseDetail(Html html, String url) {
        String title = firstNonBlank(
                html.xpath("//h1/text()").get(),
                html.xpath("//title/text()").get()
        );

        List<String> paragraphs = new ArrayList<>();
        paragraphs.addAll(html.xpath("//article//p//text()").all());
        paragraphs.addAll(html.xpath("//div[contains(@class,'content')]//p//text()").all());
        paragraphs.addAll(html.xpath("//div[contains(@class,'article-content')]//p//text()").all());
        paragraphs.addAll(html.xpath("//div[contains(@class,'main-content')]//p//text()").all());

        String content = paragraphs.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.joining("\n"));

        if (title == null || title.isBlank() || content.isBlank()) {
            return null;
        }

        NewsArticle article = new NewsArticle();
        article.setUrl(url);
        article.setTitle(title.trim());
        article.setContent(content);
        article.setAuthor(firstNonBlank(
                html.xpath("//meta[@name='author']/@content").get(),
                html.regex("([\\u4e00-\\u9fa5A-Za-z0-9·]{2,20})\\s+\\d{4}-\\d{2}-\\d{2}", 1).get()
        ));
        article.setSource(firstNonBlank(
                html.xpath("//meta[@name='apub:media_name']/@content").get(),
                html.xpath("//meta[@property='og:site_name']/@content").get(),
                "腾讯新闻"
        ));
        article.setPublishTime(firstNonBlank(
                html.xpath("//meta[@name='apub:time']/@content").get(),
                html.xpath("//meta[@property='og:pubdate']/@content").get(),
                html.regex("(20\\d{2}-\\d{2}-\\d{2}\\s*\\d{2}:\\d{2})", 1).get()
        ));
        return article;
    }

    private boolean containsKeyword(String title, String content) {
        String all = (title == null ? "" : title) + "\n" + (content == null ? "" : content);
        return KEYWORDS.stream().anyMatch(all::contains);
    }

    private String hitKeyword(String title, String content) {
        String all = (title == null ? "" : title) + "\n" + (content == null ? "" : content);
        return KEYWORDS.stream().filter(all::contains).findFirst().orElse("");
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}