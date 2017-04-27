package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

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
import net.codepoke.ai.challenge.hunterkiller.gameobjects.Controlled;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.HunterKillerOrder;
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
import net.codepoke.lib.util.common.Stopwatch;
import net.codepoke.lib.util.datastructures.MatrixMap;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntMap;

/**
 * A bot that uses Hierarchical Monte-Carlo Tree Search to create HunterKillerOrders.
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
	private static final int MCTS_NUMBER_OF_ITERATIONS = 20000;
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
	 * Information on object-orders that we want to retain during the search.
	 */
	private SideInformation sideInformation;
	/**
	 * Number of rounds after which the playout of a node is cut off.
	 */
	private static final int PLAYOUT_ROUND_CUTOFF = 20;

	private static final int GAME_WIN_EVALUATION = 10000000;
	private static final int GAME_LOSS_EVALUATION = -10 * GAME_WIN_EVALUATION;

	@SuppressWarnings("unchecked")
	public HMCTSBot(boolean useSideInformation) {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);

		// Create the utility classes that MCTS needs access to
		randomCompletion = new RandomActionCompletion();
		gameLogic = new HMCTSGameLogic(randomCompletion, new RandomControlledObjectSorting());
		sideInformation = new SideInformation(gameLogic, roundCutoff(PLAYOUT_ROUND_CUTOFF), randomCompletion);
		playoutBot = new ShortCircuitRandomBot();
		playout = new HKPlayoutStrategy(gameLogic, roundCutoff(PLAYOUT_ROUND_CUTOFF), playoutBot);

		// Build the MCTS
		builder = MCTS.<Object, HMCTSBot.HMCTSState, HMCTSBot.PartialAction, Object, HunterKillerAction> builder();
		builder.expansion(TreeExpansion.Util.createMinimumTExpansion(MIN_T_VISIT_THRESHOLD_FOR_EXPANSION));
		builder.selection(TreeSelection.Util.selectBestNode(TreeSelection.Util.scoreUCB(1 / Math.sqrt(2)),
															SELECTION_VISIT_MINIMUM_FOR_EVALUATION));
		builder.evaluation(evaluate(kb));
		builder.iterations(MCTS_NUMBER_OF_ITERATIONS);
		if (useSideInformation) {
			builder.backPropagation(sideInformation);
			builder.solution(sideInformation);
			builder.playout(sideInformation);
		} else {
			builder.backPropagation(TreeBackPropagation.Util.EVALUATE_ONCE_AND_COLOR);
			builder.solution(reconstructAction(randomCompletion));
			builder.playout(playout);
		}
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		Stopwatch actionTimer = new Stopwatch();
		actionTimer.start();

		// Check if we need to wait
		waitTimeBuffer();

		// Check if we want to update our knowledgebase
		if (state.getCurrentRound() <= KNOWLEDGEBASE_UPDATE_THRESHOLD_ROUND_NUMBER) {
			kb.update(state);
		}

		// Check that we can even issue any orders
		if (state.getActivePlayer()
					.getUnitIDs().size == 0) {
			boolean canDoSomething = false;
			for (Structure structure : state.getActivePlayer()
											.getStructures(state.getMap())) {
				if (structure.canSpawnAUnit(state))
					canDoSomething = true;
			}
			if (!canDoSomething)
				return state.createNullMove();
		}

		System.out.println("Starting an MCTS-search in round " + state.getCurrentRound());

		// We are going to use a special state as root for the search, so that we can keep track of all selected
		// orders
		HMCTSState searchState = new HMCTSState(state.copy(), gameLogic.sorting);

		// Reset the side information before searching
		sideInformation.resetInformation();

		// Setup a search with the search-state as source
		val context = SearchContext.gameSearchSetup(gameLogic, builder.build(), null, searchState, null);

		// Search for an action
		context.execute();

		// Check if the search was successful
		if (context.status() != Status.Success) {
			System.err.println("ERROR; search-context returned with status: " + context.status());
			// Return a random action
			return RandomBot.createRandomAction(state);
		}

		// Get the solution of the search
		HunterKillerAction action = context.solution();

		long time = actionTimer.end();
		System.out.println("MCTS returned with " + action.getOrders().size + " orders.");
		System.out.println("My action calculation time was " + TimeUnit.MILLISECONDS.convert(time, TimeUnit.NANOSECONDS) + " ms");
		System.out.println("");

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
		 *            A sorting method to apply to the active player's controlled objects. See
		 *            {@link CombinedAction#CombinedAction(int, IntArray)}.
		 */
		public HMCTSState(HunterKillerState state, RandomControlledObjectSorting sorting) {
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
		 * The orders for each object that have been assigned to them through the partial actions.
		 */
		public Array<HunterKillerOrder> orders;
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
			this.orders = new Array<HunterKillerOrder>(false, dimensions);
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
			this.orders = new Array<HunterKillerOrder>(other.orders);
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
		 * Adds a {@link HunterKillerOrder} from a {@link PartialOrder} to this combined action.
		 * 
		 * @param action
		 *            The partial action from which the order should be added.
		 */
		public void pushOrder(PartialAction action) {
			orders.add(action.order);
		}

		/**
		 * Returns a deep copy of this action.
		 */
		public CombinedAction copy() {
			return new CombinedAction(this);
		}

		public String toString() {
			return "Ac; playerID " + player + " | dimensions " + dimensions + " | orders " + orders.size + " | nextD " + nextDimension
					+ " | ordering " + currentOrdering;
		}

	}

	/**
	 * Represents a {@link Move} in the Hierarchical MCTS setup. Contains an order for an object as well as the current
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
		 * The order for the object.
		 */
		public HunterKillerOrder order;
		/**
		 * The index of the dimension that should be expanded next.
		 */
		public int nextDimensionIndex;
		/**
		 * Ordered array containing the IDs of the objects for which a partial action should be created.
		 */
		public IntArray currentOrdering;

		/**
		 * Constructor.
		 * 
		 * @param player
		 *            The ID of the player that is the acting player for this action.
		 * @param order
		 *            The order for the object.
		 * @param nextDimensionIndex
		 *            The index of the dimension that should be expanded next.
		 * @param currentOrdering
		 *            Ordered array containing the IDs of the units for which a partial action should be created.
		 */
		public PartialAction(int player, HunterKillerOrder order, int nextDimensionIndex, IntArray currentOrdering) {
			this.player = player;
			this.order = order;
			this.nextDimensionIndex = nextDimensionIndex;
			this.currentOrdering = currentOrdering;
		}

		@Override
		public int getPlayer() {
			return player;
		}

		public String toString() {
			return "Ap; playerID " + player + " | order " + order + " | nextIndex " + nextDimensionIndex + " | ordering " + currentOrdering;
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
		 * The sorting used to determine which dimension (i.e. controlled object) to expand.
		 */
		public RandomControlledObjectSorting sorting;

		@Override
		public boolean done(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context, HMCTSState current) {
			return current.state.isDone();
		}

		@Override
		public HMCTSState apply(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context, HMCTSState state, PartialAction action) {
			// Add the partial action into the combined action
			state.combinedAction.pushOrder(action);

			// Check if we still need to expand more dimensions
			if (!state.combinedAction.isComplete()) {

				// We can grab the next dimension from the partial action
				state.combinedAction.nextDimension = action.nextDimensionIndex;
				state.combinedAction.currentOrdering = action.currentOrdering;
				state.combinedAction.dimensions = action.currentOrdering.size;

			} else {
				do {
					// Create a HunterKillerAction from the CombinedAction
					HunterKillerAction hkAction = new HunterKillerAction(state.state);
					for (HunterKillerOrder order : state.combinedAction.orders) {
						hkAction.addOrder(order);
					}

					// Apply the created action to the hkState, so that it moves forward to the next player.
					rulesEngine.handle(state.state, hkAction);

					// Then for the next player, create a sorted unexpanded dimension set (clean HMCTSState)
					state = new HMCTSState(state.state, sorting);

					// Check if we have advanced into an empty Combined Action (no legal orders available)
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
			// Get the ID of the next object from the ordering that the combined action currently has
			int nextObjectID = state.combinedAction.currentOrdering.get(nextDimension);
			// Find the object on the map
			GameObject object = map.getObject(nextObjectID);

			if (object instanceof Unit) {
				Unit nextUnit = (Unit) object;

				// Generate all legal orders for this unit
				List<UnitOrder> orders = MoveGenerator.getAllLegalOrders(state.state, nextUnit);

				// Prune some orders we do not want to investigate
				BaseBot.filterFriendlyFire(orders, nextUnit, map);

				// Fill a collection of partial actions that encapsulate the possible unit-orders
				Array<PartialAction> partialActions = new Array<PartialAction>(false, orders.size());
				for (UnitOrder order : orders) {
					// Provide the partial actions with a link to the next dimension that should be expanded
					partialActions.add(new PartialAction(state.state.getCurrentPlayer(), order, nextDimension + 1,
															state.combinedAction.currentOrdering));
				}
				return partialActions;

			} else if (object instanceof Structure) {
				Structure nextStructure = (Structure) object;

				// Generate all legal order for this structure
				List<StructureOrder> orders = MoveGenerator.getAllLegalOrders(state.state, nextStructure);

				// Fill a collection of partial actions that encapsulate the possible structure-orders
				Array<PartialAction> partialActions = new Array<PartialAction>(false, orders.size());
				for (StructureOrder order : orders) {
					// Provide the partial actions with a link to the next dimension that should be expanded
					partialActions.add(new PartialAction(state.state.getCurrentPlayer(), order, nextDimension + 1,
															state.combinedAction.currentOrdering));
				}
				return partialActions;
			} else {
				throw new RuntimeException("Unknown type for expansion: " + object.getClass()
																					.getName());
			}
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
			// We have reached our goal if the search has gone on for an amount of rounds >= the threshold, or if the
			// game has finished.
			return (state.state.getCurrentRound() - sourceRound) >= cutoffThreshold || state.state.isDone();
		};
	}

	/**
	 * Evaluates a state. {@link HMCTSBot#evaluateOnScoreOrUnitDistance(KnowledgeBase)}
	 */
	public static StateEvaluation<HMCTSState, PartialAction, TreeSearchNode<HMCTSState, PartialAction>> evaluate(KnowledgeBase kb) {
		return (context, node, state) -> {
			HunterKillerState gameState = state.state;
			// NOTE: This entire method is written from the root player's perspective
			int rootPlayerID = context.source()
										.getPlayer();

			// Check if we can determine a winner
			int endEvaluation = 0;
			if (gameState.isDone()) {
				// Get the scores from the state
				IntArray scores = gameState.getScores();
				// Determine the winning score
				int winner = -1;
				int winningScore = -1;
				for (int i = 0; i < scores.size; i++) {
					int score = scores.get(i);
					if (score > winningScore) {
						winner = i;
						winningScore = score;
					}
				}
				// Check if the root player won
				endEvaluation = winner == rootPlayerID ? GAME_WIN_EVALUATION : GAME_LOSS_EVALUATION;
			}

			// We use the context here, because we want to evaluate from the root player's perspective.
			Map gameMap = gameState.getMap();
			Player rootPlayer = gameState.getPlayer(rootPlayerID);

			// Calculate the amount of units the root player has
			List<Unit> units = rootPlayer.getUnits(gameMap);
			int rootUnits = units.size();

			// Calculate the root player's Field-of-View size
			// NOTE: expensive calculation
			int rootFoV = rootPlayer.getCombinedFieldOfView(gameMap)
									.size();

			// Calculate how far along our farthest unit is to an enemy structure
			MatrixMap distanceMap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
										.getMap();
			// Determine the maximum distance in our map
			int maxKBDistance = distanceMap.findRange()[1];
			// Find the minimum distance for our units (note that the KB is filled with enemy structures as source)
			int minUnitDistance = maxKBDistance;
			for (Unit unit : units) {
				MapLocation unitLocation = unit.getLocation();
				// Because the distance map is filled with enemy structures as source, lower values are closer.
				int unitDistance = distanceMap.get(unitLocation.getX(), unitLocation.getY());
				if (unitDistance < minUnitDistance)
					minUnitDistance = unitDistance;
			}
			// The farthest unit is a number of steps away from an enemy structure equal to the maximum distance minus
			// its distance
			int unitProgress = maxKBDistance - minUnitDistance;

			// Calculate the difference in score between the root player and other players
			int currentScore = rootPlayer.getScore();
			int scoreDelta = currentScore;
			for (Player player : gameState.getPlayers()) {
				if (player.getID() != rootPlayerID) {
					int opponentDelta = currentScore - player.getScore();
					if (opponentDelta < scoreDelta)
						scoreDelta = opponentDelta;
				}
			}

			int evaluation = endEvaluation + (scoreDelta * 1000) + (rootFoV * 100) + (unitProgress * 10) + (rootUnits);

			// Reward evaluations that are further in the future less than earlier ones
			int playoutProgress = gameState.getCurrentRound() - context.source().state.getCurrentRound();
			float decay = 0.5f + ((PLAYOUT_ROUND_CUTOFF - playoutProgress) * (0.5f / PLAYOUT_ROUND_CUTOFF));

			return decay * evaluation;
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
			HunterKillerAction action = new HunterKillerAction(orgState);

			// Copy the partial action on this node to the main action
			action.addOrder(node.getPayload().order);
			int orderCount = 1;

			val mcts = (MCTS) context.search();
			val selection = mcts.getSelectionStrategy();

			// We cut if the node is a leaf node or when there are no more objects to give orders to
			while (!node.isLeaf() && orderCount < endAction.currentOrdering.size) {
				// Select the next node
				node = selection.selectNextNode(context, node);
				// Add the node's order to the action
				orderCount++;
				action.addOrder(node.getPayload().order);
			}

			// Could be that we haven't looked at all objects.
			Array<HunterKillerOrder> filledOrders = completion.fill(orgState, endAction.currentOrdering, endAction.nextDimensionIndex);
			// Add the generated orders to the action
			for (HunterKillerOrder order : filledOrders) {
				action.addOrder(order);
			}

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
		 * The game logic used for the Hierarchical MCTS setup.
		 */
		HMCTSGameLogic gameLogic;
		/**
		 * The goal strategy which is used to determine if the playout should stop.
		 */
		GoalStrategy<Object, HMCTSState, PartialAction, Object> goal;
		/**
		 * Bot for HunterKiller that generates a {@link HunterKillerAction} to be used during the playout.
		 */
		BaseBot<HunterKillerState, HunterKillerAction> playoutBot;

		@Override
		public HMCTSState playout(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context, HMCTSState state) {
			// Convert order so far into HunterKillerAction
			HunterKillerAction action = new HunterKillerAction(state.state);
			for (HunterKillerOrder order : state.combinedAction.orders) {
				action.addOrder(order);
			}

			// If we do not have an order for each dimension yet, use the ActionCompletionStrategy to generate them.
			if (!state.combinedAction.isComplete()) {
				Array<HunterKillerOrder> filledOrders = gameLogic.actionCompletion.fill(state.state,
																						state.combinedAction.currentOrdering,
																						state.combinedAction.nextDimension);
				// Add the generated orders to the action
				for (HunterKillerOrder order : filledOrders) {
					action.addOrder(order);
				}

			}

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
	 * Represents a strategy to retain statistics on partial actions during a search.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	private class SideInformation
			implements PlayoutStrategy<Object, HMCTSState, PartialAction, Object>, TreeBackPropagation<HMCTSState, PartialAction>,
			SolutionStrategy<TreeSearchNode<HMCTSState, PartialAction>, HunterKillerAction> {

		/**
		 * Contains the logic for the phases in MCTS.
		 */
		HMCTSGameLogic gameLogic;
		/**
		 * The goal strategy which is used to determine if the playout should stop.
		 */
		GoalStrategy<Object, HMCTSState, PartialAction, Object> goal;
		/**
		 * Strategy for completing a HunterKillerAction when our side information does not provide a result.
		 */
		ActionCompletionStrategy actionCompletion;

		/**
		 * Table containing a HashMap of {@link OrderStatistics} for each {@link HunterKillerOrder}, indexed by
		 * {@link GameObject} ID.
		 */
		IntMap<HashMap<HunterKillerOrder, OrderStatistics>> sideInformation;
		/**
		 * Collection of orders that were filled by the action completion during a playout.
		 */
		Array<HunterKillerOrder> playoutFilledOrders;

		/**
		 * Constructor.
		 */
		public SideInformation(HMCTSGameLogic gameLogic, GoalStrategy<Object, HMCTSState, PartialAction, Object> goal,
								ActionCompletionStrategy actionCompletion) {
			this.gameLogic = gameLogic;
			this.goal = goal;
			this.actionCompletion = actionCompletion;
			this.sideInformation = new IntMap<HashMap<HunterKillerOrder, OrderStatistics>>();
			this.playoutFilledOrders = null;
		}

		/**
		 * Updates the information for the specified {@link GameObject} and {@link HunterKillerOrder}.
		 * 
		 * @param objectID
		 *            The unique identifier of the game object.
		 * @param order
		 *            The order.
		 * @param evaluation
		 *            The evaluation returned from the playout.
		 */
		public void updateInformation(int objectID, HunterKillerOrder order, double evaluation) {
			HashMap<HunterKillerOrder, OrderStatistics> objectOrderMap = sideInformation.get(objectID);

			// Check if the side information contains an entry for this object
			if (objectOrderMap == null) {
				objectOrderMap = new HashMap<HunterKillerOrder, OrderStatistics>();
				sideInformation.put(objectID, objectOrderMap);
			}

			// Check if the object's HashMap contains an entry for this order
			OrderStatistics stats = objectOrderMap.get(order);
			if (stats == null) {
				objectOrderMap.put(order, new OrderStatistics(evaluation));
			} else {
				// Add the evaluation to this order's statistics
				stats.addValue(evaluation);
			}
		}

		/**
		 * Returns the {@link HunterKillerOrder} with the highest average value for the specified {@link GameObject}.
		 * Note: this method will return null if no information is stored for the object, or if there is no entry with
		 * an average value higher than 0.
		 * 
		 * @param objectID
		 *            The unique identifier of the game object.
		 */
		public HunterKillerOrder getBestAction(int objectID) {
			// Check if we have any information on this object
			if (!sideInformation.containsKey(objectID))
				return null;

			// Get the mapping of orders and statistics
			HashMap<HunterKillerOrder, OrderStatistics> objectOrderMap = sideInformation.get(objectID);
			// Go through the order statistics to find the best average value
			Entry<HunterKillerOrder, OrderStatistics> best = null;
			double bestAverage = 0;
			for (Entry<HunterKillerOrder, OrderStatistics> entry : objectOrderMap.entrySet()) {
				double entryAverage = entry.getValue()
											.getAverage();
				if (entryAverage > bestAverage) {
					best = entry;
					bestAverage = entry.getValue()
										.getAverage();
				}
			}

			// Check if we found a best order
			if (best != null) {
				return best.getKey();
			}
			// Return null if we did not find a best action
			return null;
		}

		/**
		 * Clears the stored information.
		 */
		public void resetInformation() {
			sideInformation.clear();
		}

		/**
		 * Represents the known statistics of a {@link HunterKillerOrder}.
		 * 
		 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
		 *
		 */
		private class OrderStatistics {

			/**
			 * The amount of times this order's statistics have been updated.
			 */
			public int visits = 0;;
			/**
			 * Summation of the value attained by this order.
			 */
			public double value = 0;

			/**
			 * Constructor.
			 * 
			 * @param value
			 *            An initial value for this order.
			 */
			public OrderStatistics(double value) {
				this.visits = 1;
				this.value = value;
			}

			/**
			 * Increase the total value of this order.
			 * 
			 * @param value
			 *            The value to add.
			 */
			public void addValue(double value) {
				visits++;
				this.value += value;
			}

			/**
			 * Returns the average value of this order over the amount of visits.
			 */
			public double getAverage() {
				if (visits == 0)
					return 0;
				return value / visits;
			}

		}

		@Override
		public HMCTSState playout(SearchContext<Object, HMCTSState, PartialAction, Object, ?> context, HMCTSState state) {
			// Convert order so far into HunterKillerAction
			HunterKillerAction action = new HunterKillerAction(state.state);
			for (HunterKillerOrder order : state.combinedAction.orders) {
				action.addOrder(order);
			}

			// If we do not have an order for each dimension yet, use the ActionCompletionStrategy to generate them.
			if (!state.combinedAction.isComplete()) {
				Array<HunterKillerOrder> filledOrders = gameLogic.actionCompletion.fill(state.state,
																						state.combinedAction.currentOrdering,
																						state.combinedAction.nextDimension);
				// Add the generated orders to the action
				for (HunterKillerOrder order : filledOrders) {
					action.addOrder(order);
				}

				// Check if we are currently in a state where we want to save the orders
				if (context.source().state.getCurrentRound() == state.state.getCurrentRound()
					&& context.source().state.getActivePlayerID() == state.state.getActivePlayerID()) {
					playoutFilledOrders = filledOrders;
				}
			}

			// Apply the created action on the HunterKillerState
			rulesEngine.handle(state.state, action);

			// Call the playout bot to continuously play actions until the goal is reached.
			while (!goal.done(context, state)) {
				HunterKillerAction botAction = playoutBot.handle(state.state);
				rulesEngine.handle(state.state, botAction);
			}

			return state;
		}

		@Override
		public void backPropagate(SearchContext<?, HMCTSState, PartialAction, ?, ?> context,
				StateEvaluation<HMCTSState, PartialAction, ? super TreeSearchNode<HMCTSState, PartialAction>> evaluation,
				TreeSearchNode<HMCTSState, PartialAction> target, HMCTSState state) {
			// Calculate the depth of the target node we are starting this backpropagation from.
			int targetDepth = target.calculateDepth();
			// Check how many dimensions the root had to expand
			int rootDimensions = context.source().combinedAction.dimensions;
			// Determine the root player
			int sourcePlayer = context.source()
										.getPlayer();

			// Evaluate & add the score for the last node before playout, and use that value for all nodes.
			double evaluate = evaluation.evaluate(context, target, state);

			// Check if there are any orders filled during the playout
			if (playoutFilledOrders != null) {
				// Update the information on these orders according to the evaluation
				for (HunterKillerOrder order : playoutFilledOrders) {
					updateInformation(order.objectID, order, evaluate);
				}

				// We are done with this collection, reset it
				playoutFilledOrders = null;
			}

			// We keep moving if the node has a valid parent, aka we do not backpropagate to the root node as it
			// does not have a valid move
			while (target.getParent() != null) {
				// Check if we are at a depth where we want to update the information
				if (targetDepth < rootDimensions) {
					HunterKillerOrder order = target.getPayload().order;
					updateInformation(order.objectID, order, evaluate);
				}

				// Reduce the depth and move to parent
				targetDepth--;
				target = target.getParent();

				boolean targetPlayer = target.isRoot() || sourcePlayer == target.getPayload()
																				.getPlayer();
				// Visit the target with a colored evaluation
				target.visit(targetPlayer ? evaluate : -evaluate);
			}
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		public HunterKillerAction solution(SearchContext<?, ?, ?, ?, HunterKillerAction> context,
				TreeSearchNode<HMCTSState, PartialAction> node) {
			// Hold a reference to the action where we ended our search
			// Need this to fill up our action since this path might not have a partial action for each dimension
			PartialAction endAction = node.getPayload();
			HunterKillerState rootState = node.getParent()
												.getState().state;
			HunterKillerAction action = new HunterKillerAction(rootState);

			// Copy the partial action on this node to the main action
			action.addOrder(node.getPayload().order);
			int orderCount = 1;

			val mcts = (MCTS) context.search();
			val selection = mcts.getSelectionStrategy();

			// We cut if the node is a leaf node or when there are no more units to give orders to
			while (!node.isLeaf() && orderCount < endAction.currentOrdering.size) {
				// Select the next node
				node = selection.selectNextNode(context, node);
				// Add the node's order to the action
				orderCount++;
				action.addOrder(node.getPayload().order);
			}

			// Check if we need to fill out the action with dimensions that we haven't yet expanded into
			for (int i = endAction.nextDimensionIndex; i < endAction.currentOrdering.size; i++) {
				// Select the best order for this dimension, according to our side information
				HunterKillerOrder bestOrder = getBestAction(endAction.currentOrdering.get(i));
				if (bestOrder != null) {
					action.addOrder(bestOrder);
				} else {
					// If we could not get an answer from our side information, ask action completion
					HunterKillerOrder completionOrder = actionCompletion.fill(rootState, endAction.currentOrdering.get(i));
					if (completionOrder != null)
						action.addOrder(completionOrder);
				}
			}

			return action;
		}

	}

	/**
	 * Defines what a method that sorts controlled objects should support.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public static interface ControlledObjectSortingStrategy {

		/**
		 * Sorts the IDs of the objects controlled by the active player into an ordered {@link IntArray}.
		 * 
		 * @param state
		 *            The game state that contains the objects that should be sorted.
		 */
		public IntArray sort(HunterKillerState state);

	}

	/**
	 * Provides the sorting of controlled objects' IDs in a random way.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public static class RandomControlledObjectSorting
			implements ControlledObjectSortingStrategy {

		@Override
		public IntArray sort(HunterKillerState state) {
			Player player = state.getActivePlayer();
			Map map = state.getMap();
			IntArray output = new IntArray();
			// Select all objects controlled by this player and add their ID to the output array
			map.getObjects()
				.select(i -> i instanceof Controlled && ((Controlled) i).isControlledBy(player)
				// Filter out Structures that can't spawn (i.e. have no dimensions to expand)
								&& (!(i instanceof Structure) || ((Structure) i).canSpawnAUnit(state)))
				.forEach(i -> output.add(i.getID()));

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
		 * Returns a {@link HunterKillerOrder} for each controlled Object-ID in the ordering.
		 * {@link ActionCompletionStrategy#fill(HunterKillerState, HunterKillerAction, IntArray, int)}
		 */
		public Array<HunterKillerOrder> fill(HunterKillerState state, IntArray objectIDs);

		/**
		 * Returns a {@link HunterKillerOrder} for each controlled Object-ID in the ordering, starting from a specified
		 * index.
		 * 
		 * @param state
		 *            The state for which the action is being created.
		 * @param ordering
		 *            The current ordering of controlled objects.
		 * @param startIndex
		 *            The index in the ordering from where the filling should start.
		 */
		public Array<HunterKillerOrder> fill(HunterKillerState state, IntArray ordering, int startIndex);

		/**
		 * Returns a {@link HunterKillerOrder} for the specified {@link GameObject}.
		 * 
		 * @param state
		 *            The state for which the action is being created.
		 * @param objectID
		 *            The ID of the game object that needs an order to be filled.
		 */
		public HunterKillerOrder fill(HunterKillerState state, int objectID);

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
		public Array<HunterKillerOrder> fill(HunterKillerState state, IntArray ordering) {
			return fill(state, ordering, 0);
		}

		@Override
		public Array<HunterKillerOrder> fill(HunterKillerState state, IntArray ordering, int startIndex) {
			Array<HunterKillerOrder> orders = new Array<HunterKillerOrder>();
			// Create a random order for the remaining IDs in the ordering
			for (int i = startIndex; i < ordering.size; i++) {
				HunterKillerOrder order = fill(state, ordering.get(i));
				if (order != null)
					orders.add(order);
			}
			return orders;
		}

		@Override
		public HunterKillerOrder fill(HunterKillerState state, int objectID) {
			Map map = state.getMap();
			GameObject object = map.getObject(objectID);
			if (object instanceof Unit) {
				UnitOrder order = BaseBot.getRandomOrder(state, (Unit) object);
				if (order != null)
					return order;
			} else if (object instanceof Structure) {
				StructureOrder order = BaseBot.getRandomOrder(state, (Structure) object);
				if (order != null)
					return order;
			}
			return null;
		}

	}

}
