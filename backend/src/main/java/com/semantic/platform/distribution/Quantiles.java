package com.semantic.platform.distribution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 精确分位数（唯一约定定义，全平台统一）：
 * <p>样本升序 x(0)..x(n-1)，q 分位位置 h = q·(n-1)（0 基），
 * i = floor(h)，f = h - i，值 = x(i) + f·(x(i+1) - x(i))。
 * 即 type-7 线性插值（与 Excel PERCENTILE.INC / numpy 默认一致）。
 * 中位数 = q(0.5)，P90 = q(0.9)。
 */
public final class Quantiles {

    private Quantiles() {}

    /** q 分位数；sortedAsc 必须已升序。空样本返回 null。结果标度 4（HALF_UP）。 */
    public static BigDecimal quantile(List<BigDecimal> sortedAsc, double q) {
        int n = sortedAsc.size();
        if (n == 0) return null;
        if (q <= 0) return sortedAsc.get(0);
        if (q >= 1) return sortedAsc.get(n - 1);
        double h = q * (n - 1);
        int i = (int) Math.floor(h);
        double f = h - i;
        BigDecimal lo = sortedAsc.get(i);
        BigDecimal hi = sortedAsc.get(Math.min(i + 1, n - 1));
        return lo.add(hi.subtract(lo).multiply(BigDecimal.valueOf(f)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal median(List<BigDecimal> sortedAsc) {
        return quantile(sortedAsc, 0.5);
    }

    public static BigDecimal p90(List<BigDecimal> sortedAsc) {
        return quantile(sortedAsc, 0.9);
    }

    /** 高额订单占比：net >= threshold 的样本数 / 有效样本数。空样本返回 null。 */
    public static BigDecimal highRatio(List<BigDecimal> sortedAsc, BigDecimal threshold) {
        int n = sortedAsc.size();
        if (n == 0) return null;
        long high = sortedAsc.stream().filter(v -> v.compareTo(threshold) >= 0).count();
        return BigDecimal.valueOf(high)
                .divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
    }
}
