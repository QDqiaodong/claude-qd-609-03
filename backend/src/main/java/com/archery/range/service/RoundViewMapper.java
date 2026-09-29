package com.archery.range.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.dto.RoundDtos;
import com.archery.range.service.PersonalBestSupport.GroupKey;

/**
 * Round → RoundView 统一映射：计分台与看板共用，
 * 负责带上「可比组别 + 参与资格」信息（个人最好比较口径）。
 */
@Component
public class RoundViewMapper {

    public RoundDtos.RoundView toView(Round round) {
        Member member = round.getMember();
        Lane lane = round.getLane();
        String reason = PersonalBestSupport.exclusionReason(round);
        boolean eligible = reason == null;

        GroupKey key = PersonalBestSupport.groupKey(round);
        String groupKey = key == null ? null : key.memberId() + "|" + key.bowType()
                + "|" + key.distance() + "|" + key.arrowCount();
        // 无法成组时（如射距 / 弓种缺失），也尽力给出可读标签，前端照常展示回合本身
        String groupLabel = key != null ? key.label() : fallbackLabel(round);

        return new RoundDtos.RoundView(
                round.getId(),
                round.getRoundNo(),
                member == null ? null : member.getId(),
                member == null ? "" : member.getName(),
                member == null ? "" : member.getCardNo(),
                lane == null ? null : lane.getId(),
                lane == null ? "" : lane.getLaneNo(),
                lane == null ? null : lane.getDistance(),
                round.getBowType(),
                RangeDict.bowTypeName(PersonalBestSupport.normalizeBowType(round.getBowType())),
                round.getStartTime(),
                round.getArrowCount(),
                round.getArrows() == null ? 0 : round.getArrows().size(),
                round.getTotalScore(),
                round.getAverageScore(),
                round.getPersonalBest(),
                round.getStatus(),
                RangeDict.roundStatusName(round.getStatus()),
                groupKey,
                groupLabel,
                eligible,
                eligible ? null : reason,
                eligible ? null : PersonalBestSupport.reasonName(reason, round));
    }

    public List<RoundDtos.RoundView> toViews(List<Round> rounds) {
        List<RoundDtos.RoundView> views = new ArrayList<>(rounds.size());
        for (Round round : rounds) {
            views.add(toView(round));
        }
        return views;
    }

    private String fallbackLabel(Round round) {
        String bow = RangeDict.bowTypeName(PersonalBestSupport.normalizeBowType(round.getBowType()));
        Integer distance = round.getLane() == null ? null : round.getLane().getDistance();
        String distanceText = distance == null ? "射距未知" : distance + " 米";
        Integer count = round.getArrowCount();
        return bow + " · " + distanceText + " · " + (count == null ? "箭数未知" : count + " 支");
    }
}
