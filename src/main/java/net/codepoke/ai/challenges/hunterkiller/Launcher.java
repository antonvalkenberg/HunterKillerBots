package net.codepoke.ai.challenges.hunterkiller;

import java.net.URISyntaxException;

import net.codepoke.ai.Action;
import net.codepoke.ai.GameRules;
import net.codepoke.ai.GameRules.Result;
import net.codepoke.ai.State;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerMatchRequest;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerStateFactory;
import net.codepoke.ai.challenge.hunterkiller.orders.NullMove;
import net.codepoke.ai.challenges.hunterkiller.bots.RandomBot;
import net.codepoke.ai.challenges.hunterkiller.bots.RulesBot;
import net.codepoke.ai.challenges.hunterkiller.bots.ScoutingBot;
import net.codepoke.ai.challenges.hunterkiller.bots.TestBot;
import net.codepoke.ai.network.AIClient;
import net.codepoke.ai.network.MatchMessageParser;
import net.codepoke.ai.network.MatchRequest;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.tools.texturepacker.TexturePacker;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;

public class Launcher {

	public static void main(String[] arg) throws URISyntaxException {
		// simulate(true);
		// queue(true);
		// requestMatch(true);
		requestGrudgeMatch(true);
	}

	public static void simulate(boolean seatSpecific) {
		// Create the packed asset atlas
		TexturePacker.Settings settings = new TexturePacker.Settings();
		settings.maxHeight = 2048;
		settings.maxWidth = 2048;
		settings.useIndexes = true;
		settings.paddingX = settings.paddingY = 1;
		settings.edgePadding = true;
		settings.bleed = true;
		settings.filterMin = TextureFilter.MipMapNearestNearest;
		settings.filterMag = TextureFilter.MipMapNearestNearest;
		// TexturePacker.process(settings, "imgs/", System.getProperty("user.dir"), "game.atlas");

		// Start up the game
		LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
		config.forceExit = true;

		final HunterKillerVisualization listener = new HunterKillerVisualization();

		new LwjglApplication(listener, config);

		if (seatSpecific) {
			simulateStream(listener);
		} else {
			simulateStreamWithSpecificSeats(listener);
		}
	}

	public static void queue(boolean training) {
		RandomBot bot = new RandomBot();

		AIClient client = new AIClient("ai.codepoke.net/competition/queue", bot, "HunterKiller");

		MatchRequest request = new MatchRequest("HunterKiller", bot.getBotUID(), training);

		client.connect(request);
	}

	public static void requestMatch(boolean training) {
		RandomBot bot = new RandomBot();

		AIClient<? extends State, ? extends Action> client = new AIClient<HunterKillerState, HunterKillerAction>(
																													"ai.codepoke.net/competition/queue",
																													bot, "HunterKiller");

		HunterKillerMatchRequest request = new HunterKillerMatchRequest(bot.getBotUID(), training);

		client.connect(request);
	}

	public static void requestGrudgeMatch(boolean training) {
		RandomBot bot = new RandomBot();
		RulesBot bot2 = new RulesBot();

		AIClient<? extends State, ? extends Action> client = new AIClient<HunterKillerState, HunterKillerAction>(
																													"ai.codepoke.net/competition/queue",
																													bot, "HunterKiller");
		AIClient<? extends State, ? extends Action> client2 = new AIClient<HunterKillerState, HunterKillerAction>(
																													"ai.codepoke.net/competition/queue",
																													bot2, "HunterKiller");

		new Thread() {

			public void run() {
				HunterKillerMatchRequest request = new HunterKillerMatchRequest(bot.getBotUID(), training);
				request.setMatchPlayers(2);
				request.setRequiredOpponents(Array.with("DevBot2"));

				// Connect the first bot, the one with the restrictive request
				client.connect(request);
			}

		}.start();

		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		new Thread() {

			public void run() {
				// Connect the second bot, the one that is in the first bot's list
				HunterKillerMatchRequest request2 = new HunterKillerMatchRequest(bot2.getBotUID(), training);
				client2.connect(request2);
			}

		}.start();
	}

	/**
	 * Tests streaming a match to the visualizer by locally playing a game, and sending it to the visualizer.
	 * 
	 * @param vis
	 *            The match visualization.
	 */
	private static void simulateStream(final HunterKillerVisualization vis) {
		final MatchMessageParser<HunterKillerState, HunterKillerAction> listener = vis.getParser();
		new Thread() {

			public void run() {

				// Small wait for visualisation to properly setup
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				GameRules<HunterKillerState, HunterKillerAction> rules = new HunterKillerRules();
				Array<HunterKillerAction> actions = new Array<HunterKillerAction>();

				// Create the initial state
				HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { "A", "B" }, null);

				HunterKillerState orgState = state.copy();

				// RandomBot randomBot = new RandomBot(); // Instantiate your bot here
				// RulesBot rulesBot = new RulesBot();
				TestBot testBot = new TestBot();

				Json json = new Json();

				listener.parseMessage(vis.getLastState(), json.toJson(state.getPlayers())); // Players
				listener.parseMessage(vis.getLastState(), json.toJson(Array.with(orgState))); // Initial State

				// The following snippet will run a match with the given AI for all player seats until the match is
				// finished or an error occurs.
				// This would fail if your AI assumes it always operates in the same seat; instead of querying
				// State.getCurrentPlayer().
				Result result;
				do {
					HunterKillerAction action = testBot.handle(state);
					actions.add(action);
					// Alternatively, send the action immediately: listener.parseMessage(vis.getLastState(),
					// json.toJson(action));
					result = rules.handle(state, action);
				} while (!result.isFinished() && result.isAccepted());

				listener.parseMessage(vis.getLastState(), json.toJson(actions));

			}
		}.start();
	}

	/**
	 * Tests streaming a match to the visualizer by locally playing a game, and sending it to the visualizer. This
	 * method places the AIs into specific seats.
	 * 
	 * @param vis
	 *            The match visualization.
	 */
	private static void simulateStreamWithSpecificSeats(final HunterKillerVisualization vis) {
		final MatchMessageParser<HunterKillerState, HunterKillerAction> listener = vis.getParser();
		new Thread() {

			public void run() {

				// Small wait for visualisation to properly setup
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				GameRules<HunterKillerState, HunterKillerAction> rules = new HunterKillerRules();
				Array<HunterKillerAction> actions = new Array<HunterKillerAction>();

				// Create the initial state
				HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { "A", "B" }, null);

				HunterKillerState orgState = state.copy();

				// Instantiate your bot here
				ScoutingBot botA = new ScoutingBot(vis);
				RandomBot botB = new RandomBot();

				Json json = new Json();

				listener.parseMessage(vis.getLastState(), json.toJson(state.getPlayers())); // Players
				listener.parseMessage(vis.getLastState(), json.toJson(Array.with(orgState))); // Initial State

				Result result;
				do {
					HunterKillerAction action;

					// Ask the bots in specific seats for an action
					switch (state.getActivePlayerID()) {
					case 0:
						action = botA.handle(state);
						break;
					case 1:
						action = botB.handle(state);
						break;
					case 2:
						action = new NullMove();
						break;
					case 3:
						action = new NullMove();
						break;
					default:
						action = new NullMove();
						break;
					}

					actions.add(action);
					// Alternatively, send the action immediately: listener.parseMessage(vis.getLastState(),
					// json.toJson(action));
					result = rules.handle(state, action);
				} while (!result.isFinished() && result.isAccepted());

				listener.parseMessage(vis.getLastState(), json.toJson(actions));

			}
		}.start();
	}

}
