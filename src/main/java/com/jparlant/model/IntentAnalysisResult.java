package com.jparlant.model;

import com.jparlant.enums.Complexity;
import com.jparlant.enums.Emotion;
import lombok.Builder;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;



/**
 * 意图分析最终结果
 */
@Builder(toBuilder = true)
public record IntentAnalysisResult(
        Long primaryIntentId,                        // 意图id
        String primaryIntentName,                    // 意图名称
        String thinking,                             // 推理原因
        double confidence,                           // 置信度
        Emotion emotion,                             // 情绪状态
        Complexity complexity,                       // 复杂度
        Map<String, Object> extractedEntities,       // 提取的实体
        StepJumpDetection stepJump,                  // 步骤跳跃检测结果
        String userInput,                           // 原始输入
        AgentFlow activeFlow,                        // 识别到的意图对应的实体
        List<MultipartFile> files                           // 文件内容
) {

    private static final String GENERAL_INTENT = "GENERAL";

    public static IntentAnalysisResult fallback(String input, String intent) {
        return IntentAnalysisResult.builder()
                .primaryIntentName(StringUtils.hasText(intent) ? intent : GENERAL_INTENT)
                .userInput(input)
                .confidence(0.0)
                .stepJump(StepJumpDetection.none())
                .build();
    }


    public IntentAnalysisResult withJump(boolean isJump, Long targetStepId, String reason) {
        return this.toBuilder()
                .stepJump(new StepJumpDetection(targetStepId, isJump, reason))
                .build();
    }

    public IntentAnalysisResult withFiles(List<MultipartFile> files){
        return this.toBuilder().files(files).build();
    }

}
