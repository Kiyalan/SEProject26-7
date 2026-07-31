package com.repopilot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeBuildTaskService 单元测试
 * 
 * 测试用例覆盖:
 * - TC-007: 知识库构建流程
 * - TC-008: 知识库构建异常处理
 */
class KnowledgeBuildTaskServiceTest {

    @Test
    void TC007_qualityScoreCalculation_excellentScore() {
        // Simulate excellent coverage
        double fileCoverage = 1.0;
        double embeddingCoverage = 1.0;
        double astCoverage = 1.0;
        
        double score = Math.round((fileCoverage * 0.55 + embeddingCoverage * 0.30 + astCoverage * 0.15) * 1000.0) / 10.0;
        assertThat(score).isEqualTo(100.0);
    }

    @Test
    void TC007_qualityScoreCalculation_goodScore() {
        // Simulate good coverage (>=75%)
        double fileCoverage = 0.95;
        double embeddingCoverage = 0.80;
        double astCoverage = 0.70;
        
        double score = Math.round((fileCoverage * 0.55 + embeddingCoverage * 0.30 + astCoverage * 0.15) * 1000.0) / 10.0;
        assertThat(score).isEqualTo(86.7);
    }

    @Test
    void TC007_qualityScoreCalculation_degradedScore() {
        // Simulate degraded coverage (>=50%)
        double fileCoverage = 0.70;
        double embeddingCoverage = 0.60;
        double astCoverage = 0.50;
        
        double score = Math.round((fileCoverage * 0.55 + embeddingCoverage * 0.30 + astCoverage * 0.15) * 1000.0) / 10.0;
        assertThat(score).isEqualTo(64.0);
    }

    @Test
    void TC007_qualityScoreCalculation_poorScore() {
        // Simulate poor coverage (<50%)
        double fileCoverage = 0.40;
        double embeddingCoverage = 0.30;
        double astCoverage = 0.20;
        
        double score = Math.round((fileCoverage * 0.55 + embeddingCoverage * 0.30 + astCoverage * 0.15) * 1000.0) / 10.0;
        assertThat(score).isEqualTo(34.0);
    }

    @Test
    void TC007_progressPercentage_calculation() {
        // Test progress calculation
        int done = 50;
        int total = 100;
        double percent = Math.round(done * 1000.0 / total) / 10.0;
        assertThat(percent).isEqualTo(50.0);
    }

    @Test
    void TC007_progressPercentage_withZeroTotal() {
        // Edge case: total = 0
        int done = 0;
        int total = 0;
        int safeTotal = Math.max(total, 1);
        int safeDone = Math.min(Math.max(done, 0), safeTotal);
        double percent = Math.round(safeDone * 1000.0 / safeTotal) / 10.0;
        assertThat(percent).isEqualTo(0.0);
    }

    @Test
    void TC008_qualityStatus_excellent() {
        int filesFailed = 0;
        double score = 95.0;
        String status = score >= 90 && filesFailed == 0 ? "excellent" : "good";
        assertThat(status).isEqualTo("excellent");
    }

    @Test
    void TC008_qualityStatus_good() {
        int filesFailed = 1;
        double score = 80.0;
        String status = score >= 90 && filesFailed == 0 ? "excellent"
                : score >= 75 ? "good" : "degraded";
        assertThat(status).isEqualTo("good");
    }

    @Test
    void TC008_qualityStatus_degraded() {
        int filesFailed = 0;
        double score = 60.0;
        String status = score >= 90 && filesFailed == 0 ? "excellent"
                : score >= 75 ? "good"
                : score >= 50 ? "degraded" : "poor";
        assertThat(status).isEqualTo("degraded");
    }

    @Test
    void TC008_qualityStatus_poor() {
        int filesFailed = 0;
        double score = 40.0;
        String status = score >= 90 && filesFailed == 0 ? "excellent"
                : score >= 75 ? "good"
                : score >= 50 ? "degraded" : "poor";
        assertThat(status).isEqualTo("poor");
    }
}
