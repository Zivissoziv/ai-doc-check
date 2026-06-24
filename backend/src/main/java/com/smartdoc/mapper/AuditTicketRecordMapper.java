package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.AuditTicketRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditTicketRecordMapper extends BaseMapper<AuditTicketRecord> {

    @Select("SELECT * FROM audit_ticket_record WHERE ticket_id = #{ticketId} AND ts = #{ts} LIMIT 1")
    AuditTicketRecord findByTicketIdAndTs(@Param("ticketId") String ticketId, @Param("ts") String ts);

    @Select("SELECT * FROM audit_ticket_record WHERE task_id = #{taskId} LIMIT 1")
    AuditTicketRecord findByTaskId(@Param("taskId") String taskId);
}
