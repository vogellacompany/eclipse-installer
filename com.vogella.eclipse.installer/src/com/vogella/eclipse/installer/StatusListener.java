package com.vogella.eclipse.installer;

/** Receives the installer's status messages and answers whether to cancel. */
public interface StatusListener {

	void step(String name);

	default void log(String line) {
	}

	default boolean isCanceled() {
		return false;
	}
}
