package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 计分回合模块的入参与出参。
 *
 * ArrowView 的 shotIndex 就是有序集合里的位置（从 1 开始），
 * 前端按它顺序渲染箭支徽章。
 */
public final class RoundDtos {

    private RoundDtos() {
    }

    public record RoundStartReq(
            @NotNull(message = "请选择会员") Long memberId,
            @NotNull(message = "请选择箭道") Long laneId,
            String bowType,
            @NotNull(message = "请选择箭支数") Integer arrowCount) {
    }

    /** 记一支箭：环数标记 X / 10 / 9 ... / M */
    public record ShotReq(@NotBlank(message = "请选择环数") String ring) {
    }

    public record ArrowView(Integer shotIndex, String ring, Integer ringValue) {
    }

    public record RoundView(
            Long id,
            String roundNo,
            Long memberId,
            String memberName,
            String memberCardNo,
            Long laneId,
            String laneNo,
            Integer distance,
            String bowType,
            String bowTypeName,
            LocalDateTime startTime,
            Integer arrowCount,
            Integer shotCount,
            Integer totalScore,
            BigDecimal averageScore,
            Boolean personalBest,
            String status,
            String statusName,
            // 个人最好比较口径：可比组别（会员 + 弓种 + 射距 + 规定箭数）与参与资格
            String groupKey,
            String groupLabel,
            Boolean eligible,
            String exclusionReason,
            String exclusionReasonName) {
    }

    /** 成绩单：回合 + 按 shot_index 顺序排好的每一支箭 */
    public record RoundDetailView(RoundView round, List<ArrowView> arrows) {
    }

    /** 历史修复中被排除在个人最好比较之外的回合：原始成绩保留，仅明确列出原因 */
    public record ExcludedRoundView(
            Long id,
            String roundNo,
            Long memberId,
            String memberName,
            Long laneId,
            String laneNo,
            String bowType,
            String bowTypeName,
            Integer distance,
            Integer arrowCount,
            Integer shotCount,
            Integer totalScore,
            String status,
            String statusName,
            LocalDateTime startTime,
            String exclusionReason,
            String exclusionReasonName) {
    }

    /**
     * 个人最好历史修复报告：可重复执行（幂等）。
     * 只改 personal_best 标记，changedMarks 为本轮真正翻转标记的回合数（稳态后为 0）。
     */
    public record PersonalBestRepairReport(
            int totalRounds,
            int eligibleRounds,
            int groupCount,
            int personalBestCount,
            int excludedCount,
            int changedMarks,
            List<ExcludedRoundView> excludedRounds) {
    }
}
