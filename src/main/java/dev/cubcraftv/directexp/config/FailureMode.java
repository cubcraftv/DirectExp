package dev.cubcraftv.directexp.config;

public enum FailureMode {
	FALLBACK("fallback"),
	STRICT("strict");

	private final String id;

	FailureMode(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	public static FailureMode fromId(String value) {
		if (value == null) {
			return FALLBACK;
		}

		for (FailureMode mode : values()) {
			if (mode.id.equalsIgnoreCase(value)) {
				return mode;
			}
		}

		return FALLBACK;
	}
}
