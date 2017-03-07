package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;

import java.util.List;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.HunterKillerVisualization;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.ai.challenges.hunterkiller.ui.StateVisualizationListener;
import net.codepoke.ai.network.AIBot;
import net.codepoke.lib.util.datastructures.MatrixMap;

import com.badlogic.gdx.graphics.Color;

/**
 * Represents a bot for the game of HunterKiller that tries to scout out the enemy positions.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class ScoutingBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	HunterKillerRules rulesEngine = new HunterKillerRules();

	HunterKillerVisualization visualisation;

	StateVisualizationListener<HunterKillerState, HunterKillerAction> listener;

	private static final int PLAYER_ID_NOT_SET = -1;
	private int playerID = PLAYER_ID_NOT_SET;

	KnowledgeBase kb;
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE = "distance nearest enemy structure";

	public ScoutingBot() {
		this(null);
	}

	public ScoutingBot(HunterKillerVisualization vis) {
		super("", HunterKillerState.class, HunterKillerAction.class);

		// Check if there is a visualization we can rely on
		if (vis != null) {
			visualisation = vis;

			// Subscribe to states being visualized
			listener = new StateVisualizationListener<HunterKillerState, HunterKillerAction>() {

				@Override
				public void newStateVisualized(HunterKillerState oldState, HunterKillerAction action, HunterKillerState newState) {
					handleStateVisualized(newState);
				}
			};
			vis.addStateVisualizationListeners(listener);
		}

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// Assume we are being called to handle this state for a reason, which means this instance of the bot is the
		// currently active player
		if (playerID == PLAYER_ID_NOT_SET) {
			playerID = state.getActivePlayerID();
		}

		// string builders for debugging
		StringBuilder possibleCheckFails = new StringBuilder();
		StringBuilder orderFailures = new StringBuilder();

		// Create an action
		HunterKillerAction scoutingAction = new HunterKillerAction(state);

		// Make a copy of the state, so we can mutate it
		HunterKillerState copyState = state.copy();

		// Prepare the state for this player, this is a temporary hack to simulate the server-environment
		copyState.prepare(state.getActivePlayerID());
		Player player = copyState.getActivePlayer();
		Map map = copyState.getMap();

		// Maintain a counter on the amount of orders we create, to correctly set their index in the action
		int orderCounter = 0;

		// Get some things we'll need to access
		List<Structure> structures = player.getStructures(map);
		List<Unit> units = player.getUnits(map);
		List<Structure> enemyStructures = stream(map, Structure.class).filter(i -> i.isUnderControl() && !i.isControlledBy(player))
																		.toList();
		List<Unit> enemyUnits = stream(map, Unit.class).filter(i -> !i.isControlledBy(player))
														.toList();

		// Create orders for our structures
		RulesBot.createStructureOrders(	rulesEngine,
										scoutingAction,
										orderCounter,
										structures,
										units,
										copyState,
										possibleCheckFails,
										orderFailures);

		// Update the distances to enemy structures
		kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
			.update(state);

		// Get the distance map
		MatrixMap distancemap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
									.getMap();
		float[][] valueMap = InfluenceMaps.convertToValues(distancemap);

		// Go through all our Units
		for (Unit unit : units) {
			// Get the value of the unit's location in the value map
			MapLocation unitLocation = unit.getLocation();
			// Get the surrounding area around the unit's location
			List<MapLocation> area = state.getMap()
											.getNeighbours(unitLocation);
			float minValue = valueMap[unitLocation.getX()][unitLocation.getY()];
			MapLocation minLocation = unitLocation;
			for (MapLocation loc : area) {
				float locValue = valueMap[loc.getX()][loc.getY()];
				if (locValue > 0 && locValue < minValue) {
					minValue = locValue;
					minLocation = loc;
				}
			}
			// Try to move to this location
			UnitOrder order = unit.move(MapLocation.getDirectionTo(unit.getLocation(), minLocation), map);
			// Add the order if it's possible
			if (rulesEngine.addOrderIfPossible(scoutingAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
				// Don't create another order for this unit
				continue;
			}

			// If we didn't order a move for this unit, check if anything can be attacked.
			// TODO: fall back on rules-bot
		}

		// Return our created action
		return scoutingAction;
	}

	/**
	 * Handles the event where a state is visualised by the {@link HunterKillerVisualization}.
	 * 
	 * @param state
	 *            The state that is in the process of being visualized.
	 */
	private void handleStateVisualized(HunterKillerState state) {
		// Only send a value map visualisation when we are the active player
		if (state.getActivePlayerID() == playerID) {

			// Update the distances to enemy structures
			kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
				.update(state);

			// Get the distance map
			MatrixMap distancemap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
										.getMap();
			float[][] valueMap = InfluenceMaps.convertToValues(distancemap);

			// Visualise it
			visualiseMap(valueMap, visualisation);
		}
	}

	/**
	 * Sends an array of values to the provided visualization for rendering.
	 * 
	 * @param map
	 *            2-dimensional array containing the normalised values that should be rendered.
	 * @param vis
	 *            The visualization of the game.
	 */
	public static void visualiseMap(float[][] map, HunterKillerVisualization vis) {
		// Define the ignore color as having an alpha of 0
		Color ignore = Color.GRAY.cpy();
		ignore.a = 0f;
		// Set the value array into the visualisation
		vis.visualise(map, Color.GREEN, Color.BLUE, Color.RED, ignore);
	}

}
