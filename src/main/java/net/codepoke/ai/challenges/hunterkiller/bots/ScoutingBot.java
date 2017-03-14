package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;

import java.util.List;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.enums.Direction;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.HunterKillerVisualization;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.ai.challenges.hunterkiller.ui.StateVisualizationListener;
import net.codepoke.ai.network.AIBot;
import net.codepoke.lib.util.datastructures.MatrixMap;

/**
 * Represents a bot for the game of HunterKiller that tries to scout out the enemy positions.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class ScoutingBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	private static final boolean DEBUG_ImPossible = false;
	private static final boolean DEBUG_Fails = true;

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
		super("np6fb4jae0nk30f2v87ka9rh0", HunterKillerState.class, HunterKillerAction.class);

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

		// Create orders for our structures
		RulesBot.createOrders(rulesEngine, scoutingAction, orderCounter, structures, units, copyState, possibleCheckFails, orderFailures);

		// Create orders for our units
		ScoutingBot.createOrders(copyState, scoutingAction, rulesEngine, kb, units, orderCounter, possibleCheckFails, orderFailures);

		if (DEBUG_ImPossible && possibleCheckFails.length() > 0) {
			System.out.printf(	"RB(%d)R(%d)T(%d): some orders not possible, Reasons:%n%s%n",
								player.getID(),
								state.getCurrentRound(),
								state.getMap().currentTick,
								possibleCheckFails.toString());
		}
		if (DEBUG_Fails && orderFailures.length() > 0) {
			System.out.printf(	"RB(%d)R(%d)T(%d): some orders failed, Reasons:%n%s%n",
								player.getID(),
								state.getCurrentRound(),
								state.getMap().currentTick,
								orderFailures.toString());
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
		// Skip round 1
		if (state.getCurrentRound() == 1)
			return;

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
			InfluenceMaps.visualiseMap(valueMap, visualisation);
		}
	}

	/**
	 * Creates an order for a collection of units.
	 * {@link ScoutingBot#createOrder(HunterKillerState, HunterKillerAction, HunterKillerRules, KnowledgeBase, Unit, List, List, List, float[][], Player, Map, int, StringBuilder, StringBuilder)}
	 */
	public static void createOrders(HunterKillerState state, HunterKillerAction action, HunterKillerRules rules, KnowledgeBase kb,
			List<Unit> units, int orderIndex, StringBuilder possibleCheckFails, StringBuilder orderFailures) {

		Player player = state.getActivePlayer();
		Map map = state.getMap();
		List<Structure> enemyStructures = stream(map, Structure.class).filter(i -> i.isUnderControl() && !i.isControlledBy(player))
																		.toList();
		List<Unit> enemyUnits = stream(map, Unit.class).filter(i -> !i.isControlledBy(player))
														.toList();

		// Update the distances to enemy structures
		kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
			.update(state);

		// Get the distance map
		MatrixMap distancemap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
									.getMap();
		float[][] valueMap = InfluenceMaps.convertToValues(distancemap);

		// Go through all our Units
		for (Unit unit : units) {
			ScoutingBot.createOrder(state,
									action,
									rules,
									unit,
									units,
									enemyStructures,
									enemyUnits,
									valueMap,
									player,
									map,
									orderIndex,
									possibleCheckFails,
									orderFailures);
		}

	}

	/**
	 * Creates an order for a unit. Tries to move towards lower values of the supplied value map.
	 * {@link RulesBot#createOrder(HunterKillerRules, HunterKillerAction, int, Player, Map, List, Unit, com.badlogic.gdx.utils.Array, List, List, HunterKillerState, StringBuilder, StringBuilder)}
	 * 
	 * @param valueMap
	 *            Map of values that will be used to determine where the unit will move to.
	 */
	public static void createOrder(HunterKillerState state, HunterKillerAction action, HunterKillerRules rules, Unit unit,
			List<Unit> units, List<Structure> enemyStructures, List<Unit> enemyUnits, float[][] valueMap, Player player, Map map,
			int orderIndex, StringBuilder possibleCheckFails, StringBuilder orderFailures) {
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
		// Check if we are facing towards the location we want to move to
		Direction directionToLocation = MapLocation.getDirectionTo(unitLocation, minLocation);
		if (directionToLocation != null && unit.getOrientation() != directionToLocation) {
			UnitOrder order = unit.rotate(Direction.rotationRequiredToFace(unit, directionToLocation));
			// Add the order if it's possible
			if (rules.addOrderIfPossible(action, orderIndex, state, order, possibleCheckFails, orderFailures)) {
				// Don't create another order for this unit
				return;
			}
		}
		// Try to move to this location
		UnitOrder order = unit.move(MapLocation.getDirectionTo(unit.getLocation(), minLocation), map);
		// Add the order if it's possible
		if (rules.addOrderIfPossible(action, orderIndex, state, order, possibleCheckFails, orderFailures)) {
			// Don't create another order for this unit
			return;
		}

		// Fall back on the RulesBot-unit orders
		RulesBot.createOrder(	rules,
								action,
								orderIndex,
								player,
								map,
								units,
								unit,
								null,
								enemyStructures,
								enemyUnits,
								state,
								possibleCheckFails,
								orderFailures);
	}

}
