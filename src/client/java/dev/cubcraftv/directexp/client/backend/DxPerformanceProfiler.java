package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.DirectExpMod;

import java.util.EnumMap;
import java.util.Map;

final class DxPerformanceProfiler {
	private static final boolean ENABLED = Boolean.getBoolean("directexp.profile.performance");
	private static final long REPORT_INTERVAL_NANOS = 10_000_000_000L;
	private static final Map<Category, Measurement> MEASUREMENTS = new EnumMap<>(Category.class);
	private static long reportStartedAt = System.nanoTime();

	static {
		for (Category category : Category.values()) {
			MEASUREMENTS.put(category, new Measurement());
		}
	}

	private DxPerformanceProfiler() {
	}

	static long start() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	static void record(Category category, long startedAt, long operations) {
		if (!ENABLED || startedAt == 0L) {
			return;
		}

		synchronized (MEASUREMENTS) {
			Measurement measurement = MEASUREMENTS.get(category);
			measurement.calls++;
			measurement.operations += operations;
			measurement.elapsedNanos += System.nanoTime() - startedAt;

			long now = System.nanoTime();
			if (now - reportStartedAt >= REPORT_INTERVAL_NANOS) {
				report(now);
			}
		}
	}

	private static void report(long now) {
		long intervalNanos = now - reportStartedAt;
		StringBuilder summary = new StringBuilder("DirectExp performance profile (")
			.append(intervalNanos / 1_000_000L).append(" ms):");
		for (Category category : Category.values()) {
			Measurement measurement = MEASUREMENTS.get(category);
			if (measurement.calls == 0L) {
				continue;
			}
			summary.append(' ').append(category.label)
				.append(" calls=").append(measurement.calls)
				.append(' ').append(category.operationsLabel).append('=').append(measurement.operations)
				.append(" totalMs=").append(measurement.elapsedNanos / 1_000_000L)
				.append(" avgNs=").append(measurement.elapsedNanos / measurement.calls);
			measurement.clear();
		}
		DirectExpMod.LOGGER.info("{}", summary);
		reportStartedAt = now;
	}

	enum Category {
		INDEXED_DRAW("indexedDraw", "draws"),
		MULTI_DRAW("multiDraw", "draws"),
		CONSTANT_BUFFER_UPDATE("constantBufferUpdate", "uploads"),
		BUFFER_COPY("bufferCopy", "bytes");

		private final String label;
		private final String operationsLabel;

		Category(String label, String operationsLabel) {
			this.label = label;
			this.operationsLabel = operationsLabel;
		}
	}

	private static final class Measurement {
		private long calls;
		private long operations;
		private long elapsedNanos;

		private void clear() {
			this.calls = 0L;
			this.operations = 0L;
			this.elapsedNanos = 0L;
		}
	}
}
