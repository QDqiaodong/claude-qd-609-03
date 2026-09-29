package com.archery.range.service;

import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;

/**
 * 个人最好成绩的比较口径（模块三核心规则）。
 *
 * 个人最好成绩只在「可比组别」内独立计算，可比组别由四个维度共同确定：
 * 会员 + 弓种 + 射距（取回合所属箭道的 distance）+ 规定箭数（arrow_count）。
 * 6 支与 12 支、不同弓种、不同射距之间永远不可比，每个组别各自最多保留一条最好成绩。
 *
 * 只有已完成（SUBMITTED）且箭数完整（round_arrow 中的箭数 == arrow_count）的回合才能参与比较；
 * 进行中 / 暂停 / 数据不完整 / 关联对象缺失的回合保留原始成绩，但不能参与、也不能挂最好标记。
 *
 * 历史兼容：老数据里 bow_type 为空的旧回合按系统原有默认弓种 RECURVE 处理（与开打接口的默认值一致）。
 */
public final class PersonalBestSupport {

    /** 系统原有默认弓种：开新回合时未指定弓种即按反曲弓计 */
    public static final String DEFAULT_BOW_TYPE = "RECURVE";

    private PersonalBestSupport() {
    }

    /** 弓种归一化：空值按默认弓种兼容，其余去除空白并转大写 */
    public static String normalizeBowType(String bowType) {
        if (bowType == null || bowType.isBlank()) {
            return DEFAULT_BOW_TYPE;
        }
        return bowType.trim().toUpperCase();
    }

    /** 可比组别键：会员 id + 归一化弓种 + 射距 + 规定箭数；任何一维不可得即无法成组（返回 null） */
    public static GroupKey groupKey(Round round) {
        return groupKeyOrNull(round);
    }

    /**
     * 判定回合能否参与个人最好比较。
     *
     * @return null 表示可参与；否则为排除原因代码（NOT_SUBMITTED / MEMBER_MISSING /
     *         LANE_MISSING / DISTANCE_UNKNOWN / BOW_TYPE_UNKNOWN /
     *         GROUP_SIZE_INVALID / ARROWS_INCOMPLETE / SCORE_MISSING）
     */
    public static String exclusionReason(Round round) {
        if (round == null) {
            return "ROUND_MISSING";
        }
        if (!"SUBMITTED".equals(round.getStatus())) {
            // 进行中 / 暂停 / 任何非已完成状态：保留成绩但不参与比较
            return "NOT_SUBMITTED";
        }
        Member member = round.getMember();
        if (member == null || member.getId() == null) {
            return "MEMBER_MISSING";
        }
        Lane lane = round.getLane();
        if (lane == null || lane.getId() == null) {
            return "LANE_MISSING";
        }
        if (!RangeDict.isValidDistance(lane.getDistance())) {
            return "DISTANCE_UNKNOWN";
        }
        String bowType = normalizeBowType(round.getBowType());
        if (!RangeDict.isValidBowType(bowType)) {
            return "BOW_TYPE_UNKNOWN";
        }
        if (!RangeDict.isValidGroupSize(round.getArrowCount())) {
            return "GROUP_SIZE_INVALID";
        }
        int shotCount = round.getArrows() == null ? 0 : round.getArrows().size();
        if (shotCount < round.getArrowCount()) {
            // 箭数不完整（数据缺失）：不能参与，也不能静默归入别的组
            return "ARROWS_INCOMPLETE";
        }
        if (round.getTotalScore() == null) {
            // 总分缺失的数据不完整回合：保留原始记录，但不参与比较
            return "SCORE_MISSING";
        }
        return null;
    }

    public static boolean isEligible(Round round) {
        return exclusionReason(round) == null;
    }

    /** 排除原因的中文说明（用于修复报告与前端展示） */
    public static String reasonName(String reason, Round round) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case "NOT_SUBMITTED" -> "回合状态为「" + RangeDict.roundStatusName(round == null ? "" : round.getStatus())
                    + "」，尚未完成，不参与个人最好比较";
            case "MEMBER_MISSING" -> "关联会员缺失，无法确定可比组别";
            case "LANE_MISSING" -> "关联箭道缺失，无法确定射距";
            case "DISTANCE_UNKNOWN" -> "射距无法确定（箭道距离为空或不在 10/18/30/50 米内）";
            case "BOW_TYPE_UNKNOWN" -> "弓种「" + (round == null ? "" : round.getBowType()) + "」不在认可范围内";
            case "GROUP_SIZE_INVALID" -> "规定箭数 " + (round == null ? "" : round.getArrowCount())
                    + " 不是一组 6 支或 12 支";
            case "ARROWS_INCOMPLETE" -> {
                int expected = round == null || round.getArrowCount() == null ? 0 : round.getArrowCount();
                int actual = round == null || round.getArrows() == null ? 0 : round.getArrows().size();
                yield "箭数不完整（实际 " + actual + " / 规定 " + expected + "）";
            }
            case "SCORE_MISSING" -> "总分数据缺失，无法比较";
            case "ROUND_MISSING" -> "回合不存在";
            default -> reason;
        };
    }

    /**
     * 比较完成先后：按完成时间（无单独完成时间字段，以开始时间为完成次序）升序，
     * 同一时刻按回合 id 升序兜底，保证顺序确定、同分永远保留最早完成的回合。
     */
    public static int completionOrder(Round a, Round b) {
        java.util.Comparator<Round> byTime = java.util.Comparator.comparing(
                Round::getStartTime, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        java.util.Comparator<Round> byId = java.util.Comparator.comparing(
                Round::getId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        return byTime.thenComparing(byId).compare(a, b);
    }

    private static GroupKey groupKeyOrNull(Round round) {
        if (round == null || round.getMember() == null || round.getMember().getId() == null) {
            return null;
        }
        Lane lane = round.getLane();
        if (lane == null || !RangeDict.isValidDistance(lane.getDistance())) {
            return null;
        }
        String bowType = normalizeBowType(round.getBowType());
        if (!RangeDict.isValidBowType(bowType) || !RangeDict.isValidGroupSize(round.getArrowCount())) {
            return null;
        }
        return new GroupKey(round.getMember().getId(), bowType, lane.getDistance(), round.getArrowCount());
    }

    /** 可比组别键：会员 + 弓种 + 射距 + 规定箭数 */
    public record GroupKey(Long memberId, String bowType, Integer distance, Integer arrowCount) {

        public String label() {
            return RangeDict.bowTypeName(bowType) + " · " + distance + " 米 · " + arrowCount + " 支";
        }
    }
}
