package dev.cubcraftv.directexp.client.settings;

import dev.cubcraftv.directexp.config.BackendKind;

public final class DirectExpGraphicsApiSelectionState {
	private static BackendKind openedWith;
	private static BackendKind currentSelection;

	private DirectExpGraphicsApiSelectionState() {
	}

	public static void onVideoSettingsOpened(BackendKind initialSelection) {
		openedWith = initialSelection;
		currentSelection = initialSelection;
	}

	public static void onSelectionApplied(BackendKind selected) {
		currentSelection = selected;
	}

	public static boolean hasPendingRestart() {
		return openedWith != null && currentSelection != null && openedWith != currentSelection;
	}
}
