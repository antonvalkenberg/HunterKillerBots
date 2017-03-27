package net.codepoke.ai.challenges.hunterkiller.bots;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.network.AIBot;

public abstract class BaseBot<S, A>
		extends AIBot<HunterKillerState, HunterKillerAction> {

	public BaseBot(String botUID, Class<HunterKillerState> state, Class<HunterKillerAction> action) {
		super(botUID, state, action);
	}

	public static final int NOT_SET_TIME_BUFFER = -1;

	public static int TIME_BUFFER_MS = NOT_SET_TIME_BUFFER;

	public static void setTimeBuffer(int timeBuffer) {
		TIME_BUFFER_MS = timeBuffer;
	}

	public static void waitTimeBuffer() {
		// Check if we need to adhere to a set time buffer
		if (TIME_BUFFER_MS != NOT_SET_TIME_BUFFER) {
			try {
				Thread.sleep(TIME_BUFFER_MS);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

}
