package org.opengoofy.index60321;

public class ProblemSizeCalculator4 {

    /**
     * 寻找最大的n满足 n * log2(n) < t.
     * 
     * @param t 输入的时间限制，单位为秒。
     * @return 满足条件的最大整数n。
     */
    public static Double findLargestN(double t) {
        // If t <= 0, n*log₂(n) needs to be negative, which isn't possible with real
        // positive n
        if (t <= 0) {
            return 0.0;
        }
        // We need to solve n * log2(n) < t 1n(n)/ln(2)
        // Using binary search since there's no simple closed-form solution.

        // --- Increase right bound until it's definitely larger than solution ---
        Double high = 2.0;
        double log2 = Math.log(2.0);
        while (true) {
            // n * log2(n) = n * (ln(n) / ln(2))
            double val = (double) high * (Math.log(high) / log2);
            if (val >= t) {
                break;
            }
            // 如果t特别大，防止溢流，直接设置high为Integer.MAX_VALUE
            if (high > Double.MAX_VALUE / 2) {
                high = Double.MAX_VALUE;
                break;
            }
            high *= 2;
        }

        // Binary search for the largest n
        Double low = 1.0;
        Double largestIntegerN = 0.0;

        while (low <= high) {
            // 计算中间值，避免溢出
            Double mid = (double) low + (high - low) / 2;

            // 如果mid为0，log2(0)是未定义的，我们直接跳过这个值
            if (mid == 0) {
                low = 1.0;
                continue;
            }

            double val = (double) mid * (Math.log(mid) / log2);

            if (val < t) {
                // n*log2(n) < t, 这个n是一个候选解，继续在右半部分寻找更大的n
                largestIntegerN = mid;
                low = mid + 1;
            } else {
                // n*log2(n) >= t, 继续在左半部分寻找更小的n
                high = mid - 1;
            }
        }

        // Return the largest integer where n*log₂(n) < t
        return largestIntegerN;
    }

    public static void main(String[] args) {
        // Test cases
        double[] tests = { 10, 100, 1000, 1000000 };
        for (double t : tests) {
            double n = findLargestN(t);
            double val = n * (Math.log(n) / Math.log(2));
            System.out.printf("For t = %.0f, largest n is %.0f (n*log2(n) ≈ %.2f)%n", t, n, val);
        }
    }
}