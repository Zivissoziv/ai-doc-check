package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.AuditDailyStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AuditDailyStatsMapper extends BaseMapper<AuditDailyStats> {

    @Select("SELECT * FROM audit_daily_stats WHERE stat_date >= #{startDate} ORDER BY stat_date ASC")
    List<AuditDailyStats> findSince(@Param("startDate") LocalDate startDate);

    @Select("SELECT * FROM audit_daily_stats WHERE stat_date >= #{startDate} AND stat_date <= #{endDate} ORDER BY stat_date ASC")
    List<AuditDailyStats> findBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Select("SELECT * FROM audit_daily_stats WHERE stat_date = #{date}")
    AuditDailyStats findByDate(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(SUM(count), 0) FROM audit_daily_stats WHERE stat_date >= #{startDate} AND stat_date <= #{endDate}")
    Long sumCountBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Select("SELECT COALESCE(SUM(count), 0) FROM audit_daily_stats WHERE stat_date = #{date}")
    Long sumCountByDate(@Param("date") LocalDate date);
}
