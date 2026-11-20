package com.semantic.platform.distribution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 精确分位数定义测试：type-7 线性插值（h = q·(n-1)）。
 */
class QuantilesTest {

    @Test
    void medianOddCount() {
        List<BigDecimal> s = List.of(bd("10"), bd("20"), bd("30"), bd("40"), bd("50"));
        assertEquals(0, new BigDecimal("30.0000").compareTo(Quantiles.median(s)));
    }

    @Test
    void medianEvenCountInterpolates() {
        List<BigDecimal> s = List.of(bd("10"), bd("20"), bd("30"), bd("40"));
        // h = 0.5·3 = 1.5 → 20 + 0.5·(30-20) = 25
        assertEquals(0, new BigDecimal("25.0000").compareTo(Quantiles.median(s)));
    }

    @Test
    void p90Interpolates() {
        // n=11, h = 0.9·10 = 9 → 第 9 个（0 基）= 100
        List<BigDecimal> s = List.of(bd("10"), bd("20"), bd("30"), bd("40"), bd("50"), bd("60"),
                bd("70"), bd("80"), bd("90"), bd("100"), bd("110"));
        assertEquals(0, new BigDecimal("100.0000").compareTo(Quantiles.p90(s)));
    }

    @Test
    void p90FractionalPosition() {
        // n=5, h = 0.9·4 = 3.6 → 40 + 0.6·(50-40) = 46
        List<BigDecimal> s = List.of(bd("10"), bd("20"), bd("30"), bd("40"), bd("50"));
        assertEquals(0, new BigDecimal("46.0000").compareTo(Quantiles.p90(s)));
    }

    @Test
    void emptyReturnsNull() {
        assertNull(Quantiles.median(List.of()));
        assertNull(Quantiles.highRatio(List.of(), bd("100")));
    }

    @Test
    void highRatioCountsAboveThreshold() {
        List<BigDecimal> s = List.of(bd("10"), bd("20"), bd("1000"), bd("2000"));
        // >= 1000 的有 2 个 → 0.5
        assertEquals(0, new BigDecimal("0.500000").compareTo(Quantiles.highRatio(s, bd("1000"))));
    }

    @Test
    void zeroAmountIncludedAsObservation() {
        // 零金额规则：0.00 观察值计入分布，拉低中位数
        List<BigDecimal> s = List.of(bd("0"), bd("0"), bd("100"));
        assertEquals(0, new BigDecimal("0.0000").compareTo(Quantiles.median(s)));
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
