package com.jparlant.service.flow.handler.action;

import java.util.ArrayList;
import java.util.List;

/**
 * 对应配置：loanCalculatorService.generateRepaymentPlan
 */
public class LoanCalculatorService {

    /**
     * 生成还款计划
     *
     * @param principal 对应配置映射中的 loan_request.amount
     * @param months    对应配置映射中的 loan_request.term_months
     * @param userScore 对应配置映射中的 risk_assessment.credit_score (来自上一步风控结果)
     * @return 包含建议利率和计划列表的对象
     */
    public RepaymentResponse generateRepaymentPlan(double principal, int months, int userScore) {
        RepaymentResponse response = new RepaymentResponse();

        // 1. 根据信用分计算建议利率
        double suggestedRate = (userScore > 700) ? 0.05 : 0.08;
        response.setSuggestedRate(suggestedRate);

        // 2. 生成还款计划列表 (ARRAY 类型映射)
        List<PlanItem> planList = new ArrayList<>();
        double monthlyPayment = (principal * (1 + suggestedRate)) / months;

        for (int i = 1; i <= months; i++) {
            PlanItem item = new PlanItem();
            item.setPeriod(i);
            item.setMonthlyPayment(monthlyPayment);
            item.setDueDate("2024-0" + (i % 9 + 1) + "-10"); // 模拟日期生成
            planList.add(item);
        }

        response.setPlanList(planList);
        return response;
    }

    // 对应 outputMapping 的结构
    public static class RepaymentResponse {
        private double suggestedRate;
        private List<PlanItem> planList;

        public double getSuggestedRate() { return suggestedRate; }
        public void setSuggestedRate(double suggestedRate) { this.suggestedRate = suggestedRate; }

        public List<PlanItem> getPlanList() { return planList; }
        public void setPlanList(List<PlanItem> planList) { this.planList = planList; }
    }

    // 对应 elementMapping 中的 Item 结构
    public static class PlanItem {
        private int period;
        private double monthlyPayment;
        private String dueDate;

        public int getPeriod() { return period; }
        public void setPeriod(int period) { this.period = period; }

        public double getMonthlyPayment() { return monthlyPayment; }
        public void setMonthlyPayment(double monthlyPayment) { this.monthlyPayment = monthlyPayment; }

        public String getDueDate() { return dueDate; }
        public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    }
}
