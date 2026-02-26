package com.jparlant.controller;

import com.jparlant.model.BeanWithMethods;
import com.jparlant.model.JParlantResult;
import com.jparlant.service.flow.handler.action.FlowMetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/jparlant/metadata")
public class FlowMetadataController {

    private final FlowMetadataService flowMetadataService;

    public FlowMetadataController(FlowMetadataService flowMetadataService) {
        this.flowMetadataService = flowMetadataService;
    }

    /**
     * 获取所有可用的业务执行器元数据
     */
    @GetMapping("/actions")
    public Mono<JParlantResult<List<BeanWithMethods>>> listAvailableActions() {
        // 使用 Mono.fromCallable 处理同步的反射逻辑，并返回响应式结果
        return Mono.fromCallable(flowMetadataService::getAllActionsFromMetadata)
                .map(JParlantResult::success)
                .onErrorResume(e -> Mono.just(JParlantResult.error("元数据解析失败: " + e.getMessage())));
    }
}
