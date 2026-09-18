package com.smartdoc.controller;

import com.smartdoc.service.PromptOverrideService;
import com.smartdoc.template.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词自定义接口：读取/保存支持自定义的提示词（当前为规则训练的用户提示词）。
 * 内容为空表示恢复内置默认。
 */
@Slf4j
@RestController
@RequestMapping("/api/config/prompts")
@RequiredArgsConstructor
public class PromptOverrideController {

    private final PromptOverrideService promptOverrideService;

    /**
     * 可自定义的提示词清单
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listEditablePrompts() {
        Map<String, Object> response = new HashMap<>();
        response.put("keys", promptOverrideService.listEditableKeys());
        return ResponseEntity.ok(response);
    }

    /**
     * 取提示词当前内容（自定义优先，否则内置默认）+ 默认内容 + 可用占位符
     */
    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> getPrompt(@PathVariable String key) {
        String promptKey = key == null ? null : key.trim();
        String custom = promptOverrideService.getCustomContent(promptKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("key", promptKey);
        response.put("isCustom", custom != null);
        response.put("content", custom != null ? custom : PromptTemplate.raw(promptKey));
        response.put("defaultContent", PromptTemplate.raw(promptKey));
        response.put("placeholders", buildPlaceholders(promptKey));
        return ResponseEntity.ok(response);
    }

    /**
     * 保存自定义提示词；content 为空/空白 = 恢复默认
     */
    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> savePrompt(@PathVariable String key,
                                                          @RequestBody Map<String, Object> body) {
        Object content = body == null ? null : body.get("content");
        String text = content == null ? null : String.valueOf(content);
        return ResponseEntity.ok(promptOverrideService.save(key == null ? null : key.trim(), text));
    }

    /**
     * 占位符说明：这些占位符在生成时会被实际内容替换，用户可以调整位置但应保留需要的占位符。
     */
    private List<Map<String, String>> buildPlaceholders(String promptKey) {
        List<Map<String, String>> placeholders = new ArrayList<>();
        if (promptKey == null || promptKey.startsWith("rule-training")) {
            placeholders.add(placeholder("{reviewReport}", "用户粘贴的人类审核报告 / 评审经验原文"));
            placeholders.add(placeholder("{existingRules}", "当前规则组已有规则（用于判断重复）"));
            placeholders.add(placeholder("{auditScope}", "审核范围：brief / ticket / document"));
        }
        return placeholders;
    }

    private Map<String, String> placeholder(String name, String desc) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("desc", desc);
        return item;
    }
}
