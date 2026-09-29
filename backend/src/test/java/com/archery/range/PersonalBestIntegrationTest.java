package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.archery.range.domain.Round;
import com.archery.range.dto.RoundDtos;
import com.archery.range.repository.RoundRepository;
import com.archery.range.service.PersonalBestService;
import com.archery.range.service.RoundService;

/**
 * 个人最好成绩端到端集成测试（H2 MySQL 模式加载真实 schema.sql 种子数据）。
 *
 * 验收点：
 * 1. 12 支箭高总分不压过 6 支箭组别的最好；
 * 2. 同组新高分替换旧标记；同分时最早完成的回合保持；
 * 3. 进行中 / 暂停回合被排除；
 * 4. 历史修复只改 personal_best，原始成绩（顺序 / 环值 / 总分 / 平均 / 时间）不变；
 * 5. 弓种为空的历史回合按默认 RECURVE 兼容；
 * 6. 射距未知 / 箭数不完整 / 弓种未知 / 箭数非法 / 关联缺失的异常记录被明确列出，不静默归组；
 * 7. 修复可重复执行，第二次不再改变任何标记。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonalBestIntegrationTest {

    @Autowired
    private RoundService roundService;
    @Autowired
    private PersonalBestService personalBestService;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    // ---------- 1. 种子数据：启动修复后，最好标记按可比组别各自独立 ----------

    @Test
    @Order(1)
    void startupRepair_marksSeedRoundsWithinComparableGroups() {
        // 会员 1：回合 1（10 米 6 支 58）与回合 6（50 米 6 支 58）分属两个组别 ——
        // 不同射距各自比较，50 米组只有回合 6，它必须是最好；10 米组只有回合 1，也仍是最好。
        assertThat(pb(1L)).isTrue();
        assertThat(pb(6L)).isTrue();

        // 会员 4：回合 3（12 支 112，30 米复合）与回合 9（6 支 59，30 米复合）箭数不同，不可比，
        // 两条都应是各自组别的最好 —— 12 支的高总分不能压过 6 支组。
        assertThat(pb(3L)).isTrue();
        assertThat(pb(9L)).isTrue();

        // 会员 2：回合 2（2026-09-16，43 分）与回合 13（2025-09-10，40 分）同弓种同射距同箭数，
        // 高分回合 2 为最好；旧回合 13 不是。
        assertThat(pb(2L)).isTrue();
        assertThat(pb(13L)).isFalse();

        // 进行中回合（10/11/12）启动修复后绝不能被挂标记
        assertThat(pb(10L)).isFalse();
        assertThat(pb(11L)).isFalse();
        assertThat(pb(12L)).isFalse();

        // 9 个可比组别各恰好一条最好（种子数据 SUBMITTED 且完整的回合分属 9 组）
        Long pbCount = jdbcTemplate.queryForObject(
                "select count(*) from rounds where personal_best = 1 and status = 'SUBMITTED'", Long.class);
        assertThat(pbCount).isEqualTo(9L);
    }

    // ---------- 2. 同组同分：保留最早完成的回合，不随提交顺序切换 ----------

    @Test
    @Order(2)
    void sameScore_keepsEarliestCompletedRound() {
        // 会员 10（种子里无任何回合），10 米反曲弓 6 支组：两条都打 55 环
        long roundA = startRound(10L, 1L, 6);
        long roundB = startRound(10L, 1L, 6);
        shootAndSubmit(roundA, List.of("X", "X", "X", "X", "X", "5"));
        shootAndSubmit(roundB, List.of("X", "X", "X", "X", "X", "5"));
        // 人为把 A 的开始时间拨早 1 小时，制造「A 完成更早」的确定场景
        jdbcTemplate.update("update rounds set start_time = ? where id = ?",
                LocalDateTime.now().minusHours(1), roundA);

        personalBestService.repairAll();
        assertThat(pb(roundA)).isTrue();
        assertThat(pb(roundB)).isFalse();

        // 再来一条 55 环的同组回合（第三条同分），最早的 A 依然保持
        long roundB2 = startRound(10L, 1L, 6);
        shootAndSubmit(roundB2, List.of("X", "X", "X", "X", "X", "5"));
        personalBestService.repairAll();
        assertThat(pb(roundA)).isTrue();
        assertThat(pb(roundB)).isFalse();
        assertThat(pb(roundB2)).isFalse();
    }

    // ---------- 3. 同组新高分替换旧标记；12 支组与 6 支组互不干涉 ----------

    @Test
    @Order(3)
    void higherScoreReplaces_andTwelveArrowsNeverBeatsSixArrowGroup() {
        // 会员 12：18 米反曲弓 6 支，先 50 后 58
        long roundF = startRound(12L, 3L, 6);
        long roundG = startRound(12L, 3L, 6);
        shootAndSubmit(roundF, List.of("X", "X", "X", "X", "X", "M")); // 50
        var submittedF = shootAndSubmit(roundG, List.of("X", "X", "X", "X", "X", "8")); // 58
        // 提交即按可比组别判定：新回合成为唯一最好，旧标记被清除
        assertThat(submittedF.round().personalBest()).isTrue();
        assertThat(pb(roundF)).isFalse();
        assertThat(pb(roundG)).isTrue();

        // 再开一条 10 米 12 支、满分 120 的回合：总分远高，但箭数 / 射距都不同，自成一组，
        // 绝不能影响 18 米 6 支组（G 仍是该组唯一最好）
        long roundH = startRound(12L, 1L, 12);
        List<String> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i += 1) {
            twelve.add("X");
        }
        var submittedH = shootAndSubmit(roundH, twelve);
        assertThat(submittedH.round().personalBest()).isTrue();
        assertThat(pb(roundG)).isTrue();
        assertThat(pb(roundH)).isTrue();
    }

    // ---------- 4. 进行中 / 暂停回合不参与比较 ----------

    @Test
    @Order(4)
    void ongoingAndPausedRounds_areExcluded() {
        // 进行中：打了 2 支但不提交
        long ongoingId = startRound(12L, 3L, 6);
        roundService.shoot(ongoingId, new RoundDtos.ShotReq("X"));
        roundService.shoot(ongoingId, new RoundDtos.ShotReq("9"));

        // 暂停（安全联锁）：另起一条并直接置为 PAUSED
        long pausedId = startRound(12L, 3L, 6);
        jdbcTemplate.update("update rounds set status = 'PAUSED' where id = ?", pausedId);

        RoundDtos.PersonalBestRepairReport report = personalBestService.repairAll();
        assertThat(pb(ongoingId)).isFalse();
        assertThat(pb(pausedId)).isFalse();
        assertThat(report.excludedRounds())
                .filteredOn(r -> r.id() == ongoingId)
                .singleElement()
                .extracting(RoundDtos.ExcludedRoundView::exclusionReason)
                .isEqualTo("NOT_SUBMITTED");
        assertThat(report.excludedRounds())
                .filteredOn(r -> r.id() == pausedId)
                .singleElement()
                .extracting(RoundDtos.ExcludedRoundView::exclusionReasonName)
                .asString().contains("暂停");

        // 视图上的参与资格字段
        RoundDtos.RoundView ongoingView = roundService.listByMember(12L).stream()
                .filter(r -> r.id() == ongoingId).findFirst().orElseThrow();
        assertThat(ongoingView.eligible()).isFalse();
        assertThat(ongoingView.exclusionReason()).isEqualTo("NOT_SUBMITTED");
    }

    // ---------- 5. 历史兼容：弓种为空按默认 RECURVE，与该组正常回合一起比较 ----------

    @Test
    @Order(5)
    void legacyBlankBowType_defaultsToRecurve() {
        // 会员 10 在 18 米 6 支组先有一条正常 RECURVE 50 环
        long roundC = startRound(10L, 3L, 6);
        shootAndSubmit(roundC, List.of("X", "X", "X", "X", "X", "M")); // 50

        // 老数据：bow_type 为空字符串，18 米 6 支 40 环，完成时间更早
        long legacyId = insertRawRound(10L, 3L, "", 6, 6, "SUBMITTED", 40,
                List.of("X", "X", "X", "X", "M", "M"), LocalDateTime.now().minusDays(30),
                "R-LEGACY-BLANK-BOW");

        RoundDtos.PersonalBestRepairReport report = personalBestService.repairAll();

        // 空弓种回合按 RECURVE 兼容：可参与，不被列为异常
        assertThat(report.excludedRounds()).noneMatch(r -> r.id() == legacyId);
        RoundDtos.RoundView legacyView = roundService.listByMember(10L).stream()
                .filter(r -> r.id() == legacyId).findFirst().orElseThrow();
        assertThat(legacyView.eligible()).isTrue();
        assertThat(legacyView.groupLabel()).contains("反曲弓").contains("18 米").contains("6 支");

        // 它分低（40 < 50），不是最好；正常的 50 环回合才是该组唯一最好
        assertThat(pb(legacyId)).isFalse();
        assertThat(pb(roundC)).isTrue();
    }

    // ---------- 6. 异常历史记录：保留成绩、清除残留标记，并在报告中明确列出 ----------

    @Test
    @Order(6)
    void abnormalRecords_listedExplicitly_andStaleMarksCleared() {
        // 6.1 箭数不完整：规定 6 支只有 4 支，且残留旧口径最好标记 —— 修复必须清除
        long incompleteId = insertRawRound(10L, 1L, "RECURVE", 6, 4, "SUBMITTED", 36,
                List.of("9", "9", "9", "9"), LocalDateTime.now().minusDays(2), "R-BAD-INCOMPLETE");
        jdbcTemplate.update("update rounds set personal_best = 1 where id = ?", incompleteId);

        // 6.2 弓种无法识别
        long unknownBowId = insertRawRound(10L, 1L, "LONGBOW", 6, 6, "SUBMITTED", 48,
                List.of("8", "8", "8", "8", "8", "8"), LocalDateTime.now().minusDays(2), "R-BAD-BOW");

        // 6.3 规定箭数非法（7 支，哪怕打满也不可比）
        long badCountId = insertRawRound(10L, 1L, "RECURVE", 7, 7, "SUBMITTED", 49,
                List.of("7", "7", "7", "7", "7", "7", "7"), LocalDateTime.now().minusDays(2), "R-BAD-COUNT");

        // 6.4 射距无法确定：箭道距离 25 米（不在 10/18/30/50）
        jdbcTemplate.update(
                "insert into lanes (id, lane_no, distance, target_type, status, hourly_price) "
                        + "values (900, 'T25', 25, '纸质靶', 'OPEN', 60.00)");
        long unknownDistanceId = insertRawRound(10L, 900L, "RECURVE", 6, 6, "SUBMITTED", 48,
                List.of("8", "8", "8", "8", "8", "8"), LocalDateTime.now().minusDays(2), "R-BAD-DIST");

        // 6.5 / 6.6 关联对象缺失：会员或箭道外键悬空（临时关闭 H2 引用完整性插入）
        long[] danglingIds = new long[2];
        transactionTemplate.executeWithoutResult(s -> {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
            // 外键悬空时仍可由自增列生成 id（引用完整性已关）
            danglingIds[0] = insertRoundRow(999999L, 1L, "RECURVE", 6, 48,
                    "SUBMITTED", LocalDateTime.now().minusDays(2));
            insertArrows(danglingIds[0], List.of("8", "8", "8", "8", "8", "8"));
            danglingIds[1] = insertRoundRow(10L, 999998L, "RECURVE", 6, 48,
                    "SUBMITTED", LocalDateTime.now().minusDays(2));
            insertArrows(danglingIds[1], List.of("8", "8", "8", "8", "8", "8"));
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        });
        long missingMemberId = danglingIds[0];
        long missingLaneId = danglingIds[1];

        RoundDtos.PersonalBestRepairReport report = personalBestService.repairAll();

        assertReason(report, incompleteId, "ARROWS_INCOMPLETE");
        assertReason(report, unknownBowId, "BOW_TYPE_UNKNOWN");
        assertReason(report, badCountId, "GROUP_SIZE_INVALID");
        assertReason(report, unknownDistanceId, "DISTANCE_UNKNOWN");
        assertReason(report, missingMemberId, "MEMBER_MISSING");
        assertReason(report, missingLaneId, "LANE_MISSING");

        // 异常回合一律无最好标记（残留标记被清除），原始成绩仍保留
        assertThat(pb(incompleteId)).isFalse();
        Integer totalKept = jdbcTemplate.queryForObject(
                "select total_score from rounds where id = ?", Integer.class, incompleteId);
        assertThat(totalKept).isEqualTo(36);
    }

    // ---------- 7. 修复幂等 + 原始数据不变 ----------

    @Test
    @Order(7)
    void repair_isIdempotent_andOriginalDataUntouched() {
        // 修复前快照：除 personal_best 外的全部回合字段 + 箭支连接顺序 + 环值
        List<Map<String, Object>> roundsSnapshot = snapshot(
                "select id, round_no, member_id, lane_id, bow_type, start_time, arrow_count, "
                        + "total_score, average_score, status from rounds order by id");
        List<Map<String, Object>> linkSnapshot = snapshot(
                "select round_id, arrow_id, shot_index from round_arrow order by round_id, shot_index");
        List<Map<String, Object>> arrowSnapshot = snapshot(
                "select id, ring, ring_value from arrow_score order by id");

        RoundDtos.PersonalBestRepairReport first = personalBestService.repairAll();
        List<Long> pbAfterFirst = pbSnapshot();

        RoundDtos.PersonalBestRepairReport second = personalBestService.repairAll();
        List<Long> pbAfterSecond = pbSnapshot();

        // 第二次执行：没有任何标记翻转，最好集合完全一致
        assertThat(second.changedMarks()).isZero();
        assertThat(pbAfterSecond).isEqualTo(pbAfterFirst);
        assertThat(second.personalBestCount()).isEqualTo(first.personalBestCount());
        assertThat(second.excludedCount()).isEqualTo(first.excludedCount());

        // 原始成绩字段、箭支顺序、环值逐行一致
        assertThat(snapshot(
                "select id, round_no, member_id, lane_id, bow_type, start_time, arrow_count, "
                        + "total_score, average_score, status from rounds order by id"))
                .isEqualTo(roundsSnapshot);
        assertThat(snapshot("select round_id, arrow_id, shot_index from round_arrow "
                + "order by round_id, shot_index")).isEqualTo(linkSnapshot);
        assertThat(snapshot("select id, ring, ring_value from arrow_score order by id"))
                .isEqualTo(arrowSnapshot);
    }

    // ================= 辅助方法 =================

    private boolean pb(Long id) {
        return transactionTemplate.execute(s -> {
            Round round = roundRepository.findById(id).orElseThrow();
            return Boolean.TRUE.equals(round.getPersonalBest());
        });
    }

    private long startRound(long memberId, long laneId, int arrowCount) {
        return transactionTemplate.execute(s -> roundService.start(
                new RoundDtos.RoundStartReq(memberId, laneId, "RECURVE", arrowCount)).round().id());
    }

    private RoundDtos.RoundDetailView shootAndSubmit(long id, List<String> rings) {
        return transactionTemplate.execute(s -> {
            for (String ring : rings) {
                roundService.shoot(id, new RoundDtos.ShotReq(ring));
            }
            return roundService.submit(id);
        });
    }

    private void assertReason(RoundDtos.PersonalBestRepairReport report, long roundId, String reason) {
        assertThat(report.excludedRounds())
                .as("异常回合 %d 必须在报告中列出", roundId)
                .filteredOn(r -> r.id() == roundId)
                .singleElement()
                .extracting(RoundDtos.ExcludedRoundView::exclusionReason)
                .isEqualTo(reason);
    }

    private List<Long> pbSnapshot() {
        return jdbcTemplate.queryForList(
                "select id from rounds where personal_best = 1 order by id", Long.class);
    }

    private List<Map<String, Object>> snapshot(String sql) {
        return jdbcTemplate.queryForList(sql);
    }

    /** 直接插一条原始回合（含箭支连接），用于构造历史 / 异常数据；id 由自增列生成 */
    private long insertRawRound(long memberId, long laneId, String bowType, int arrowCount, int actualShots,
            String status, int total, List<String> rings, LocalDateTime startTime, String roundNo) {
        long id = insertRoundRow(memberId, laneId, bowType, arrowCount, total, status, startTime);
        insertArrows(id, rings.subList(0, actualShots));
        return id;
    }

    private long insertRoundRow(long memberId, long laneId, String bowType,
            int arrowCount, int total, String status, LocalDateTime startTime) {
        // round_no 有唯一约束：用时间戳 + 自增主键保证唯一
        java.sql.Timestamp ts = java.sql.Timestamp.valueOf(startTime);
        org.springframework.jdbc.support.KeyHolder holder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "insert into rounds (round_no, member_id, lane_id, bow_type, start_time, arrow_count, "
                            + "total_score, average_score, personal_best, status) values (?,?,?,?,?,?,?,?,0,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "R" + System.nanoTime());
            ps.setLong(2, memberId);
            ps.setLong(3, laneId);
            ps.setString(4, bowType);
            ps.setTimestamp(5, ts);
            ps.setInt(6, arrowCount);
            ps.setInt(7, total);
            ps.setBigDecimal(8, java.math.BigDecimal.valueOf(total)
                    .divide(java.math.BigDecimal.valueOf(arrowCount), 2, java.math.RoundingMode.HALF_UP));
            ps.setString(9, status);
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    private void insertArrows(long roundId, List<String> rings) {
        int index = 0;
        for (String ring : rings) {
            int value = "X".equals(ring) || "10".equals(ring) ? 10 : "M".equals(ring) ? 0
                    : Integer.parseInt(ring);
            jdbcTemplate.update("insert into arrow_score (ring, ring_value) values (?, ?)", ring, value);
            Long arrowId = jdbcTemplate.queryForObject("select max(id) from arrow_score", Long.class);
            jdbcTemplate.update("insert into round_arrow (round_id, arrow_id, shot_index) values (?,?,?)",
                    roundId, arrowId, index);
            index += 1;
        }
    }
}
