package com.jparlant.service.flow.handler.action;


/**
 * 对应配置：riskControlService.checkUserCredit
 */
public class RiskControlService {

    /**
     * 检查用户信用
     *
     * @param idCard 对应配置映射中的 applicant_profile.identity_info.card_no
     * @param mobile 对应配置映射中的 applicant_profile.mobile
     * @return 包含 score 和 blacklisted 的结果对象
     */
    public RiskResult checkUserCredit(String idCard, String mobile) {
        // 模拟业务逻辑
        RiskResult result = new RiskResult();

        // 示例：简单逻辑模拟
        if (idCard == null || idCard.isEmpty()) {
            result.setScore(0);
            result.setBlacklisted(true);
        } else {
            result.setScore(750); // 模拟评分
            result.setBlacklisted(false);
        }

        return result;
    }

    // 内部输出类，便于编排引擎通过 outputMapping 提取
    public static class RiskResult {
        private int score;
        private boolean blacklisted;

        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }

        public boolean isBlacklisted() { return blacklisted; }
        public void setBlacklisted(boolean blacklisted) { this.blacklisted = blacklisted; }
    }
}
