package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.val;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.ai.SearchContext.Status;
import net.codepoke.lib.util.ai.State;
import net.codepoke.lib.util.ai.game.GameLogic;
import net.codepoke.lib.util.ai.game.Move;
import net.codepoke.lib.util.ai.search.GoalStrategy;
import net.codepoke.lib.util.ai.search.PlayoutStrategy;
import net.codepoke.lib.util.ai.search.SolutionStrategy;
import net.codepoke.lib.util.ai.search.StateEvaluation;
import net.codepoke.lib.util.ai.search.tree.TreeBackPropagation;
import net.codepoke.lib.util.ai.search.tree.TreeExpansion;
import net.codepoke.lib.util.ai.search.tree.TreeSearchNode;
import net.codepoke.lib.util.ai.search.tree.TreeSelection;
import net.codepoke.lib.util.ai.search.tree.mcts.MCTS;
import net.codepoke.lib.util.ai.search.tree.mcts.MCTS.MCTSBuilder;
import net.codepoke.lib.util.datastructures.MatrixMap;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

/**
 * A bot that uses Hierarchical Monte-Carlo Tree Search to create UnitOrders.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class HMCTSBot
		extends BaseBot<HunterKillerState, HunterKillerAction> {

	/**
	 * Unique identifier, supplied by the AI-Competition for HunterKiller.
	 */
	private static final String myUID = "";
	/**
	 * Name of this bot as it is registered to the AI-Competition for HunterKiller.
	 */
	@Getter
	public final String botName = "HMCTSBot";

	/**
	 * Rules of HunterKiller.
	 */
	HunterKillerRules rulesEngine = new HunterKillerRules();

	/**
	 * The knowledgebase we are using.
	 */
	KnowledgeBase kb;
	/**
	 * String used to identify the knowledge-layer that contains the distance to any enemy structure.
	 */
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE = "distance nearest enemy structure";
	/**
	 * Number indicating after which round the knowledgebase should not be updated anymore.
	 */
	private static final int KNOWLEDGEBASE_UPDATE_THRESHOLD_ROUND_NUMBER = 1;

	/**
	 * Number of iterations that MCTS should go through.
	 */
	private static final int MCTS_NUMBER_OF_ITERATIONS = 10000;
	/**
	 * Threshold on the number of visits a node should have before it can be expanded.
	 */
	private static final int MIN_T_VISIT_THRESHOLD_FOR_EXPANSION = 20;
	/**
	 * Threshold on the number of visits a node should have before its evaluation will be used for selection.
	 */
	private static final int SELECTION_VISIT_MINIMUM_FOR_EVALUATION = 50;
	/**
	 * Helps us set up a MCTS.
	 */
	private MCTSBuilder<Object, HMCTSState, PartialAction, Object, HunterKillerAction> builder;
	/**
	 * Contains the logic for the phases in MCTS.
	 */
	private HMCTSGameLogic gameLogic;
	/**
	 * A random way of completing an action during a MCTS-playout.
	 */
	private RandomActionCompletion randomCompletion;
	/**
	 * Contains the logic for how a playout is handled in MCTS.
	 */
	private HKPlayoutStrategy playout;
	/**
	 * Bot that can be called to simulate actions during a MCTS-playout.
	 */
	private BaseBot<HunterKillerState, HunterKillerAction> playoutBot;
	/**
	 * Number of rounds after which the playout of a node is cut off.
	 */
	private static final int PLAYOUT_ROUND_CUTOFF = 50;

	@SuppressWarnings("unchecked")
	public HMCTSBot() {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);

		// Create the utility classes that MCTS needs access to
		randomCompletion = new RandomActionCompletion();
		gameLogic = new HMCTSGameLogic(randomCompletion, new RandomUnitSorting());
		playoutBot = new PerformanceBot();
		playout = new HKPlayoutStrategy(roundCutoff(PLAYOUT_ROUND_CUTOFF), gameLogic, playoutBot);

		// Build the MCTS
		builder = MCTS.<Object, HMCTSBot.HMCTSState, HMCTSBot.PartialAction, Object, HunterKillerAction> builder();
		builder.expansion(TreeExpansion.Util.createMinimumTExpansion(MIN_T_VISIT_THRESHOLD_FOR_EXPANSION));
		builder.selection(TreeSelection.Util.selectBestNode(TreeSelection.Util.scoreUCB(1 / Math.sqrt(2)),
															SELECTION_VISIT_MINIMUM_FOR_EVALUATION));
		builder.evaluation(evaluateOnScoreOrUnitDistance(kb));
		builder.iterations(MCTS_NUMBER_OF_ITERATIONS);
		builder.backPropagation(TreeBackPropagation.Util.EVALUATE_ONCE_AND_COLOR);
		builder.solution(reconstructAction(randomCompletion));
		builder.playout(playout);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// Check if we need to wait
		waitTimeBuffer();

		// Check if we want to update our knowledgebase
		if (state.getCurrentRound() <= KNOWLEDGEBASE_UPDATE_THRESHOLD_ROUND_NUMBER) {
			kb.update(state);
		}

		// String builders for debugging
		StringBuilder possibleCheckFails = new StringBuilder();
		StringBuilder orderFails = new StringBuilder();

		// Create an action we can set our orders in
		HunterKillerAction action = new HunterKillerAction(state);

		// Get some things we'll need to access
		Player player = state.getActivePlayer();
		Map map = state.getMap();
		List<Structure> structures = player.getStructures(map);
		List<Unit> units = player.getUnits(map);

		// Do not go through a search if we have no units
		if (units.size() >= 1) {
			System.out.println("Starting an MCTS-search in round " + state.getCurrentRound());

			// We are going to use a special state as root for the search, so that we can keep track of all selected
			// orders
			HMCTSState searchState = new HMCTSState(state.copy(), gameLogic.sorting);

			// Setup a search with the search-state as source
			val context = SearchContext.gameSearchSetup(gameLogic, builder.build(), null, searchState, null);

			// Tell the context to create a report
			context.constructReport(true);

			// Search for an action
			context.execute();

			// Print the Search's report
			System.out.println(context.report());

			// Check if the search was successful
			if (context.status() != Status.Success) {
				System.err.println("ERROR; search-context returned with status: " + context.status());
				// Return a random action
				return RandomBot.createRandomAction(state);
			}

			// Get the solution of the search
			action = context.solution();

			// System.out.println("MCTS returned with " + action.getOrders().size + " orders for my " + units.size() +
			// " units.");
			System.out.println("");
		}

		// We don't want to do a search for Structures, so just ask RulesBot what to do.
		RulesBot.createOrders(rulesEngine, action, structures, units, state.copy(), possibleCheckFails, orderFails);

		return action;
	}

	/**
	 * State representation for the Hierarchical MCTS implementation for HunterKiller. Holds a {@link HunterKillerState}
	 * and {@link CombinedAction}.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@EqualsAndHashCode(callSuper = false)
	private class HMCTSState
			extends State {

		/**
		 * Game state.
		 */
		public HunterKillerState state;
		/**
		 * Contains orders for this state's active player.
		 */
		public CombinedAction combinedAction;

		/**
		 * Constructor.
		 * 
		 * @param state
		 *            The current game state.
		 * @param sorting
		 *            A sorting method to apply to the active player's Units. See
		 *            {@link CombinedAction#CombinedAction(int, IntArray)}.
		 */
		public HMCTSState(HunterKillerState state, UnitSortingStrategy sorting) {
			this.state = state;
			this.combinedAction = new CombinedAction(state.getCurrentPlayer(), sorting.sort(state));
		}

		/**
		 * Copy constructor.
		 * 
		 * @param other
		 *            State to copy.
		 */
		public HMCTSState(HMCTSState other) {
			this.state = other.state.copy();
			this.combinedAction = other.combinedAction.copy();
		}

		@Override
		public long hashMethod() {
			return hashCode();
		}

		@SuppressWarnings("unchecked")
		@Override
		public HMCTSState copy() {
			return new HMCTSState(this);
		}

		@Override
		public int getPlayer() {
			return state.getCurrentPlayer();
		}

		@Override
		public int getPlayers() {
			return state.getNumberOfPlayers();
		}

	}

	/**
	 * Container for combining several {@link PartialAction}s during the Hierarchical MCTS.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	private class CombinedAction {

		/**
		 * The ID of the player that is the acting player for this action.
		 */
		public int player;
		/**
		 * The amount of dimensions (or units) that are represented in this action.
		 */
		public int dimensions;
		/**
		 * The orders for each unit that have been assigned to them through the partial actions.
		 */
		public Array<UnitOrder> orders;
		/**
		 * The next dimension (index of unit) to expand, -1 if we still need to determine an ordering.
		 */
		public int nextDimension = -1;
		/**
		 * Ordered array containing the IDs of the units for which a partial action should be created.
		 * Set by the last partial action we applied.
		 */
		public IntArray currentOrdering = null;

		/**
		 * Constructor.
		 * 
		 * @param player
		 *            of the player that is the acting player for this action.
		 * @param currentOrdering
		 *            Ordered array containing the IDs of the units for which a partial action should be created.
		 */
		public CombinedAction(int player, IntArray currentOrdering) {
			this.player = player;
			this.dimensions = currentOrdering.size;
			this.orders = new Array<UnitOrder>(false, dimensions);
			this.nextDimension = 0;
			this.currentOrdering = currentOrdering;
		}

		/**
		 * Copy constructor.
		 * 
		 * @param other
		 *            The action to copy.
		 */
		public CombinedAction(CombinedAction other) {
			this.player = other.player;
			this.dimensions = other.dimensions;
			this.orders = new Array<UnitOrder>(other.orders);
			this.nextDimension = other.nextDimension;
			this.currentOrdering = new IntArray(other.currentOrdering);
		}

		/**
		 * Whether there are no choices to be made for this combined action
		 */
		public boolean isEmpty() {
			return dimensions == 0;
		}

		/**
		 * Returns whether this CombinedAction can be executed or not.
		 */
		public boolean isComplete() {
			return dimensions <= orders.size;
		}

		/**
		 * Adds a {@link UnitOrder} from a {@link PartialOrder} to this combined action.
		 * 
		 * @param action
		 *            The partial action from which the order should be added.
		 */
		public void pushOrder(PartialAction action) {
			orders.add(action.unitOrder);
		}

		/**
		 * Returns a deep copy of this action.
		 */
		public CombinedAction copy() {
			return new CombinedAction(this);
		}

	}

	/**
	 * Represents a {@link Move} in the Hierarchical MCTS setup. Contains an order for a unit as well as the current
	 * ordering for its {@link CombinedAction} and the next dimension that should be expanded in that action.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	private class PartialAction
			implements Move {

		/**
		 * The ID of the player that is the acting player for this action.
		 */
		public int player;
		/**
		 * The order for the unit.
		 */
		public UnitOrder unitOrder;
		/**
		 * The index of the dimension that should be expanded next.
		 */
		public int nextDimensionIndex;
		/**
		 * Ordered array containing the IDs of the units for which a partial action should be created.
		 */
		public IntArray currentOrdering;

		/**
		 * Constructor.
		 * 
		 * @param player
		 *            The ID of the player that is the acting player for this action.
		 * @param unitOrder
		 *            The order for the unit.
		 * @param nextDimensionIndex
		 *            The index of the dimension that should be expanded next.
		 * @param currentOrdering
		 *            Ordered array containing the IDs of the units for which a partial action should be created.
		 */
		public PartialAction(int player, UnitOrder unitOrder, int nextDimensionIndex, IntArray currentOrdering) {
			this.player = player;
			this.unitOrder = unitOrder;
			this.nextDimensionIndex = nextDimensionIndex;
			this.currentOrdering = currentOrdering;
		}

		@Override
		public int getPlayer() {
			return player;
		}

	}

	/**
	 * Core logic for applying the Hierarchical MCTS strategy to HunterKiller.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@AllArgsConstructor
	private class HMCTSGameLogic
			implements GameLogic<Object, HMCTSState, PartialAction, Object> {

		/**
		 * Strategy used to complete a {@link CombinedAction} or {@link HunterKillerAction} when an order could not be
		 * created for each unit/structure.
		 */
		ActionCompletionStrategy actionCompletion;
		/**
		 * The sorting used to determine which dimension (i.e. unit) to expand.
		 */
		public UnitSortingStrategy sorting;

		@Override
		public boolean done(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context, HMCTSState current) {
			return current.state.isDone();
		}

		@Override
		public HMCTSState apply(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context, HMCTSState state, PartialAction action) {
			// Add the partial action into the combined action
			state.combinedAction.pushOrder(action);

			System.out.println("Pushing action " + action.unitOrder);

			// Check if we still need to expand more dimensions
			if (!state.combinedAction.isComplete()) {				
				// We can grab the next dimension from the partial action
				state.combinedAction.nextDimension = action.nextDimensionIndex;
				state.combinedAction.currentOrdering = action.currentOrdering;
			} else {
				do {
					// Create a HunterKillerAction from the CombinedAction
					HunterKillerAction hkAction = new HunterKillerAction(state.state);
					for (UnitOrder order : state.combinedAction.orders) {
						hkAction.addOrder(order);
					}
					// Create orders for Structures
					actionCompletion.createStructureOrders(state.state, hkAction);

					// Apply the created action to the hkState, so that it moves forward to the next player.
					rulesEngine.handle(state.state, hkAction);

					// Then for the next player, create a sorted unexpanded dimension set (clean HMCTSState)
					state = new HMCTSState(state.state, sorting);

					// Check if we have expanded into an empty Combined Action (no units)
					// Keep skipping and applying the rules until we get to a non-empty combined action.
				} while (state.combinedAction.isEmpty() && !state.state.isDone());
			}

			return state;
		}

		/**
		 * Returns a collection of {@link PartialAction}s that is the result of expanding the next dimension in the
		 * provided state.
		 * 
		 * @param state
		 *            The state that should have its next dimension expanded.
		 */
		@Override
		public Iterable<? extends PartialAction> expand(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context,
				HMCTSState state) {
			Map map = state.state.getMap();
			// The next dimension to expand can be found in the combined action
			int nextDimension = state.combinedAction.nextDimension;
			// Get the ID of the next unit from the ordering that the combined action currently has
			int nextUnitID = state.combinedAction.currentOrdering.get(nextDimension);
			Unit nextUnit = (Unit) map.getObject(nextUnitID);

			// Generate all legal orders for this unit
			List<UnitOrder> orders = MoveGenerator.getAllLegalOrders(state.state, nextUnit);

			// Prune some orders we do not want to investigate
			RandomBot.filterFriendlyFire(orders, nextUnit, map);

			// Fill a collection of partial actions that encapsulate the possible unit-orders
			Array<PartialAction> partialActions = new Array<PartialAction>(false, orders.size());
			for (UnitOrder order : orders) {
				// Provide the partial actions with a link to the next dimension that should be expanded
				partialActions.add(new PartialAction(state.state.getCurrentPlayer(), order, nextDimension + 1,
														state.combinedAction.currentOrdering));
			}

			return partialActions;
		}

		@Override
		public double[] scores(HMCTSState current) {
			IntArray scores = current.state.getScores();
			double[] dScores = new double[scores.size];
			for (int i = 0; i < scores.size; i++) {
				dScores[i] = scores.get(i) * 1.0;
			}
			return dScores;
		}

	}

	/**
	 * Goal strategy that cuts the search off after it has progressed for a specific number of rounds.
	 * 
	 * @param cutoffThreshold
	 *            The number of rounds after which to cut off the search.
	 * @return Whether or not the goal state is reached.
	 */
	public static GoalStrategy<Object, HMCTSState, PartialAction, Object> roundCutoff(int cutoffThreshold) {
		return (context, state) -> {
			// Get the round for which we started the search
			int sourceRound = context.source().state.getCurrentRound();
			// We have reached our goal if the search has gone on for an amount of rounds e.t.o.g.t. the threshold.
			return (state.state.getCurrentRound() - sourceRound) >= cutoffThreshold;
		};
	}

	/**
	 * Evaluates a state on the player's score, or average distance of its units to any enemy structure.
	 * 
	 * @param kb
	 *            The knowledgebase to use when determining the distance of units to enemy structures.
	 * @return Double indicating the value of the state.
	 */
	public static StateEvaluation<HMCTSState, PartialAction, TreeSearchNode<HMCTSState, PartialAction>> evaluateOnScoreOrUnitDistance(
			KnowledgeBase kb) {
		return (context, node, state) -> {
			HunterKillerState gameState = state.state;

			// Check if we can determine a winner
			if (gameState.isDone()) {
				// Get the scores from the state
				IntArray scores = gameState.getScores();
				// Return the score of the active player in the context
				return scores.get(context.source().state.getActivePlayerID());
			}

			// If we are not done with the game yet, return how close our units are to enemy structures
			Map gameMap = gameState.getMap();
			MatrixMap distanceMap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
										.getMap();
			// Determine the minimum and maximum value of our valuemap
			int[] kbRange = distanceMap.findRange();
			// int minKBDistance = Math.max(0, kbRange[0]);
			int maxKBDistance = kbRange[1] + 1;

			double totalDistance = 0;
			double averageUnitDistance = 0;

			// We use the context here, because we want to evaluate from the root player's perspective.
			Player player = gameState.getPlayer(context.source()
														.getPlayer());
			List<Unit> units = player.getUnits(gameMap);
			if (!units.isEmpty()) {
				for (Unit unit : units) {
					MapLocation unitLocation = unit.getLocation();
					int unitDistance = distanceMap.get(unitLocation.getX(), unitLocation.getY());
					// Because the distance map is filled with enemy structures as source, lower values are closer.
					// However, selection will prefer higher values over lower ones, so take (1 - relative_distance).
					double relativeUnitDistance = 1 - (unitDistance / (maxKBDistance * 1.0));
					totalDistance += relativeUnitDistance;
				}
				averageUnitDistance = totalDistance / units.size();
			}

			// DecimalFormat df = new DecimalFormat("0.0000");
			// df.setRoundingMode(RoundingMode.HALF_UP);
			// System.out.println("Eval " + df.format(evaluationScore) + " at depth " + node.calculateDepth() + " | " +
			// units.size()
			// + " units");
			return averageUnitDistance;
		};
	}

	/**
	 * Constructs a HunterKillerAction containing the orders in the best nodes according to our selection strategy,
	 * starting from the final node as selected by the final-selection strategy.
	 * 
	 * @param completion
	 *            Strategy that completes a HunterKillerAction by creating order for units that do not have one yet.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static SolutionStrategy<TreeSearchNode<HMCTSState, PartialAction>, HunterKillerAction> reconstructAction(
			ActionCompletionStrategy completion) {
		return (context, node) -> {
			// Hold a reference to the action where we ended our search
			// Need this to fill up our combined action since this path might not have a partial action for each
			// dimension
			PartialAction endAction = node.getPayload();

			HunterKillerState orgState = node.getParent()
												.getState().state;
			val action = new HunterKillerAction(orgState);

			// Copy the partial action on this node to the main action
			action.addOrder(node.getPayload().unitOrder);
			int unitOrderCount = 1;

			val mcts = (MCTS) context.search();
			val selection = mcts.getSelectionStrategy();

			// We cut if the node is a leaf node or when there are no more units to give orders to
			while (!node.isLeaf() && unitOrderCount < endAction.currentOrdering.size) {
				// Select the next node
				node = selection.selectNextNode(context, node);
				// Add the node's order to the action
				unitOrderCount++;
				action.addOrder(node.getPayload().unitOrder);
			}

			// Could be that we haven't looked at all units.
			completion.fill(orgState, action, endAction.currentOrdering, endAction.nextDimensionIndex);

			return action;
		};
	}

	/**
	 * Represents a strategy that plays out a game of HunterKiller starting from a specific {@link HMCTSState}.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@AllArgsConstructor
	private class HKPlayoutStrategy
			implements PlayoutStrategy<Object, HMCTSState, PartialAction, Object> {

		/**
		 * The goal strategy which is used to determine if the playout should stop.
		 */
		GoalStrategy<Object, HMCTSState, PartialAction, Object> goal;
		/**
		 * The game logic used for the Hierarchical MCTS setup.
		 */
		HMCTSGameLogic gameLogic;
		/**
		 * Bot for HunterKiller that generates a {@link HunterKillerAction} to be used during the playout.
		 */
		BaseBot<HunterKillerState, HunterKillerAction> playoutBot;

		@Override
		public HMCTSState playout(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context, HMCTSState state) {
			// Convert order so far into HunterKillerAction
			HunterKillerAction action = new HunterKillerAction(state.state);
			for (UnitOrder order : state.combinedAction.orders) {
				action.addOrder(order);
			}

			// If we do not have an order for each dimension yet, use the ActionCompletionStrategy to generate them.
			if (state.combinedAction.dimensions > state.combinedAction.orders.size) {
				gameLogic.actionCompletion.fill(state.state,
												action,
												state.combinedAction.currentOrdering,
												state.combinedAction.nextDimension);
			}

			// Create orders for structures
			gameLogic.actionCompletion.createStructureOrders(state.state, action);

			// Apply the created action on the HunterKillerState
			rulesEngine.handle(state.state, action);

			// Call the playout bot to continuously play actions until the goal is reached.
			while (!goal.done(context, state)) {
				HunterKillerAction botAction = playoutBot.handle(state.state);
				rulesEngine.handle(state.state, botAction);
			}

			return state;
		}

	}

	/**
	 * Defines what a method that sorts units should support.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public static interface UnitSortingStrategy {

		/**
		 * Sorts the IDs of the units available to the active player into an ordered {@link IntArray}.
		 * 
		 * @param state
		 *            The game state that contains the units that should be sorted.
		 */
		public IntArray sort(HunterKillerState state);

	}

	/**
	 * Provides the sorting of units' IDs in a random way.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public static class RandomUnitSorting
			implements UnitSortingStrategy {

		@Override
		public IntArray sort(HunterKillerState state) {
			Player player = state.getActivePlayer();
			Map map = state.getMap();
			// Get all of the player's units
			List<Unit> units = player.getUnits(map);
			// Create an ordered IntArray from the unit's IDs
			IntArray output = IntArray.with(units.stream()
													.mapToInt(i -> i.getID())
													.toArray());

			System.out.println("New ordering: " + state.getCurrentRound() + " :: " + output + " :: for player " + state.getActivePlayerID());

			// Randomize the array
			output.shuffle();

			return output;
		}
	}

	/**
	 * Defines what a method that completes a {@link HunterKillerAction} should support.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public interface ActionCompletionStrategy {

		/**
		 * Adds a {@link UnitOrder} to an action for each unit-ID in the ordering, starting from a specified index.
		 * 
		 * @param state
		 *            The state for which the action is being created.
		 * @param action
		 *            The incomplete action.
		 * @param ordering
		 *            The current ordering of units.
		 * @param startIndex
		 *            The index in the ordering from where the filling should start.
		 */
		public void fill(HunterKillerState state, HunterKillerAction action, IntArray ordering, int startIndex);

		/**
		 * Creates a {@link StructureOrder} for each structure that the active player in the provided state controls.
		 * {@link ActionCompletionStrategy#fill(HunterKillerState, HunterKillerAction, IntArray, int)}.
		 */
		public void createStructureOrders(HunterKillerState state, HunterKillerAction action);

	}

	/**
	 * Provides a random way of completing a {@link HunterKillerAction}.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public static class RandomActionCompletion
			implements ActionCompletionStrategy {

		@Override
		public void fill(HunterKillerState state, HunterKillerAction action, IntArray ordering, int startIndex) {
			Map map = state.getMap();
			// Create a random order for the remaining IDs in the ordering
			for (int i = startIndex; i < ordering.size; i++) {
				GameObject object = map.getObject(ordering.get(i));
				if (object instanceof Unit) {
					UnitOrder order = BaseBot.getRandomOrder(state, (Unit) object);
					if (order != null)
						action.addOrder(order);
				} else if (object instanceof Structure) {
					StructureOrder order = BaseBot.getRandomOrder(state, (Structure) object);
					if (order != null)
						action.addOrder(order);
				}
			}
		}

		@Override
		public void createStructureOrders(HunterKillerState state, HunterKillerAction action) {
			Player player = state.getActivePlayer();
			Map map = state.getMap();
			// Go through all structures and create a random order
			for (Structure structure : player.getStructures(map)) {
				StructureOrder order = BaseBot.getRandomOrder(state, structure);
				if (order != null)
					action.addOrder(order);
			}
		}

	}

}
