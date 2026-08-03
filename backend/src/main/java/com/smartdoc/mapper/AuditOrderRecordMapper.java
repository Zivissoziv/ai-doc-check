package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.AuditOrderRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AuditOrderRecordMapper extends BaseMapper<AuditOrderRecord> {

    @Select("SELECT * FROM audit_order_record WHERE order_id = #{orderId} AND ts = #{ts} ORDER BY created_at DESC, id DESC LIMIT 1")
    AuditOrderRecord findByOrderIdAndTs(@Param("orderId") String orderId, @Param("ts") String ts);

    @Select("SELECT * FROM audit_order_record WHERE order_id = #{orderId} AND ts = #{ts} ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE")
    AuditOrderRecord findLatestByOrderIdAndTsForUpdate(@Param("orderId") String orderId, @Param("ts") String ts);

    @Select("SELECT * FROM audit_order_record WHERE task_id = #{taskId} LIMIT 1")
    AuditOrderRecord findByTaskId(@Param("taskId") String taskId);

    @Select({
            "<script>",
            "SELECT * FROM audit_order_record WHERE audit_batch_no IN",
            "<foreach collection='batchNos' item='batchNo' open='(' separator=',' close=')'>",
            "#{batchNo}",
            "</foreach>",
            "ORDER BY created_at DESC, id DESC",
            "</script>"
    })
    List<AuditOrderRecord> findByBatchNos(@Param("batchNos") List<String> batchNos);

    @Select("SELECT * FROM audit_order_record WHERE status IN ('PENDING', 'RUNNING') AND task_id IS NOT NULL")
    List<AuditOrderRecord> findUnfinishedAsyncTasks();

    @Update("UPDATE audit_order_record SET status = 'FAILED', error_message = #{errorMessage} WHERE status IN ('PENDING', 'RUNNING') AND task_id IS NOT NULL")
    int markUnfinishedTasksFailed(@Param("errorMessage") String errorMessage);
}
