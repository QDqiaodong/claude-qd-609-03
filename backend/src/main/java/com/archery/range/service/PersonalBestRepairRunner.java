package com.archery.range.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.dto.RoundDtos;

/**
 * 升级后自动修复历史个人最好标记：服务启动、JPA 就绪后按可比组别重算一遍。
 *
 * 修复逻辑幂等（{@link PersonalBestService#repairAll()}）：只翻转 personal_best，
 * 多次启动结果一致；稳态后 changedMarks 恒为 0，刷新页面或重启服务标记都不会变化。
 * 也可通过 POST /api/rounds/personal-bests/repair 手动重复执行。
 */
@Component
@Order(0)
public class PersonalBestRepairRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PersonalBestRepairRunner.class);

    private final PersonalBestService personalBestService;

    public PersonalBestRepairRunner(PersonalBestService personalBestService) {
        this.personalBestService = personalBestService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        RoundDtos.PersonalBestRepairReport report = personalBestService.repairAll();
        log.info("个人最好成绩历史修复完成：回合 {}（可参与 {}，可比组别 {}，最好标记 {}，翻转 {}，排除 {}）",
                report.totalRounds(), report.eligibleRounds(), report.groupCount(),
                report.personalBestCount(), report.changedMarks(), report.excludedCount());
        for (RoundDtos.ExcludedRoundView excluded : report.excludedRounds()) {
            log.warn("回合 {}（会员 id={}）不参与个人最好比较：{}",
                    excluded.roundNo(), excluded.memberId(), excluded.exclusionReasonName());
        }
    }
}
