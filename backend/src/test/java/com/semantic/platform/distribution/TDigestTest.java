package com.semantic.platform.distribution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * t-digest 近似摘要测试：精度对照精确路线、可合并性、存储有界、误差界有效性。
 * 误差验证方式：对同一批样本，比较近似分位数值的真实排名是否落在摘要给出的
 * 结构误差界 [rankLo, rankHi] 内。
 */
class TDigestTest {

    /** 长尾分布：900 笔小额（10~100）+ 100 笔大额团购（1000~100000），贴近客单金额形态。 */
    private static List<Double> longTail(int seed) {
        Random rnd = new Random(seed);
        List<Double> data = new ArrayList<>();
        for (int i = 0; i < 900; i++) data.add(10 + rnd.nextDouble() * 90);
        for (int i = 0; i < 100; i++) data.add(1000 + rnd.nextDouble() * 99000);
        return data;
    }

    private static TDigest digestOf(List<Double> data) {
        TDigest d = new TDigest();
        data.forEach(d::add);
        return d;
    }

    /** 真实排名：样本中 <= v 的数量。 */
    private static double trueRankLe(List<Double> sorted, double v) {
        double n = 0;
        for (double x : sorted) if (x <= v) n++;
        return n;
    }

    @Test
    void quantileWithinReportedBound_longTail() {
        List<Double> data = longTail(42);
        List<Double> sorted = new ArrayList<>(data);
        sorted.sort(Double::compareTo);
        TDigest d = digestOf(data);
        double n = data.size();
        for (double q : new double[]{0.5, 0.9, 0.95, 0.99}) {
            TDigest.Estimate est = d.quantileEstimate(q);
            double trueRank = trueRankLe(sorted, est.value());
            assertTrue(trueRank >= est.rankLo() - 1e-9 && trueRank <= est.rankHi() + 1e-9,
                    "q=" + q + " 真实排名 " + trueRank + " 应在界内 [" + est.rankLo() + "," + est.rankHi() + "]");
            // 与精确值对照：排名偏差不超过报告的误差界
            assertTrue(Math.abs(trueRank / n - q) <= est.rankErrorBound() + 0.01,
                    "q=" + q + " 排名偏差超过误差界 " + est.rankErrorBound());
        }
    }

    @Test
    void quantileCloseToExact_uniform() {
        Random rnd = new Random(7);
        List<Double> data = new ArrayList<>();
        for (int i = 0; i < 5000; i++) data.add(rnd.nextDouble() * 1000);
        TDigest d = digestOf(data);
        List<BigDecimal> sorted = data.stream().map(BigDecimal::valueOf).sorted().toList();
        for (double q : new double[]{0.5, 0.9}) {
            double exact = Quantiles.quantile(sorted, q).doubleValue();
            double approx = d.quantileEstimate(q).value();
            // 均匀分布下误差应远小于 1% 量程
            assertTrue(Math.abs(approx - exact) < 15, "q=" + q + " approx=" + approx + " exact=" + exact);
        }
    }

    @Test
    void mergeEquivalentToUnion() {
        List<Double> data = longTail(99);
        List<Double> sorted = new ArrayList<>(data);
        sorted.sort(Double::compareTo);
        // 模拟按 门店×周×渠道 分片建摘要后合并
        List<TDigest> shards = new ArrayList<>();
        for (int i = 0; i < 7; i++) shards.add(new TDigest());
        for (int i = 0; i < data.size(); i++) shards.get(i % 7).add(data.get(i));
        TDigest merged = new TDigest();
        shards.forEach(merged::merge);
        assertEquals(data.size(), merged.size(), "合并后样本数守恒");
        double n = data.size();
        // 合并摘要与整体构建摘要的契约相同：估计值的真实排名落在严格界内
        for (double q : new double[]{0.5, 0.9, 0.99}) {
            TDigest.Estimate est = merged.quantileEstimate(q);
            double trueRank = trueRankLe(sorted, est.value());
            assertTrue(trueRank >= est.rankLo() - 1e-9 && trueRank <= est.rankHi() + 1e-9,
                    "q=" + q + " 合并摘要真实排名 " + trueRank + " 应在界内 [" + est.rankLo() + "," + est.rankHi() + "]");
            assertTrue(Math.abs(trueRank / n - q) <= est.rankErrorBound() + 0.01,
                    "q=" + q + " 合并摘要排名偏差超过误差界 " + est.rankErrorBound());
        }
    }

    @Test
    void storageIsBounded() {
        TDigest d = new TDigest();
        Random rnd = new Random(1);
        for (int i = 0; i < 100_000; i++) d.add(rnd.nextDouble() * 1_000_000);
        assertTrue(d.centroidCount() <= 120,
                "质心数应有界（实际 " + d.centroidCount() + "）");
    }

    @Test
    void highRatioEstimateConsistentWithExact() {
        List<Double> data = longTail(2024);
        TDigest d = digestOf(data);
        double threshold = 5000;
        long trueHigh = data.stream().filter(x -> x >= threshold).count();
        double trueRatio = (double) trueHigh / data.size();
        TDigest.Estimate est = d.highRatioEstimate(threshold);
        assertTrue(Math.abs(est.value() - trueRatio) <= est.rankErrorBound() + 0.005,
                "高额占比估计 " + est.value() + " 与真实 " + trueRatio + " 之差超过误差界 " + est.rankErrorBound());
        // 界应包住真实占比
        assertTrue(trueRatio >= est.rankLo() - 1e-9 && trueRatio <= est.rankHi() + 1e-9,
                "真实占比 " + trueRatio + " 应在界内 [" + est.rankLo() + "," + est.rankHi() + "]");
    }

    @Test
    void jsonRoundTripPreservesEstimates() {
        TDigest d = digestOf(longTail(5));
        TDigest restored = TDigest.fromJson(d.toJson());
        assertEquals(d.size(), restored.size());
        assertEquals(d.quantileEstimate(0.5).value(), restored.quantileEstimate(0.5).value(), 1e-9);
        assertEquals(d.quantileEstimate(0.9).value(), restored.quantileEstimate(0.9).value(), 1e-9);
        assertEquals(d.min(), restored.min(), 1e-9);
        assertEquals(d.max(), restored.max(), 1e-9);
    }

    @Test
    void minMaxExactAtExtremes() {
        TDigest d = digestOf(longTail(3));
        List<Double> sorted = new ArrayList<>(longTail(3));
        sorted.sort(Double::compareTo);
        assertEquals(sorted.get(0), d.quantileEstimate(0).value(), 1e-9);
        assertEquals(sorted.get(sorted.size() - 1), d.quantileEstimate(1).value(), 1e-9);
    }
}
