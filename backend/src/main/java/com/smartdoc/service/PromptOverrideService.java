package com.smartdoc.service;

import com.smartdoc.entity.PromptOverride;
import com.smartdoc.exception.BusinessException;
import com.smartdoc.mapper.PromptOverrideMapper;
import com.smartdoc.template.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词自定义覆盖服务：
 * 用户可在界面上调整部分提示词（如规则训练的用户提示词），内容按模板名落库到 prompt_override。
 * 取不到覆盖内容时统一回退到 classpath 内置模板。
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PromptOverrideService {

    /**
     * 允许用户自定义的提示词模板名白名单（其余模板不开放覆盖，避免误改核心链路）
     */
    private static final List<String> EDITABLE_KEYS = new ArrayList<>();

    static {
        // 规则训练：变更简报（brief）与 工单/文档审核 各一套用户提示词
        EDITABLE_KEYS.add("rule-training-brief-user");
        EDITABLE_KEYS.add("rule-training-user");
    }

    private final PromptOverrideMapper promptOverrideMapper;

    public List<String> listEditableKeys() {
        return new ArrayList<>(EDITABLE_KEYS);
    }

    /**
     * 取模板当前生效内容：有自定义用自定义，否则用内置模板原文。
     */
    @Transactional(readOnly = true)
    public String getEffectiveContent(String promptKey) {
        String custom = getCustomContent(promptKey);
        return custom != null ? custom : PromptTemplate.raw(promptKey);
    }

    /**
     * 取用户自定义内容；未自定义返回 null。
     */
    @Transactional(readOnly = true)
    public String getCustomContent(String promptKey) {
        validateKey(promptKey);
        PromptOverride record = promptOverrideMapper.findByKey(promptKey);
        if (record == null || record.getContent() == null || record.getContent().trim().isEmpty()) {
            return null;
        }
        return record.getContent();
    }

    /**
     * 保存自定义提示词；内容为空表示恢复默认（删除覆盖记录）。
     */
    public Map<String, Object> save(String promptKey, String content) {
        validateKey(promptKey);
        boolean reset = content == null || content.trim().isEmpty();

        if (reset) {
            promptOverrideMapper.deleteByKey(promptKey);
            log.info("已恢复提示词默认内容: {}", promptKey);
        } else {
            PromptOverride existing = promptOverrideMapper.findByKey(promptKey);
            if (existing == null) {
                PromptOverride record = PromptOverride.builder()
                        .promptKey(promptKey)
                        .content(content)
                        .build();
                promptOverrideMapper.insert(record);
            } else {
                existing.setContent(content);
                promptOverrideMapper.updateById(existing);
            }
            log.info("已保存自定义提示词: {} ({} 字)", promptKey, content.length());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("key", promptKey);
        result.put("isCustom", !reset);
        result.put("content", reset ? PromptTemplate.raw(promptKey) : content);
        return result;
    }

    /**
     * 供业务侧查询：是否存在自定义内容（null = 用内置模板）
     */
    @Transactional(readOnly = true)
    public String findOverrideOrNull(String promptKey) {
        if (!EDITABLE_KEYS.contains(promptKey)) {
            return null;
        }
        return getCustomContent(promptKey);
    }

    private void validateKey(String promptKey) {
        if (promptKey == null || promptKey.trim().isEmpty()) {
            throw new BusinessException("缺少提示词标识 promptKey");
        }
        if (!EDITABLE_KEYS.contains(promptKey.trim())) {
            throw new BusinessException("该提示词不支持自定义：" + promptKey);
        }
        if (!PromptTemplate.exists(promptKey.trim())) {
            throw new BusinessException("提示词模板不存在：" + promptKey);
        }
    }
}
