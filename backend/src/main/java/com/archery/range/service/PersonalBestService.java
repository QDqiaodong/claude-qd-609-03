package com.archery.range.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.dto.RoundDtos;
import com.archery.range.repository.RoundRepository;
import com.archery.range.service.PersonalBestSupport.GroupKey;

/**
 * 个人最好成绩重算。
 *
 * 比较口径见 {@link PersonalBestSupport}：可比组别 = 会员 + 弓种 + 射距 + 规定箭数；
 * 只有已完成且箭数完整的回合参与，分数最高者成为该组别唯一的个人最好，
 * 同分时保留最早完成的回合（按完成时间、再按回合 id 兜底，顺序确定，不会来回切换）。
 *
 * 两种入口：
 * 1. 新回合提交时 {@link #refreshGroup}：只重算该回合所属的一个组别，清除同组旧标记；
 * 2. 升级历史数据 {@link #repairAll}：按完成时间重算所有可比组别，可重复执行、结果幂等，
 *    只翻转 personal_best 标记，不触碰箭支顺序、环值、总分、平均环、认证证据或原始完成时间。
 */
@Service
public class PersonalBestService {

    private static final Logger log = LoggerFactory.getLogger(PersonalBestService.class);

    /** 分数高者优先；同分则完成时间最早者优先（completionOrder 已保证确定性） */
    private static final Comparator<Round> WINNER_ORDER = Comparator
            .comparingInt((Round r) -> r.getTotalScore() == null ? 0 : r.getTotalScore())
            .reversed()
            .thenComparing(PersonalBestSupport::completionOrder);

    private final RoundRepository roundRepository;

    public PersonalBestService(RoundRepository roundRepository) {
        this.roundRepository = roundRepository;
    }

    /**
     * 新回合完成：只重算同一可比组别的标记。
     * 调用前必须确认 round 已 SUBMITTED 且箭数完整（提交接口已校验）。
     * 新高分 → 新回合成为唯一最好并清除旧标记；同分 → 最早完成的回合保持最好。
     */
    @Transactional
    public void refreshGroup(Round current) {
        GroupKey key = PersonalBestSupport.groupKey(current);
        if (key == null) {
            // 理论上不会发生（提交时已校验箭道 / 弓种 / 箭数），稳妥起见不动任何标记
            log.warn("提交回合 {} 的可比组别不完整，跳过个人最好判定", current.getId());
            return;
        }
        List<Round> peers = new ArrayList<>();
        // 锁定该会员全部回合行：同一会员并发提交时串行，保证每个组别至多一条最好标记
        for (Round item : roundRepository.findByMemberIdForUpdate(key.memberId())) {
            if (PersonalBestSupport.isEligible(item)
                    && key.equals(PersonalBestSupport.groupKey(item))) {
                peers.add(item);
            }
        }
        applyWinner(peers);
    }

    /**
     * 历史修复：按完成时间重算每个可比组别，可重复执行。
     * 不改动除 personal_best 外的任何字段；异常 / 不完整记录保留原始成绩并在报告中明确列出。
     */
    @Transactional
    public RoundDtos.PersonalBestRepairReport repairAll() {
        // 按 id（也即创建顺序）稳定遍历，保证报告顺序、结果在多次执行间完全一致
        List<Round> all = roundRepository.findAllByOrderByIdAsc();

        Map<GroupKey, List<Round>> groups = new LinkedHashMap<>();
        List<Round> excludedRounds = new ArrayList<>();
        int changedMarks = 0;

        for (Round round : all) {
            String reason = PersonalBestSupport.exclusionReason(round);
            if (reason != null) {
                // 无法参与比较的记录：保留原始成绩，清除其可能残留的旧口径最好标记
                if (Boolean.TRUE.equals(round.getPersonalBest())) {
                    round.setPersonalBest(false);
                    changedMarks += 1;
                }
                excludedRounds.add(round);
                continue;
            }
            groups.computeIfAbsent(PersonalBestSupport.groupKey(round), k -> new ArrayList<>()).add(round);
        }

        int personalBestCount = 0;
        for (List<Round> group : groups.values()) {
            Round winner = group.stream().min(WINNER_ORDER).orElseThrow();
            for (Round round : group) {
                boolean isBest = round.getId().equals(winner.getId());
                if (isBest) {
                    personalBestCount += 1;
                }
                if (isBest != Boolean.TRUE.equals(round.getPersonalBest())) {
                    round.setPersonalBest(isBest);
                    changedMarks += 1;
                }
            }
        }

        // 脏检查在事务提交时统一落库；只有 personal_best 被 set，其他字段原样不变。
        List<RoundDtos.ExcludedRoundView> excludedViews = excludedRounds.stream()
                .map(this::toExcludedView)
                .toList();
        return new RoundDtos.PersonalBestRepairReport(
                all.size(),
                all.size() - excludedRounds.size(),
                groups.size(),
                personalBestCount,
                excludedRounds.size(),
                changedMarks,
                excludedViews);
    }

    /** 在同组内选出唯一最好并写回标记（入参回合均为受管实体） */
    private void applyWinner(List<Round> group) {
        Round winner = group.stream().min(WINNER_ORDER).orElse(null);
        for (Round round : group) {
            boolean isBest = winner != null && round.getId().equals(winner.getId());
            if (isBest != Boolean.TRUE.equals(round.getPersonalBest())) {
                round.setPersonalBest(isBest);
            }
        }
    }

    private RoundDtos.ExcludedRoundView toExcludedView(Round round) {
        String reason = PersonalBestSupport.exclusionReason(round);
        Member member = round.getMember();
        Lane lane = round.getLane();
        String bowType = PersonalBestSupport.normalizeBowType(round.getBowType());
        return new RoundDtos.ExcludedRoundView(
                round.getId(),
                round.getRoundNo(),
                member == null ? null : member.getId(),
                member == null ? "" : member.getName(),
                lane == null ? null : lane.getId(),
                lane == null ? "" : lane.getLaneNo(),
                round.getBowType(),
                RangeDict.isValidBowType(bowType) ? RangeDict.bowTypeName(bowType) : bowType,
                lane == null ? null : lane.getDistance(),
                round.getArrowCount(),
                round.getArrows() == null ? 0 : round.getArrows().size(),
                round.getTotalScore(),
                round.getStatus(),
                RangeDict.roundStatusName(round.getStatus()),
                round.getStartTime(),
                reason,
                PersonalBestSupport.reasonName(reason, round));
    }
}
