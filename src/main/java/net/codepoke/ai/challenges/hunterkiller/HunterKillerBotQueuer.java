package net.codepoke.ai.challenges.hunterkiller;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

import lombok.val;
import net.codepoke.ai.GameRules;
import net.codepoke.ai.GameRules.Result;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerConstants;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerMatchRequest;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerStateFactory;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.MapSetup;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.enums.Direction;
import net.codepoke.ai.challenge.hunterkiller.enums.GameMode;
import net.codepoke.ai.challenge.hunterkiller.enums.MapType;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Infected;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Soldier;
import net.codepoke.ai.challenges.hunterkiller.bots.BaseBot;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot;
import net.codepoke.ai.challenges.hunterkiller.bots.NMCBot;
import net.codepoke.ai.challenges.hunterkiller.bots.PerformanceBot;
import net.codepoke.ai.challenges.hunterkiller.bots.RandomBot;
import net.codepoke.ai.challenges.hunterkiller.bots.RulesBot;
import net.codepoke.ai.challenges.hunterkiller.bots.ScoutingBot;
import net.codepoke.ai.challenges.hunterkiller.bots.ShortCircuitRandomBot;
import net.codepoke.ai.challenges.hunterkiller.bots.SquadBot;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.RandomSorting;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.StaticSorting;
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

public class HunterKillerBotQueuer {

	public static final String BASE_PATH = System.getProperty("user.home") + "/";
	public static final String HKS_FILE_NAME = "state";
	public static final String BASE_FILE_EXTENSION = ".hks";
	public static final String RANDOMBOT_NAME = "RandomBot";
	public static final String RULESBOT_NAME = "RulesBot";
	public static final String SCOUTINGBOT_NAME = "ScoutingBot";
	public static final String SQUADBOT_NAME = "SquadBot";
	public static final String SERVER_QUEUE_ADDRESS = "ai.codepoke.net/competition/queue";
	public static final boolean TRAINING_MODE = true;
	public static final int TIME_BUFFER = 10;

	public static void main(String[] arg) {
		// simulate(true);
		// queue(TRAINING_MODE);
		// requestMatch(true);
		// requestGrudgeMatch(true);

		playFromFileState();

		// for (int i = 0; i < 100; i++) {
		// simulateWithoutVisuals();
		// }

		// BaseBot.setTimeBuffer(TIME_BUFFER);

		// spawnTestRooms();

		// Small wait to simulate people randomly queuing in
		// try {
		// Thread.sleep(2000);
		// } catch (InterruptedException e) {
		// e.printStackTrace();
		// }

		// Queue some RandomBots to play against these test rooms
		// Random r = new Random();
		// for (int i = 0; i < 6; i++) {
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

		// HunterKillerState testState = createTestState(new String[] { "0", "1" }, true);
		// playFromState(testState);
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
				RandomBot bot = new RandomBot();

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
		// new Thread() {
		// public void run() {

		GameRules<HunterKillerState, HunterKillerAction> rules = new HunterKillerRules();
		Array<HunterKillerAction> actions = new Array<HunterKillerAction>();

		HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { "A", "B" }, null);// ,
																														// "C",
																														// "D"
																														// },
																														// null);

		NMCBot bot = new NMCBot();

		Result result;
		do {
			HunterKillerState stateCopy = state.copy();
			stateCopy.prepare(state.getActivePlayerID());

			HunterKillerAction action = bot.handle(stateCopy);
			actions.add(action);

			result = rules.handle(state, action);

		} while (!result.isFinished() && result.isAccepted());

		// }
		// }.start();
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
				@SuppressWarnings("rawtypes")
				Array<BaseBot> bots = Array.with(	new HMCTSBot(false, new StaticSorting(), new ShortCircuitRandomBot()),
													new HMCTSBot(false, new RandomSorting(), new ShortCircuitRandomBot()));

				// Shuffle the bots, so that the player that starts is random
				bots.shuffle();

				// Create the initial state
				HunterKillerState state = new HunterKillerStateFactory().generateInitialState(new String[] { bots.get(0)
																													.getBotName(),
																											bots.get(1)
																												.getBotName() }, null);

				// Copy the initial state to serialize it
				HunterKillerState orgState = state.copy();

				Json json = new Json();

				listener.parseMessage(vis.getLastState(), json.toJson(state.getPlayers())); // Players
				listener.parseMessage(vis.getLastState(), json.toJson(Array.with(orgState))); // Initial State

				Result result;
				do {
					// Simulate preparing of the state
					HunterKillerState stateCopy = state.copy();
					stateCopy.prepare(state.getActivePlayerID());

					// Ask the bots in specific seats for an action
					HunterKillerAction action = bots.get(state.getActivePlayerID())
													.handle(stateCopy);

					actions.add(action);
					// Alternatively, send the action immediately:
					listener.parseMessage(vis.getLastState(), json.toJson(Array.with(action)));
					result = rules.handle(state, action);
				} while (!result.isFinished() && result.isAccepted());

				// listener.parseMessage(vis.getLastState(), json.toJson(actions));
			}
		}.start();
	}

	public static void writeHKSToFile(HunterKillerState state) {
		Json json = new Json();
		File file = new File(BASE_PATH + HKS_FILE_NAME + BASE_FILE_EXTENSION);
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

	public static void playFromFileState() {
		try {
			Json json = new Json();
			HunterKillerState state = json.fromJson(HunterKillerState.class, new FileReader(new File(BASE_PATH + HKS_FILE_NAME
																										+ BASE_FILE_EXTENSION)));
			playFromState(state);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static void playFromState(HunterKillerState state) {

		LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
		config.forceExit = true;

		final HunterKillerVisualization vis = new HunterKillerVisualization();

		new LwjglApplication(vis, config);

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
				// HMCTSBot botA = new HMCTSBot(false, new RandomSorting(), new ShortCircuitRandomBot());
				NMCBot botA = new NMCBot();
				NMCBot botB = new NMCBot();
				// LSIBot botB = new LSIBot();
				PerformanceBot botC = new PerformanceBot();
				PerformanceBot botD = new PerformanceBot();

				Json json = new Json();

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

			}
		}.start();
	}

	public static HunterKillerState createTestState(String[] playerNames, boolean createRedundantDimensions) {
		// Create the map setup for the test case
		MapSetup testMapSetup = new MapSetup("B___\n____\n____\n____");
		HunterKillerState state = new HunterKillerStateFactory().generateInitialStateFromPremade(	testMapSetup,
																									playerNames,
																									"nonRandomSections");
		Map testMap = state.getMap();

		// Test map we want to create looks like:
		// 0 S S S . . S S
		// S S . . . . . .
		// . . . . . . . .
		// . . . I S . . .
		// . . . . . . . .
		// . . . . . . S S
		// . . . S . . . .
		// S . S S . S S 1
		//
		// '0' and '1' are bases for those players

		if (createRedundantDimensions) {
			// Create soldiers for player 0 at [1,0], [2,0], [3,0], [6,0], [7,0], [0,1], [1,1]
			Soldier s00 = new Soldier(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.NORTH);
			testMap.registerGameObject(s00);
			testMap.place(new MapLocation(1, 0), s00);
			Soldier s01 = new Soldier(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.NORTH);
			testMap.registerGameObject(s01);
			testMap.place(new MapLocation(2, 0), s01);
			Soldier s02 = new Soldier(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.NORTH);
			testMap.registerGameObject(s02);
			testMap.place(new MapLocation(3, 0), s02);
			Soldier s03 = new Soldier(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.NORTH);
			testMap.registerGameObject(s03);
			testMap.place(new MapLocation(6, 0), s03);
			Soldier s04 = new Soldier(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.NORTH);
			testMap.registerGameObject(s04);
			testMap.place(new MapLocation(7, 0), s04);
			Soldier s05 = new Soldier(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.NORTH);
			testMap.registerGameObject(s05);
			testMap.place(new MapLocation(0, 1), s05);
			Soldier s06 = new Soldier(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.NORTH);
			testMap.registerGameObject(s06);
			testMap.place(new MapLocation(1, 1), s06);
		}
		// And an infected at [3,3] facing EAST
		Infected i07 = new Infected(0, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.EAST);
		// Reduce the infected's HP so much that it will be killed by the soldier if it does not attack
		i07.reduceHP(i07.getHpCurrent() - 1);
		testMap.registerGameObject(i07);
		testMap.place(new MapLocation(3, 3), i07);

		if (createRedundantDimensions) {
			// Create soldiers for player 1 at [6,5], [7,5], [3,6], [0,7], [2,7], [3,7], [5,7], [6,7]
			Soldier s10 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s10);
			testMap.place(new MapLocation(6, 5), s10);
			Soldier s11 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s11);
			testMap.place(new MapLocation(7, 5), s11);
			Soldier s12 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s12);
			testMap.place(new MapLocation(3, 6), s12);
			Soldier s13 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s13);
			testMap.place(new MapLocation(0, 7), s13);
			Soldier s14 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s14);
			testMap.place(new MapLocation(2, 7), s14);
			Soldier s15 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s15);
			testMap.place(new MapLocation(3, 7), s15);
			Soldier s16 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s16);
			testMap.place(new MapLocation(5, 7), s16);
			Soldier s17 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.SOUTH);
			testMap.registerGameObject(s17);
			testMap.place(new MapLocation(6, 7), s17);
		}
		// And a soldier at [4,3] facing WEST
		Soldier s18 = new Soldier(1, HunterKillerConstants.GAMEOBJECT_NOT_PLACED, Direction.WEST);
		testMap.registerGameObject(s18);
		testMap.place(new MapLocation(4, 3), s18);

		// Make sure all objects are correctly assigned to their players
		for (Player player : state.getPlayers()) {
			testMap.assignObjectsToPlayer(player);
		}

		testMap.updateFieldOfView();

		return state;
	}

}