package net.codepoke.ai.challenges.hunterkiller;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.TimeUnit;

import lombok.val;
import net.codepoke.ai.GameRules;
import net.codepoke.ai.GameRules.Result;
import net.codepoke.ai.GameRules.Result.Ranking;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerConstants;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerMatchRequest;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerStateFactory;
import net.codepoke.ai.challenge.hunterkiller.enums.GameMode;
import net.codepoke.ai.challenge.hunterkiller.enums.MapType;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot;
import net.codepoke.ai.challenges.hunterkiller.bots.PerformanceBot;
import net.codepoke.ai.challenges.hunterkiller.bots.RandomBot;
import net.codepoke.ai.challenges.hunterkiller.bots.RulesBot;
import net.codepoke.ai.challenges.hunterkiller.bots.ScoutingBot;
import net.codepoke.ai.challenges.hunterkiller.bots.SquadBot;
import net.codepoke.ai.network.AIBot;
import net.codepoke.ai.network.AIClient;
import net.codepoke.ai.network.MatchMessageParser;
import net.codepoke.ai.network.MatchRequest;
import net.codepoke.lib.util.common.Stopwatch;

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

public class HunterKillerBotQueuer {

	public static final String HKS_FILE_PATH = System.getProperty("user.home") + "/test.hks";
	public static final String RANDOMBOT_NAME = "RandomBot";
	public static final String RULESBOT_NAME = "RulesBot";
	public static final String SCOUTINGBOT_NAME = "ScoutingBot";
	public static final String SQUADBOT_NAME = "SquadBot";
	public static final String SERVER_QUEUE_ADDRESS = "ai.codepoke.net/competition/queue";
	public static final boolean TRAINING_MODE = false;
	public static final int TIME_BUFFER = 10;

	public static void main(String[] arg) {
		simulate(true);
		// queue(false);
		// requestMatch(true);
		// requestGrudgeMatch(true);

		// playFromLoadedState(HKS_FILE_PATH);

		// for (int i = 0; i < 100; i++) {
		// simulateWithoutVisuals();
		// }

		// BaseBot.setTimeBuffer(TIME_BUFFER);

		// spawnTestRooms();
		//
		// // Queue some RandomBots to play against these test rooms
		// Random r = new Random();
		// for (int i = 0; i < 10; i++) {
		//
		// // Small wait to simulate people randomly queuing in
		// try {
		// Thread.sleep(r.nextInt(50));
		// } catch (InterruptedException e) {
		// e.printStackTrace();
		// }
		//
		// queue(TRAINING_MODE);
		// }

		// runTest(1);
	}

	public static void spawnTestRooms() {
		// Define the bots that we want to have present in the rotation
		Array<String> myBots = Array.with(RANDOMBOT_NAME, RULESBOT_NAME, SCOUTINGBOT_NAME, SQUADBOT_NAME);

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
								// Output that we are going to queue
								System.out.println("Queueing bot '" + botName + "' for: " + combination);

								AIBot<HunterKillerState, HunterKillerAction> bot = getBot(botName);
								val botClient = new AIClient<HunterKillerState, HunterKillerAction>(SERVER_QUEUE_ADDRESS, bot,
																									HunterKillerConstants.GAME_NAME);

								// Create a new MatchRequest
								MatchRequest botRequest = new MatchRequest(HunterKillerConstants.GAME_NAME, bot.getBotUID(), TRAINING_MODE);
								// required-opponents are all bots in the combination
								botRequest.setRequiredOpponents(requiredOpponents);
								// excluded-opponents is the bot's name
								botRequest.setExcludedOpponents(excludedOpponents);
								// match-players is one more than combination's length
								botRequest.setMatchPlayers(combination.getSize() + 1);

								// Queue the request
								botClient.connect(botRequest);

								// Output that we've returned from the connection
								System.out.println("Bot '" + botName + "' returned from connection: " + combination);

							} while (true);
						}
					}.start();
				});
			}
		}
	}

	public static AIBot<HunterKillerState, HunterKillerAction> getBot(String botName) {
		AIBot<HunterKillerState, HunterKillerAction> bot;
		switch (botName) {
		case SQUADBOT_NAME:
			bot = new SquadBot();
			break;
		case SCOUTINGBOT_NAME:
			bot = new ScoutingBot();
			break;
		case RULESBOT_NAME:
			bot = new RulesBot();
			break;
		case RANDOMBOT_NAME:
			// Intentional fall-through
		default:
			bot = new RandomBot();
			break;
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
				SquadBot bot = new SquadBot();

				val client = new AIClient<HunterKillerState, HunterKillerAction>(SERVER_QUEUE_ADDRESS, bot, HunterKillerConstants.GAME_NAME);

				MatchRequest request = new MatchRequest(HunterKillerConstants.GAME_NAME, bot.getBotUID(), training);

				client.connect(request);
			}
		}.start();
	}

	public static void requestMatch(boolean training) {
		new Thread() {
			public void run() {
				RandomBot bot = new RandomBot();

				val client = new AIClient<HunterKillerState, HunterKillerAction>(SERVER_QUEUE_ADDRESS, bot, HunterKillerConstants.GAME_NAME);

				HunterKillerMatchRequest request = new HunterKillerMatchRequest(bot.getBotUID(), training);
				request.setMatchPlayers(3);
				request.setRequiredOpponents(Array.with(RANDOMBOT_NAME, RULESBOT_NAME));
				request.setGameType(GameMode.Capture);
				request.setMapType(MapType.Narrow);

				client.connect(request);
			}
		}.start();
	}

	public static void simulateWithoutVisuals() {
		new Thread() {
			public void run() {

				Stopwatch timer = new Stopwatch();

				GameRules<HunterKillerState, HunterKillerAction> rules = new HunterKillerRules();
				Array<HunterKillerAction> actions = new Array<HunterKillerAction>();

				HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { "A", "B", "C", "D" }, null);

				RandomBot randomBot = new RandomBot();

				Json json = new Json();

				Result result;
				do {
					HunterKillerState stateCopy = state.copy();
					stateCopy.prepare(state.getActivePlayerID());

					// timer.start();
					// json.toJson(state);
					// long time = timer.end();
					// System.out.println("Serializing state took " + TimeUnit.MILLISECONDS.convert(time,
					// TimeUnit.NANOSECONDS)
					// + " miliseconds");

					HunterKillerAction action = randomBot.handle(stateCopy);
					actions.add(action);

					// timer.start();
					// json.toJson(actions);
					// long time2 = timer.end();
					// System.out.println("Serializing actions took " + TimeUnit.MILLISECONDS.convert(time2,
					// TimeUnit.NANOSECONDS)
					// + " miliseconds");

					// timer.start();
					result = rules.handle(state, action);
					// long time3 = timer.end();
					// System.out.println("Ruling this action took " + TimeUnit.MILLISECONDS.convert(time3,
					// TimeUnit.NANOSECONDS)
					// + " miliseconds");

				} while (!result.isFinished() && result.isAccepted());

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

				// Copy the initial state to serialize it
				HunterKillerState orgState = state.copy();

				PerformanceBot performanceBot = new PerformanceBot(); // Instantiate your bot here

				Json json = new Json();

				listener.parseMessage(vis.getLastState(), json.toJson(state.getPlayers())); // Players
				listener.parseMessage(vis.getLastState(), json.toJson(Array.with(orgState))); // Initial State

				// The following snippet will run a match with the given AI for all player seats until the match is
				// finished or an error occurs.
				// This would fail if your AI assumes it always operates in the same seat; instead of querying
				// State.getCurrentPlayer().
				Result result;
				do {
					HunterKillerAction action = performanceBot.handle(state);
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

				// Instantiate your bot here
				HMCTSBot botA = new HMCTSBot(true);
				HMCTSBot botB = new HMCTSBot(false);
				PerformanceBot botC = new PerformanceBot();
				PerformanceBot botD = new PerformanceBot();

				// Create the initial state
				HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { botA.getBotName(),
																											botB.getBotName() }, null);

				// Copy the initial state to serialize it
				HunterKillerState orgState = state.copy();

				Json json = new Json();

				listener.parseMessage(vis.getLastState(), json.toJson(state.getPlayers())); // Players
				listener.parseMessage(vis.getLastState(), json.toJson(Array.with(orgState))); // Initial State

				Result result;
				do {
					HunterKillerAction action;

					// Simulate preparing of the state
					HunterKillerState stateCopy = state.copy();
					stateCopy.prepare(state.getActivePlayerID());

					// Ask the bots in specific seats for an action
					switch (state.getActivePlayerID()) {
					case 0:
						action = botA.handle(stateCopy);
						break;
					case 1:
						action = botB.handle(stateCopy);
						break;
					case 2:
						action = botC.handle(stateCopy);
						break;
					case 3:
						action = botD.handle(stateCopy);
						break;
					default:
						action = stateCopy.createNullMove();
						break;
					}

					actions.add(action);
					// Alternatively, send the action immediately:
					listener.parseMessage(vis.getLastState(), json.toJson(Array.with(action)));
					result = rules.handle(state, action);
				} while (!result.isFinished() && result.isAccepted());

				// listener.parseMessage(vis.getLastState(), json.toJson(actions));
			}
		}.start();
	}

	public static void runTest(int numberOfGames) {
		HunterKillerRules.IGNORE_FAILURES = true;
		GameRules<HunterKillerState, HunterKillerAction> rules = new HunterKillerRules();
		HMCTSBot botA = new HMCTSBot(true);
		PerformanceBot botB = new PerformanceBot();
		HunterKillerMatchRequest request = new HunterKillerMatchRequest(botA.getBotUID(), TRAINING_MODE);
		// request.setOptions("nonRandomSections");

		long totalTime = 0;
		int totalRounds = 0;
		int totalWinningPoints = 0;

		for (int i = 0; i < numberOfGames; i++) {

			Stopwatch gameTimer = new Stopwatch();
			gameTimer.start();

			HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { botA.getBotName(),
																										botB.getBotName() }, request);

			// System.out.print("Initial state contains " + state.getMap()
			// .getObjects().size + " objects | ");

			Result result;
			do {
				if (state.getActivePlayer()
							.getName()
							.equals(botA.getBotName())) {
					result = rules.handle(state, botA.handle(state));
				} else {
					result = rules.handle(state, botB.handle(state));
				}

			} while (!result.isFinished() && result.isAccepted());

			long time = gameTimer.end();

			// System.out.println("Game took " + TimeUnit.MILLISECONDS.convert(time, TimeUnit.NANOSECONDS) +
			// " milliseconds, ended after "
			// + state.getCurrentRound() + " rounds.");

			// if (i >= 5) {
			totalTime += time;
			totalRounds += state.getCurrentRound();
			totalWinningPoints += state.getPlayer(result.getRanking()
														.get(0)
														.getPlayerNumber())
										.getScore();
			// }

			System.out.println("**********");
			for (Ranking ranking : result.getRanking()) {
				System.out.println(state.getPlayer(ranking.getPlayerNumber())
										.getName() + " | Rank " + ranking.getRank());
			}
			System.out.println("**********");
		}

		System.out.println("All games took " + TimeUnit.MILLISECONDS.convert(totalTime, TimeUnit.NANOSECONDS) + " miliseconds");
		System.out.println("All games lasted for " + totalRounds + " rounds");
		System.out.println("All games scored " + totalWinningPoints + " points for the winner");

	}

	public static void writeHKSToFile(HunterKillerState state) {
		Json json = new Json();
		File file = new File(HKS_FILE_PATH);
		try {
			PrintWriter writer = new PrintWriter(file, "UTF-8");
			writer.write(json.toJson(state));
			writer.close();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public static void playFromLoadedState(String stateFilePath) {

		LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
		config.forceExit = true;

		final HunterKillerVisualization vis = new HunterKillerVisualization();

		new LwjglApplication(vis, config);

		final MatchMessageParser<HunterKillerState, HunterKillerAction> listener = vis.getParser();
		new Thread() {
			public void run() {
				try {

					// Small wait for visualisation to properly setup
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}

					GameRules<HunterKillerState, HunterKillerAction> rules = new HunterKillerRules();
					Array<HunterKillerAction> actions = new Array<HunterKillerAction>();

					// Instantiate your bot here
					HMCTSBot botA = new HMCTSBot(true);
					SquadBot botB = new SquadBot();
					PerformanceBot botC = new PerformanceBot();
					PerformanceBot botD = new PerformanceBot();

					Json json = new Json();
					// Create the loaded state
					HunterKillerState state = json.fromJson(HunterKillerState.class, new FileReader(new File(HKS_FILE_PATH)));

					// Copy the initial state to serialize it
					HunterKillerState orgState = state.copy();

					listener.parseMessage(vis.getLastState(), json.toJson(state.getPlayers())); // Players
					listener.parseMessage(vis.getLastState(), json.toJson(Array.with(orgState))); // Initial State

					Result result;
					do {
						HunterKillerAction action;

						// Simulate preparing of the state
						HunterKillerState stateCopy = state.copy();
						stateCopy.prepare(state.getActivePlayerID());

						// Ask the bots in specific seats for an action
						switch (state.getActivePlayerID()) {
						case 0:
							action = botA.handle(stateCopy);
							break;
						case 1:
							action = botB.handle(stateCopy);
							break;
						case 2:
							action = botC.handle(stateCopy);
							break;
						case 3:
							action = botD.handle(stateCopy);
							break;
						default:
							action = stateCopy.createNullMove();
							break;
						}

						actions.add(action);
						// Alternatively, send the action immediately:
						listener.parseMessage(vis.getLastState(), json.toJson(Array.with(action)));
						result = rules.handle(state, action);
					} while (!result.isFinished() && result.isAccepted());

				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}.start();
	}
}