package com.benmao.quanta.risk;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 内存环形点差历史（单进程、无持久化）。
 *
 * <p>只保留最近 {@code MAX_SAMPLES} 条；查询时按时间窗口过滤。GOLD 单品种，无需按品种分桶。
 */
@Component
public class InMemorySpreadHistory implements SpreadHistory {

    /** 上限，防内存无限增长（M5 采样频率下足够覆盖 1 小时窗口）。 */
    private static final int MAX_SAMPLES = 4096;

    private final Deque<Sample> samples = new ArrayDeque<>();

    @Override
    public synchronized void record(double spreadPoints, LocalDateTime at) {
        if (at == null) {
            return;
        }
        samples.addLast(new Sample(at, spreadPoints));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    @Override
    public synchronized Stat stat(LocalDateTime now, int windowMinutes) {
        LocalDateTime from = now.minusMinutes(Math.max(1, windowMinutes));
        List<Double> values = new ArrayList<>();
        for (Sample s : samples) {
            if (!s.at().isBefore(from)) {
                values.add(s.points());
            }
        }
        if (values.isEmpty()) {
            return new Stat(0, Double.NaN);
        }
        Collections.sort(values);
        int n = values.size();
        double median = (n % 2 == 1)
                ? values.get(n / 2)
                : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
        return new Stat(n, median);
    }

    private record Sample(LocalDateTime at, double points) {
    }
}
