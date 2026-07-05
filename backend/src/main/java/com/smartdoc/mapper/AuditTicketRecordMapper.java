package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.AuditTicketRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AuditTicketRecordMapper extends BaseMapper<AuditTicketRecord> {

    @Select("SELECT * FROM audit_ticket_record WHERE ticket_id = #{ticketId} AND ts = #{ts} ORDER BY created_at DESC, id DESC LIMIT 1")
    AuditTicketRecord findByTicketIdAndTs(@Param("ticketId") String ticketId, @Param("ts") String ts);

    @Select("SELECT * FROM audit_ticket_record WHERE ticket_id = #{ticketId} AND ts = #{ts} ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE")
    AuditTicketRecord findLatestByTicketIdAndTsForUpdate(@Param("ticketId") String ticketId, @Param("ts") String ts);

    @Select("SELECT * FROM audit_ticket_record WHERE task_id = #{taskId} LIMIT 1")
    AuditTicketRecord findByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM audit_ticket_record WHERE status IN ('PENDING', 'RUNNING') AND task_id IS NOT NULL")
    List<AuditTicketRecord> findUnfinishedAsyncTasks();

    @Update("UPDATE audit_ticket_record SET status = 'FAILED', error_message = #{errorMessage} WHERE status IN ('PENDING', 'RUNNING') AND task_id IS NOT NULL")
    int markUnfinishedTasksFailed(@Param("errorMessage") String errorMessage);
}
