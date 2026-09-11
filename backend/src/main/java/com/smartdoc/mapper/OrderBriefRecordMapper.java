package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.OrderBriefRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderBriefRecordMapper extends BaseMapper<OrderBriefRecord> {

    @Select("SELECT * FROM order_brief_record WHERE task_id = #{taskId}")
    OrderBriefRecord findByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM order_brief_record WHERE order_id = #{orderId} AND ts = #{ts} ORDER BY created_at DESC LIMIT 1")
    OrderBriefRecord findLatestByOrderIdAndTs(@Param("orderId") String orderId, @Param("ts") String ts);

    @Select("SELECT * FROM order_brief_record WHERE order_id = #{orderId} ORDER BY created_at DESC LIMIT 1")
    OrderBriefRecord findLatestByOrderId(@Param("orderId") String orderId);

    @Select("SELECT * FROM order_brief_record WHERE ts = #{ts} ORDER BY created_at DESC LIMIT 1")
    OrderBriefRecord findLatestByTs(@Param("ts") String ts);

    @Select("SELECT * FROM order_brief_record WHERE status = 'COMPLETED' ORDER BY created_at DESC LIMIT #{limit}")
    java.util.List<OrderBriefRecord> findRecentCompleted(@Param("limit") int limit);

    @Update("UPDATE order_brief_record SET status = 'FAILED', error_message = #{message} WHERE status IN ('PENDING', 'RUNNING')")
    int markUnfinishedTasksFailed(@Param("message") String message);
}
