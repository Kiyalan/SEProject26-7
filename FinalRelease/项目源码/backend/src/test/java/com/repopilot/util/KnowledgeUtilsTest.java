package com.repopilot.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeUtilsTest {

    @Test
    void classifyQuestion_where_chinese() {
        assertThat(KnowledgeUtils.classifyQuestion("文件在哪里")).isEqualTo("where");
        assertThat(KnowledgeUtils.classifyQuestion("代码在哪")).isEqualTo("where");
        assertThat(KnowledgeUtils.classifyQuestion("在哪里配置")).isEqualTo("where");
    }

    @Test
    void classifyQuestion_where_english() {
        assertThat(KnowledgeUtils.classifyQuestion("Where is the config")).isEqualTo("where");
        assertThat(KnowledgeUtils.classifyQuestion("where is it")).isEqualTo("where");
    }

    @Test
    void classifyQuestion_how_chinese() {
        assertThat(KnowledgeUtils.classifyQuestion("如何部署")).isEqualTo("how");
        assertThat(KnowledgeUtils.classifyQuestion("怎么运行")).isEqualTo("how");
        assertThat(KnowledgeUtils.classifyQuestion("怎样配置")).isEqualTo("how");
    }

    @Test
    void classifyQuestion_how_english() {
        assertThat(KnowledgeUtils.classifyQuestion("How to deploy")).isEqualTo("how");
        assertThat(KnowledgeUtils.classifyQuestion("how does it work")).isEqualTo("how");
    }

    @Test
    void classifyQuestion_what_default() {
        assertThat(KnowledgeUtils.classifyQuestion("这是什么")).isEqualTo("what");
        assertThat(KnowledgeUtils.classifyQuestion("项目是什么")).isEqualTo("what");
        assertThat(KnowledgeUtils.classifyQuestion("What is this")).isEqualTo("what");
    }

    @Test
    void classifyQuestion_null_returns_what() {
        assertThat(KnowledgeUtils.classifyQuestion(null)).isEqualTo("what");
    }

    @Test
    void classifyQuestion_empty_returns_what() {
        assertThat(KnowledgeUtils.classifyQuestion("")).isEqualTo("what");
    }
}
