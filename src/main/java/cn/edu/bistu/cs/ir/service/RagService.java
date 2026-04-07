package cn.edu.bistu.cs.ir.service;

import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.AskResponse;
import org.apache.lucene.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagService {

    private final IdxService idxService;
    private final ChatClient chatClient;

    public RagService(IdxService idxService, ChatClient.Builder chatClientBuilder) {
        this.idxService = idxService;
        this.chatClient = chatClientBuilder.build();
    }

    public AskResponse ask(String question) throws Exception {
        List<Document> docs = idxService.searchNews(question, 5);

        List<AskResponse.Source> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        int i = 1;
        for (Document doc : docs) {
            String title = doc.get("TITLE");
            String url = doc.get("URL");
            String publishTime = doc.get("PUBLISH_TIME");
            String content = doc.get("CONTENT");

            sources.add(new AskResponse.Source(title, url, publishTime));

            context.append("【新闻").append(i).append("】\n")
                    .append("标题：").append(title).append("\n")
                    .append("时间：").append(publishTime).append("\n")
                    .append("链接：").append(url).append("\n")
                    .append("正文节选：").append(trimContent(content, 1200)).append("\n\n");
            i++;
        }

        String answer;
        if (docs.isEmpty()) {
            answer = "当前本地索引里没有检索到与问题相关的腾讯新闻，无法基于新闻事实回答。";
        } else {
            answer = chatClient.prompt()
                    .system("""
你是一个基于“本地腾讯新闻索引”回答问题的助手。
请严格遵守：
1. 只能依据我提供的新闻上下文回答；
2. 不要编造新闻中不存在的事实；
3. 如果上下文不足，请明确说“根据当前已抓取新闻，无法确认”；
4. 回答尽量简洁、准确；
5. 最后附上“参考新闻”标题列表。
""")
                    .user("""
用户问题：
%s

下面是检索到的新闻上下文：
%s
""".formatted(question, context))
                    .call()
                    .content();
        }

        return new AskResponse(question, answer, sources);
    }

    private String trimContent(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }
}