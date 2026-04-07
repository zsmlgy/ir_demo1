package cn.edu.bistu.cs.ir.controller;

import cn.edu.bistu.cs.ir.model.AskRequest;
import cn.edu.bistu.cs.ir.model.AskResponse;
import cn.edu.bistu.cs.ir.service.RagService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) throws Exception {
        return ragService.ask(request.question());
    }
}