package com.semantic.platform.distribution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 可合并近似分位摘要（t-digest，k1 尺度函数，压缩参数 δ）。
 * <p>性质：
 * <ul>
 *   <li>可合并：两个摘要的质心直接合并再压缩，结果与对并集样本构建同阶误差；</li>
 *   <li>存储有界：压缩后质心数 ≤ δ/2 + 缓冲（δ=100 时约 50 个）；</li>
 *   <li>误差严格可界：每个质心记录其样本的 [min,max] 覆盖范围，
 *       分位数/占比结果附带的排名误差界 [rankLo, rankHi] 由质心实际覆盖范围推出——
 *       真实排名必然落在界内（不依赖"质量集中于均值"的启发式假设），
 *       该性质在 TDigestTest 中以精确路线对照验证。</li>
 * </ul>
 * 尺度函数 k(q) = δ/(2π)·arcsin(2q−1)：两端质心小（尾部精度高），中间质心大。
 */
public class TDigest {

    public static final double DEFAULT_COMPRESSION = 100.0;

    /** 质心：均值 + 权重 + 样本覆盖范围 [min,max]（用于严格误差界）。 */
    public static final class Centroid {
        public double mean;
        public double weight;
        public double min;
        public double max;
        public Centroid(double mean, double weight, double min, double max) {
            this.mean = mean; this.weight = weight; this.min = min; this.max = max;
        }
        public Centroid copy() { return new Centroid(mean, weight, min, max); }
        void absorb(Centroid c) {
            double w = weight + c.weight;
            mean = (mean * weight + c.mean * c.weight) / w;
            weight = w;
            if (c.min < min) min = c.min;
            if (c.max > max) max = c.max;
        }
    }

    /**
     * 分位数/占比估计结果。
     * value：估计值；rankLo/rankHi：该值（或占比）真实排名的严格下/上界；
     * rankErrorBound = (rankHi − rankLo)/N（占样本比）。
     */
    public record Estimate(double value, double rankLo, double rankHi, double rankErrorBound) {}

    private final double compression;
    private List<Centroid> centroids = new ArrayList<>();
    private final List<Centroid> buffer = new ArrayList<>();
    private double totalWeight = 0;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    public TDigest() { this(DEFAULT_COMPRESSION); }

    public TDigest(double compression) { this.compression = compression; }

    /** 从已压缩质心重建（用于从存储加载）。 */
    public static TDigest of(double compression, double min, double max, List<Centroid> centroids) {
        TDigest d = new TDigest(compression);
        d.min = min;
        d.max = max;
        for (Centroid c : centroids) {
            d.centroids.add(c.copy());
            d.totalWeight += c.weight;
        }
        return d;
    }

    public void add(double x) {
        buffer.add(new Centroid(x, 1, x, x));
        totalWeight += 1;
        if (x < min) min = x;
        if (x > max) max = x;
        if (buffer.size() > 5 * compression) flush();
    }

    /** 合并另一个摘要（可结合性：误差与对并集样本构建同阶）。 */
    public void merge(TDigest other) {
        if (other == null || other.size() == 0) return;
        other.flush();
        for (Centroid c : other.centroids) buffer.add(c.copy());
        totalWeight += other.totalWeight;
        if (other.min < min) min = other.min;
        if (other.max > max) max = other.max;
        flush();
    }

    public long size() { return Math.round(totalWeight); }

    public double min() { return min; }
    public double max() { return max; }
    public double totalWeight() { flush(); return totalWeight; }
    public double compression() { return compression; }

    /** 当前质心（先压缩）。用于持久化。 */
    public List<Centroid> centroids() {
        flush();
        List<Centroid> out = new ArrayList<>(centroids.size());
        for (Centroid c : centroids) out.add(c.copy());
        return out;
    }

    /** 质心数（压缩后），验证存储有界。 */
    public int centroidCount() { flush(); return centroids.size(); }

    private void flush() {
        if (buffer.isEmpty()) return;
        List<Centroid> all = new ArrayList<>(centroids.size() + buffer.size());
        all.addAll(centroids);
        all.addAll(buffer);
        buffer.clear();
        all.sort(Comparator.comparingDouble(c -> c.mean));
        centroids = compress(all);
    }

    /** k1 尺度贪心压缩：每个输出质心在 k 空间跨度 ≤ 1。 */
    private List<Centroid> compress(List<Centroid> sorted) {
        List<Centroid> out = new ArrayList<>();
        double total = 0;
        for (Centroid c : sorted) total += c.weight;
        if (total == 0) return out;
        double normalizer = compression / (2 * Math.PI);
        double wCum = 0;
        Centroid cur = null;
        for (Centroid c : sorted) {
            if (cur == null) { cur = c.copy(); continue; }
            double projected = cur.weight + c.weight;
            double kLeft = normalizer * Math.asin(2 * (wCum / total) - 1);
            double kRight = normalizer * Math.asin(2 * ((wCum + projected) / total) - 1);
            if (kRight - kLeft <= 1) {
                cur.absorb(c);
            } else {
                out.add(cur);
                wCum += cur.weight;
                cur = c.copy();
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    /**
     * q 分位数估计（质心中心线性插值），附严格排名误差界。
     * <p>插值坐标与精确路线（{@link Quantiles}，type-7）对齐：
     * index = q·(N−1) + 0.5（质心中心坐标系），保证同一分位数定义下两条路线结果可对照。
     */
    public Estimate quantileEstimate(double q) {
        flush();
        int k = centroids.size();
        if (k == 0) return null;
        double n = totalWeight;
        if (q <= 0) return new Estimate(min, 0, 0, 0);
        if (q >= 1) return new Estimate(max, n, n, 0);
        double[] centers = centers();
        double index = q * (n - 1) + 0.5;
        double value;
        if (index <= centers[0]) {
            Centroid c0 = centroids.get(0);
            value = (c0.mean == min || centers[0] == 0) ? c0.mean
                    : min + (index / centers[0]) * (c0.mean - min);
        } else if (index >= centers[k - 1]) {
            Centroid ck = centroids.get(k - 1);
            double span = n - centers[k - 1];
            value = (max == ck.mean || span == 0) ? ck.mean
                    : ck.mean + ((index - centers[k - 1]) / span) * (max - ck.mean);
        } else {
            int i = 0;
            while (i < k - 2 && centers[i + 1] < index) i++;
            Centroid c1 = centroids.get(i);
            Centroid c2 = centroids.get(i + 1);
            double t = (centers[i + 1] == centers[i]) ? 0.5
                    : (index - centers[i]) / (centers[i + 1] - centers[i]);
            value = c1.mean + t * (c2.mean - c1.mean);
        }
        return withRankBounds(value, n);
    }

    /** 累计分布估计：样本中 ≤ x 的数量估计，附严格误差界。 */
    public Estimate cdfEstimate(double x) {
        flush();
        int k = centroids.size();
        if (k == 0) return null;
        double n = totalWeight;
        if (x < min) return new Estimate(0, 0, 0, 0);
        if (x >= max) return new Estimate(n, n, n, 0);
        double[] centers = centers();
        double mean0 = centroids.get(0).mean;
        double meanK = centroids.get(k - 1).mean;
        double value;
        if (x < mean0) {
            value = (mean0 == min) ? centers[0] : (x - min) / (mean0 - min) * centers[0];
        } else if (x >= meanK) {
            double span = max - meanK;
            value = (span == 0) ? n : centers[k - 1] + (x - meanK) / span * (n - centers[k - 1]);
        } else {
            int i = 0;
            while (i < k - 2 && centroids.get(i + 1).mean <= x) i++;
            Centroid c1 = centroids.get(i);
            Centroid c2 = centroids.get(i + 1);
            double t = (c2.mean == c1.mean) ? 0.5 : (x - c1.mean) / (c2.mean - c1.mean);
            value = centers[i] + t * (centers[i + 1] - centers[i]);
        }
        // 严格界：max ≤ x 的质心样本必然 ≤ x（下界）；min ≤ x 的质心样本可能 ≤ x（上界）
        double lo = 0, hi = 0;
        for (Centroid c : centroids) {
            if (c.max <= x) lo += c.weight;
            if (c.min <= x) hi += c.weight;
        }
        // 估计值钳入严格界内，保证 |估计-真实| ≤ 界宽
        value = Math.max(lo, Math.min(hi, value));
        return new Estimate(value, lo, hi, (hi - lo) / n);
    }

    /** 高额占比估计：net >= threshold 的样本占比 + 严格误差界。 */
    public Estimate highRatioEstimate(double threshold) {
        flush();
        if (centroids.isEmpty()) return null;
        double n = totalWeight;
        Estimate cdf = cdfEstimate(threshold);
        // 占比 = (N − cdf(t))/N；cdf 的界平移到占比（方向相反）
        double ratio = (n - cdf.value()) / n;
        double ratioLo = (n - cdf.rankHi()) / n;
        double ratioHi = (n - cdf.rankLo()) / n;
        return new Estimate(ratio, ratioLo, ratioHi, cdf.rankErrorBound());
    }

    /** 为分位数值 v 计算严格排名界：max ≤ v 的质心必然 ≤ v；min ≤ v 的质心可能 ≤ v。 */
    private Estimate withRankBounds(double v, double n) {
        double lo = 0, hi = 0;
        for (Centroid c : centroids) {
            if (c.max <= v) lo += c.weight;
            if (c.min <= v) hi += c.weight;
        }
        return new Estimate(v, lo, hi, (hi - lo) / n);
    }

    private double[] centers() {
        flush();
        double[] centers = new double[centroids.size()];
        double cum = 0;
        for (int i = 0; i < centroids.size(); i++) {
            centers[i] = cum + centroids.get(i).weight / 2;
            cum += centroids.get(i).weight;
        }
        return centers;
    }

    /** 序列化为 JSON（质心 [mean,weight,min,max] 列表）。 */
    public String toJson() {
        flush();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"compression\":").append(compression)
          .append(",\"min\":").append(min)
          .append(",\"max\":").append(max)
          .append(",\"centroids\":[");
        for (int i = 0; i < centroids.size(); i++) {
            Centroid c = centroids.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(c.mean).append(',').append(c.weight)
              .append(',').append(c.min).append(',').append(c.max).append(']');
        }
        return sb.append("]}").toString();
    }

    /** 从 toJson 的产物重建。 */
    public static TDigest fromJson(String json) {
        String s = json.trim();
        double compression = extractNumber(s, "\"compression\":");
        double min = extractNumber(s, "\"min\":");
        double max = extractNumber(s, "\"max\":");
        int ci = s.indexOf("\"centroids\":");
        String arr = s.substring(ci + "\"centroids\":".length()).trim();
        List<Centroid> centroids = new ArrayList<>();
        String inner = arr.substring(1, arr.lastIndexOf(']'));
        for (String pair : inner.split("\\],\\[")) {
            String p = pair.replace("[", "").replace("]", "").trim();
            if (p.isEmpty()) continue;
            String[] parts = p.split(",");
            if (parts.length == 2) {
                double m = Double.parseDouble(parts[0]);
                double w = Double.parseDouble(parts[1]);
                centroids.add(new Centroid(m, w, m, m)); // 兼容旧格式（无 min/max）
            } else {
                centroids.add(new Centroid(
                        Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
            }
        }
        return TDigest.of(compression, min, max, centroids);
    }

    private static double extractNumber(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) throw new IllegalArgumentException("缺少键 " + key);
        int start = i + key.length();
        int end = start;
        while (end < s.length() && "-+.0123456789eE".indexOf(s.charAt(end)) >= 0) end++;
        return Double.parseDouble(s.substring(start, end));
    }

    /** 仅供测试/调试。 */
    List<Centroid> rawCentroids() { return Collections.unmodifiableList(centroids); }
}
