package cn.edu.bistu.cs.ir.controller;

import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final IdxService idxService;

    public QueryController(@Autowired IdxService idxService) {
        this.idxService = idxService;
    }

    @GetMapping(value = "/kw", produces = "application/json;charset=UTF-8")
    public QueryResponse<List<Map<String, String>>> queryByKw(@RequestParam(name = "kw") String kw,
                                                              @RequestParam(name = "pageNo", defaultValue = "1") int pageNo,
                                                              @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) {
        try {
            List<Document> docs = idxService.queryByKw(kw);

            List<Map<String, String>> results = new ArrayList<>();
            for (Document doc : docs) {
                Map<String, String> record = new HashMap<>(4);
                record.put("ID", doc.get("ID"));
                record.put("TITLE", doc.get("TITLE"));
                record.put("TIME", doc.get("PUBLISH_TIME"));
                results.add(record);
            }

            return QueryResponse.genSucc("检索成功", results);
        } catch (Exception e) {
            log.error("检索过程中发生异常:[{}]", e.getMessage(), e);
            return QueryResponse.genErr("检索过程中发生异常");
        }
    }

    @GetMapping(value = "/hybrid", produces = "application/json;charset=UTF-8")
    public QueryResponse<List<Map<String, String>>> hybridQuery(
            @RequestParam(name = "kw") String kw,
            @RequestParam(name = "topK", defaultValue = "10") int topK) {

        try {
            List<Document> docs = idxService.hybridSearch(kw, topK);

            List<Map<String, String>> results = new ArrayList<>();
            for (Document doc : docs) {
                Map<String, String> record = new HashMap<>(4);
                record.put("ID", doc.get("ID"));
                record.put("TITLE", doc.get("TITLE"));
                record.put("TIME", doc.get("PUBLISH_TIME"));
                results.add(record);
            }

            return QueryResponse.genSucc("混合检索成功", results);
        } catch (Exception e) {
            log.error("混合检索过程中发生异常:[{}]", e.getMessage(), e);
            return QueryResponse.genErr("混合检索过程中发生异常");
        }
    }
}