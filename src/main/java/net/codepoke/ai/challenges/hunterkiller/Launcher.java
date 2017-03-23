package net.codepoke.ai.challenges.hunterkiller;

import lombok.val;
import net.codepoke.ai.GameRules;
import net.codepoke.ai.GameRules.Result;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerConstants;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerMatchRequest;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerStateFactory;
import net.codepoke.ai.challenge.hunterkiller.enums.GameMode;
import net.codepoke.ai.challenge.hunterkiller.enums.MapType;
import net.codepoke.ai.challenge.hunterkiller.orders.NullMove;
import net.codepoke.ai.challenges.hunterkiller.bots.RandomBot;
import net.codepoke.ai.challenges.hunterkiller.bots.RulesBot;
import net.codepoke.ai.challenges.hunterkiller.bots.ScoutingBot;
import net.codepoke.ai.challenges.hunterkiller.bots.SquadBot;
import net.codepoke.ai.challenges.hunterkiller.bots.TestBot;
import net.codepoke.ai.network.AIBot;
import net.codepoke.ai.network.AIClient;
import net.codepoke.ai.network.MatchMessageParser;
import net.codepoke.ai.network.MatchRequest;

import org.paukov.combinatorics.Factory;
import org.paukov.combinatorics.Generator;
import org.paukov.combinatorics.ICombinatoricsVector;

import com.google.common.collect.Iterables;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.tools.texturepacker.TexturePacker;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;

public class Launcher {

	public static void main(String[] arg) {
		// simulate(true);
		// queue(true);
		// requestMatch(true);
		// requestGrudgeMatch(true);

		spawnTestRooms();
	}

	public static void spawnTestRooms() {
		// Define the bots that we want to have present in the rotation
		Array<String> myBots = Array.with("RandomBot", "RulesBot", "ScoutingBot", "SquadBot");

		// Create a combinatorics vector based on the bots array
		ICombinatoricsVector<String> initialVector = Factory.createVector(myBots.toArray());
		// Go through all possible combinations for each size
		for (int i = 1; i < initialVector.getSize(); i++) {
			Generator<String> generator = Factory.createSimpleCombinationGenerator(initialVector, i);
			for (ICombinatoricsVector<String> combination : generator) {

				System.out.println("Firing threads for combination: " + combination);

				// For this bot, the required opponents are all bots in the combination
				Array<String> requiredOpponents = Array.with(combination.getVector()
																		.toArray(new String[0]));

				// Create a thread for all items in the combination
				combination.forEach(botName -> {

					// Exclude any of our bots that are not in the required collection
					Array<String> excludedOpponents = Array.with(Iterables.toArray(	myBots.select(name -> !requiredOpponents.contains(	name,
																																		false)),
																					String.class));

					new Thread() {
						public void run() {
							do {
								AIBot<HunterKillerState, HunterKillerAction> bot = getAntonBot(botName);
								val botClient = new AIClient<HunterKillerState, HunterKillerAction>("ai.codepoke.net/competition/queue",
																									bot, HunterKillerConstants.GAME_NAME);

								// Create a new MatchRequest
								MatchRequest botRequest = new MatchRequest(HunterKillerConstants.GAME_NAME, bot.getBotUID(), true);
								// required-opponents are all bots in the combination
								botRequest.setRequiredOpponents(requiredOpponents);
								// excluded-opponents is the bot's name
								botRequest.setExcludedOpponents(excludedOpponents);
								// match-players is one more than combination's length
								botRequest.setMatchPlayers(combination.getSize() + 1);

								// Queue the request
								botClient.connect(botRequest);

							} while (true);
						}
					}.start();
				});
			}
		}
	}

	public static AIBot<HunterKillerState, HunterKillerAction> getAntonBot(String botName) {
		AIBot<HunterKillerState, HunterKillerAction> bot;
		switch (botName) {
		case "SquadBot":
			bot = new SquadBot();
			break;
		case "ScoutingBot":
			bot = new ScoutingBot();
			break;
		case "RulesBot":
			bot = new RulesBot();
			break;
		case "RandomBot":
		default:
			bot = new RandomBot();
		}
		return bot;
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
			simulateStreamWithSpecificSeats(listener);
		} else {
			simulateStream(listener);
		}
	}

	public static void queue(boolean training) {
		new Thread() {
			public void run() {
				RandomBot bot = new RandomBot();

				val client = new AIClient<HunterKillerState, HunterKillerAction>("ai.codepoke.net/competition/queue", bot,
																					HunterKillerConstants.GAME_NAME);

				MatchRequest request = new MatchRequest(HunterKillerConstants.GAME_NAME, bot.getBotUID(), training);

				client.connect(request);
			}
		}.start();
	}

	public static void requestMatch(boolean training) {
		new Thread() {
			public void run() {
				RandomBot bot = new RandomBot();

				val client = new AIClient<HunterKillerState, HunterKillerAction>("ai.codepoke.net/competition/queue", bot,
																					HunterKillerConstants.GAME_NAME);

				HunterKillerMatchRequest request = new HunterKillerMatchRequest(bot.getBotUID(), training);
				request.setMatchPlayers(3);
				request.setRequiredOpponents(Array.with("RandomBot", "RulesBot"));
				request.setGameType(GameMode.Capture);
				request.setMapType(MapType.Narrow);

				client.connect(request);
			}
		}.start();

		try {
			Thread.sleep(1000);
		} catch (Exception e) {

		}

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
				// ScoutingBot scoutBot = new ScoutingBot(vis);
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
				HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { "A", "B", "C", "D" }, null);

				HunterKillerState orgState = state.copy();

				// Instantiate your bot here
				SquadBot botA = new SquadBot(vis);
				ScoutingBot botB = new ScoutingBot(vis);
				RulesBot botC = new RulesBot();
				RandomBot botD = new RandomBot();

				Json json = new Json();

				listener.parseMessage(vis.getLastState(), json.toJson(state.getPlayers())); // Players
				listener.parseMessage(vis.getLastState(), json.toJson(Array.with(orgState))); // Initial State

				String matchName = orgState.getMap()
											.getName()
											.replace(".txt", "")
											.replace("_", " ");
				vis.setGameName(matchName);

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
						action = botC.handle(state);
						break;
					case 3:
						action = botD.handle(state);
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