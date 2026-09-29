package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.Round;

import jakarta.persistence.LockModeType;

public interface RoundRepository extends JpaRepository<Round, Long> {

    List<Round> findAllByOrderByIdAsc();

    List<Round> findAllByOrderByStartTimeDesc();

    List<Round> findByMemberIdOrderByStartTimeDesc(Long memberId);

    List<Round> findByLaneIdOrderByStartTimeDesc(Long laneId);

    List<Round> findByStatusOrderByStartTimeDesc(String status);

    List<Round> findByMemberIdAndStatusOrderByStartTimeDesc(Long memberId, String status);

    boolean existsByRoundNo(String roundNo);

    long countByMemberId(Long memberId);

    long countByStatus(String status);

    /** 某会员当前拥有的个人最好数（按可比组别各最多一条） */
    long countByMemberIdAndPersonalBestTrue(Long memberId);

    /** 悲观写锁：记箭 / 撤销 / 提交与安全联锁的暂停、恢复互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Round r where r.id = :id")
    Optional<Round> findByIdForUpdate(@Param("id") Long id);

    /** 悲观写锁：停射事件批量暂停受影响箭道上进行中的回合 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Round r where r.lane.id in :laneIds and r.status = 'ONGOING' order by r.id")
    List<Round> findOngoingByLaneIdsForUpdate(@Param("laneIds") List<Long> laneIds);

    /**
     * 悲观写锁：个人最好判定时锁定该会员的全部回合行。
     * 可比组别以会员为首维，同一会员并发提交两个回合时在此串行，避免同组并发出两条最好标记。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Round r where r.member.id = :memberId order by r.id")
    List<Round> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
