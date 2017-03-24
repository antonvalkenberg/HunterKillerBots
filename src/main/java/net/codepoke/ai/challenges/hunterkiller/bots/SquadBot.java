/**
 * 
 */
package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;

import java.util.List;

import lombok.Getter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.HunterKillerVisualization;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.ai.challenges.hunterkiller.ui.StateVisualizationListener;
import net.codepoke.ai.network.AIBot;
import net.codepoke.lib.util.datastructures.MatrixMap;

import com.badlogic.gdx.utils.Array;

/**
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class SquadBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	private static final String myUID = "69648ck15d5qlgid2d3lbaroqo";
	
	@Getter
	public final String botName = "SquadBot";

	HunterKillerRules rulesEngine = new HunterKillerRules();

	HunterKillerVisualization visualisation;

	StateVisualizationListener<HunterKillerState, HunterKillerAction> listener;

	private static final int SQUAD_MIN_PRESENCE_VALUE = 2;

	private static final int PLAYER_ID_NOT_SET = -1;
	private int playerID = PLAYER_ID_NOT_SET;

	KnowledgeBase kb;
	// private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE = "distance nearest enemy structure";
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY = "distance nearest enemy";
	// private static final String KNOWLEDGE_LAYER_ALLY_PRESENCE = "amount of ally presence";
	private static final String KNOWLEDGE_LAYER_SQUAD_PRESENCE = "amount of squad presence";

	public SquadBot() {
		this(null);
	}

	public SquadBot(HunterKillerVisualization vis) {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);

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
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY, InfluenceMaps::calculateDistanceToAnyEnemy);
		kb.put(KNOWLEDGE_LAYER_SQUAD_PRESENCE, InfluenceMaps::calculateSquadPresence);
	}

	/**
	 * Handles the event where a state is visualised by the {@link HunterKillerVisualization}.
	 * 
	 * @param state
	 *            The state that is in the process of being visualized.
	 */
	private void handleStateVisualized(HunterKillerState state) {
		// Skip round 1, this was causing a crash when going back through states in the GUI (specifically from 2 to 1).
		if (state.getCurrentRound() == 1)
			return;

		// Only send a value map visualisation when we are the active player
		if (state.getActivePlayerID() == playerID) {

			// Update the knowledgebase
			kb.update(state);

			// Get the distance map
			MatrixMap distancemap = kb.get(KNOWLEDGE_LAYER_SQUAD_PRESENCE)
										.getMap();
			float[][] valueMap = InfluenceMaps.convertToValues(distancemap);

			// Visualise it
			InfluenceMaps.visualiseMap(valueMap, visualisation);
		}
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// Assume we are being called to handle this state for a reason, which means this instance of the bot is the
		// currently active player
		if (playerID == PLAYER_ID_NOT_SET) {
			playerID = state.getActivePlayerID();
		}

		// String builders for debugging
		StringBuilder possibleCheckFails = new StringBuilder();
		StringBuilder orderFailures = new StringBuilder();

		// Create an action
		HunterKillerAction squadAction = new HunterKillerAction(state);

		// Update our knowledgebase with the new state
		kb.update(state);

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

		List<GameObject> attackableEnemies = stream(map, GameObject.class).filter(i -> {
			if (i instanceof Unit) {
				return !((Unit) i).isControlledBy(player);
			} else if (i instanceof Structure) {
				Structure s = (Structure) i;
				return !s.isControlledBy(player) && s.isUnderControl() && s.isDestructible();
			} else
				return false;
		})
																			.toList();

		// Create orders for our structures
		RulesBot.createOrders(rulesEngine, squadAction, orderCounter, structures, units, copyState, possibleCheckFails, orderFailures);

		// Go through our units
		for (Unit unit : units) {

			// See if we have anything we need to react to
			UnitOrder reactiveOrder = RulesBot.getReactiveOrder(rulesEngine,
																player,
																map,
																units,
																unit,
																attackableEnemies,
																copyState,
																possibleCheckFails);
			if (reactiveOrder != null) {
				if (rulesEngine.addOrderIfPossible(squadAction, orderCounter, copyState, reactiveOrder, possibleCheckFails, orderFailures)) {
					continue;
				}
			}

			// Get the squad influence map
			MatrixMap squadMap = kb.get(KNOWLEDGE_LAYER_SQUAD_PRESENCE)
									.getMap();

			// See if we can get a strategic order
			UnitOrder squadOrder = getStrategicOrder(copyState, rulesEngine, unit, squadMap, possibleCheckFails);
			if (squadOrder != null) {
				if (rulesEngine.addOrderIfPossible(squadAction, orderCounter, copyState, squadOrder, possibleCheckFails, orderFailures)) {
					continue;
				}
			}

			// Get the distance to enemies map
			float[][] distanceMap = InfluenceMaps.convertToValues(kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY)
																	.getMap());

			// See if we can get a strategic order from ScoutingBot
			UnitOrder strategicOrder = ScoutingBot.getStrategicOrder(copyState, rulesEngine, unit, distanceMap, possibleCheckFails);
			if (strategicOrder != null) {
				if (rulesEngine.addOrderIfPossible(squadAction, orderCounter, copyState, strategicOrder, possibleCheckFails, orderFailures)) {
					continue;
				}
			}

		}

		// Return our created action
		return squadAction;
	}

	/**
	 * Creates an order for a unit. Tries to .
	 * 
	 * @param squadMap
	 *            Map of values that will be used to determine where the unit will move to.
	 */
	public static UnitOrder getStrategicOrder(HunterKillerState state, HunterKillerRules rules, Unit unit, MatrixMap squadMap,
			StringBuilder possibleCheckFails) {

		// Get the value of the unit's location in the value map
		MapLocation unitLocation = unit.getLocation();
		int unitValue = squadMap.get(unitLocation.getX(), unitLocation.getY());

		// Check if we match/exceed our required value
		if (unitValue >= SQUAD_MIN_PRESENCE_VALUE)
			return null;

		// Get the surrounding area around the unit's location
		Array<MapLocation> area = Array.with(state.getMap()
													.getNeighbours(unitLocation)
													.toArray(new MapLocation[0]));
		// Shuffle so that not every unit chooses the same location when multiple are similar
		area.shuffle();
		MapLocation maxLocation = unitLocation;
		int maxValue = unitValue;
		for (MapLocation loc : area) {
			int locValue = squadMap.get(loc.getX(), loc.getY());
			if (locValue > maxValue) {
				maxValue = locValue;
				maxLocation = loc;
			}
		}

		// Check if we are facing towards the location we want to move to
		// Direction directionToLocation = MapLocation.getDirectionTo(unitLocation, minLocation);
		// if (directionToLocation != null && unit.getOrientation() != directionToLocation) {
		// UnitOrder order = unit.rotate(Direction.rotationRequiredToFace(unit, directionToLocation));
		// if (rules.isOrderPossible(state, order, possibleCheckFails)) {
		// return order;
		// }
		// }

		// Try to move to this location
		UnitOrder order = unit.move(MapLocation.getDirectionTo(unit.getLocation(), maxLocation), state.getMap());
		// Add the order if it's possible
		if (rules.isOrderPossible(state, order, possibleCheckFails)) {
			return order;
		}

		return null;
	}

}
