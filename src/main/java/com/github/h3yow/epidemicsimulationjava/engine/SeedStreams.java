package com.github.h3yow.epidemicsimulationjava.engine;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * 种子派生工具：保证 (baseSeed, replicateIndex) 唯一确定一条随机流。
 *
 * <p>可复现性约定：固定使用 JDK 内置的 {@code L64X128MixRandom} 算法（LXM，
 * 不依赖平台默认实现），每个重复实验的种子由 baseSeed 与 replicateIndex 经
 * SplitMix64 混合得到。因此第 i 次重复的随机序列只取决于 (baseSeed, i)，
 * 与并行度、任务划分、执行顺序均无关。
 */
public final class SeedStreams {

    private static final RandomGeneratorFactory<RandomGenerator> FACTORY =
            RandomGeneratorFactory.of("L64X128MixRandom");

    /** SplitMix64 增量常数。 */
    private static final long GOLDEN = 0x9e3779b97f4a7c15L;

    private SeedStreams() {
    }

    /** 由基础种子与重复实验下标派生出该次实验的确定种子。 */
    public static long replicateSeed(long baseSeed, int replicateIndex) {
        if (replicateIndex < 0) {
            throw new IllegalArgumentException("replicateIndex 不能为负: " + replicateIndex);
        }
        return splitMix64(baseSeed + GOLDEN * (replicateIndex + 1L));
    }

    /** 按指定种子创建固定算法的随机数生成器。 */
    public static RandomGenerator newRng(long seed) {
        return FACTORY.create(seed);
    }

    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
