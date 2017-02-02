package net.codepoke.ai.challenges.hunterkiller.bots;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.network.AIBot;

/**
 * Represents a bot used for testing purposes.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class TestBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	public TestBot() {
		super("", HunterKillerState.class, HunterKillerAction.class);

	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {

		// Create a random action to return
		return RandomBot.createRandomAction(state);
	}

}
